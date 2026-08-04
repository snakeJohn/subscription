/* auth.js — 多用户：账号 + 密码登录 + 会话 Token
 * 密码用 PBKDF2-SHA256(100k 迭代) 哈希后存 users.password_hash；
 * 会话 id 存 KV（值含 uid）并设 TTL，Cookie 为 HttpOnly + Secure + SameSite=Lax。 */

/* Workers 运行时上限为 100000 次迭代（超过会抛
 * "iteration counts above 100000 are not supported"），
 * 本地 Miniflare 不校验此限制，故必须以线上为准。 */
const ITER = 100000;
const SESSION_TTL = 60 * 60 * 24 * 30;   // 30 天
const COOKIE = 'substat_sid';

const enc = new TextEncoder();
const b64 = buf => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));

export function randHex(bytes = 32){
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return [...a].map(b => b.toString(16).padStart(2,'0')).join('');
}

async function pbkdf2(password, salt){
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name:'PBKDF2', salt, iterations:ITER, hash:'SHA-256' }, key, 256);
  return b64(bits);
}
export async function hashPassword(password){
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const hash = await pbkdf2(password, salt);
  return `pbkdf2$${ITER}$${b64(salt)}$${hash}`;
}
export async function verifyPassword(password, stored){
  if(!stored) return false;
  const parts = String(stored).split('$');
  if(parts.length !== 4 || parts[0] !== 'pbkdf2') return false;
  /* 夹到运行时上限：早期若写入过更高的迭代数，直接用会抛错 */
  const iter = Math.min(parseInt(parts[1], 10) || ITER, 100000);
  const salt = unb64(parts[2]);
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name:'PBKDF2', salt, iterations:iter, hash:'SHA-256' }, key, 256);
  return timingSafeEq(b64(bits), parts[3]);
}
/* 常数时间比较，避免通过响应时间侧信道推断哈希 */
function timingSafeEq(a, b){
  if(a.length !== b.length) return false;
  let d = 0;
  for(let i=0;i<a.length;i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

/* —— 用户 —— */
export const normUsername = s =>
  String(s || '').trim().toLowerCase();

/* 用户名规则：3–24 位，字母数字下划线连字符 */
export function validUsername(u){
  return /^[a-z0-9_-]{3,24}$/.test(u);
}

export async function findUserByName(env, username){
  return env.DB.prepare(
    'SELECT id,username,password_hash,is_admin FROM users WHERE username=?')
    .bind(normUsername(username)).first();
}
export async function findUserById(env, id){
  return env.DB.prepare(
    'SELECT id,username,password_hash,is_admin FROM users WHERE id=?').bind(id).first();
}
export async function countUsers(env){
  const r = await env.DB.prepare('SELECT COUNT(*) AS n FROM users').first();
  return r ? (r.n | 0) : 0;
}
/* 创建用户，返回用户 id；用户名已存在返回 null */
export async function createUser(env, username, password, isAdmin = 0){
  const uname = normUsername(username);
  const exist = await findUserByName(env, uname);
  if(exist) return null;
  const id = randHex(8);
  const hash = await hashPassword(password);
  await env.DB.prepare(
    'INSERT INTO users(id,username,password_hash,is_admin,created_at) VALUES(?,?,?,?,?)')
    .bind(id, uname, hash, isAdmin ? 1 : 0, Date.now()).run();
  return id;
}

/* 首次启动：若还没有任何用户且配置了 AUTH_PASSWORD，
 * 创建管理员并把既有（无归属）数据迁移到该账号。幂等：有用户即跳过。 */
export async function ensureAdmin(env){
  if(await countUsers(env) > 0) return;
  const plain = env.AUTH_PASSWORD;
  if(!plain) return;                       // 没有初始密码则不建管理员（全新库等注册）
  const uname = normUsername(env.ADMIN_USERNAME || 'admin') || 'admin';
  const adminId = await createUser(env, uname, plain, 1);
  if(!adminId) return;

  const now = Date.now();
  /* 1) 既有订阅（ALTER 后 user_id 默认 ''）归入管理员 */
  await env.DB.prepare(
    "UPDATE subscriptions SET user_id=? WHERE user_id IS NULL OR user_id=''")
    .bind(adminId).run();
  await env.DB.prepare(
    "UPDATE notify_log SET user_id=? WHERE user_id IS NULL OR user_id=''")
    .bind(adminId).run();

  /* 2) 旧的全局 settings：除汇率缓存外，都迁到管理员的 user_settings；password_hash 丢弃 */
  const rows = (await env.DB.prepare('SELECT key,value FROM settings').all()).results || [];
  const KEEP_GLOBAL = new Set(['rate', 'rate_at', 'rate_source']);
  const moved = [];
  for(const r of rows){
    if(r.key === 'password_hash' || KEEP_GLOBAL.has(r.key)) continue;
    moved.push(r);
  }
  if(moved.length){
    const stmt = env.DB.prepare(
      'INSERT INTO user_settings(user_id,key,value,updated_at) VALUES(?,?,?,?) ' +
      'ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at');
    await env.DB.batch(moved.map(r => stmt.bind(adminId, r.key, r.value, now)));
    /* 迁移后从全局表清掉，避免与新逻辑混淆 */
    const del = env.DB.prepare('DELETE FROM settings WHERE key=?');
    await env.DB.batch(moved.map(r => del.bind(r.key)));
  }
  await env.DB.prepare("DELETE FROM settings WHERE key='password_hash'").run();
}

export function parseCookies(req){
  const out = {};
  const raw = req.headers.get('Cookie') || '';
  for(const part of raw.split(';')){
    const i = part.indexOf('=');
    if(i > 0) out[part.slice(0,i).trim()] = part.slice(i+1).trim();
  }
  return out;
}
export function sessionCookie(sid, maxAge){
  const bits = [`${COOKIE}=${sid}`, 'Path=/', 'HttpOnly', 'Secure', 'SameSite=Lax',
                `Max-Age=${maxAge}`];
  return bits.join('; ');
}
export const clearCookie = () =>
  `${COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;

export async function createSession(env, uid){
  const sid = randHex(32);
  await env.KV.put(`sess:${sid}`, JSON.stringify({ uid, at: Date.now() }),
    { expirationTtl: SESSION_TTL });
  return { sid, ttl: SESSION_TTL };
}
/* 返回会话用户 id，未登录返回 null */
export async function sessionUser(req, env){
  const sid = parseCookies(req)[COOKIE];
  if(!sid) return null;
  const v = await env.KV.get(`sess:${sid}`, 'json');
  return v && v.uid ? v.uid : null;
}
export async function destroySession(req, env){
  const sid = parseCookies(req)[COOKIE];
  if(sid) await env.KV.delete(`sess:${sid}`);
}

/* 登录失败限流：同 IP 15 分钟内最多 8 次 */
export async function rateLimit(env, ip){
  const k = `rl:${ip}`;
  const n = parseInt(await env.KV.get(k) || '0', 10);
  if(n >= 8) return false;
  await env.KV.put(k, String(n+1), { expirationTtl: 900 });
  return true;
}
export const clearRateLimit = (env, ip) => env.KV.delete(`rl:${ip}`);
