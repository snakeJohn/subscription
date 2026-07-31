/* auth.js — 密码登录 + 会话 Token
 * 密码用 PBKDF2-SHA256(210k 迭代) 哈希后存 D1；
 * 会话 id 存 KV 并设 TTL，Cookie 为 HttpOnly + Secure + SameSite=Lax。 */

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

/* 首次访问：把 secret 里的明文密码哈希入库 */
export async function ensurePassword(env){
  const row = await env.DB.prepare('SELECT value FROM settings WHERE key=?')
    .bind('password_hash').first();
  if(row && row.value) return true;
  const plain = env.AUTH_PASSWORD;
  if(!plain) return false;
  const h = await hashPassword(plain);
  await env.DB.prepare(
    'INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) ' +
    'ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at')
    .bind('password_hash', h, Date.now()).run();
  return true;
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

export async function createSession(env){
  const sid = randHex(32);
  await env.KV.put(`sess:${sid}`, JSON.stringify({ at: Date.now() }),
    { expirationTtl: SESSION_TTL });
  return { sid, ttl: SESSION_TTL };
}
export async function checkSession(req, env){
  const sid = parseCookies(req)[COOKIE];
  if(!sid) return false;
  const v = await env.KV.get(`sess:${sid}`);
  return !!v;
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
