/* index.js — Worker 入口：/api/* 路由 + cron 触发（多用户）
 * 静态资源由 wrangler.toml [assets] 托管；所有业务数据按会话用户隔离。 */

import { hashPassword, verifyPassword, createSession, sessionUser, destroySession,
         sessionCookie, clearCookie, rateLimit, clearRateLimit, randHex,
         ensureAdmin, findUserByName, findUserById, createUser,
         normUsername, validUsername } from './auth.js';
import { getRate } from './rate.js';
import { runNotify, runNotifyAll, loadUserSettings, globalRate, effectiveRate,
         notifyConfig, normalize, sendBark, sendTelegram, sendWebhook } from './notify.js';
import { webdavConfig, davTest, davBackup, davGet } from './webdav.js';
import { LIB } from '../public/js/catalog.js';
import { CYCLES, CYC_KEYS } from '../public/shared/billing.js';

const J = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {
  status, headers: { 'Content-Type':'application/json; charset=utf-8',
                     'Cache-Control':'no-store', ...headers },
});
const bad  = (msg, s = 400) => J({ error: msg }, s);
const need = () => J({ error:'未登录' }, 401);

const SUB_FIELDS = ['name','domain','cat','plan','price','cur','cycle','qty','start',
                    'note','nsfw','enabled','remind'];
/* 允许前端写入的每用户设置键（白名单，防止越权写别的字段） */
const SET_KEYS = ['rate','rate_mode','cur','warn','theme','week','nsfw','notify_days',
                  'notify_bark','notify_tg','notify_hook','bark_url','bark_sound',
                  'bark_level','tg_token','tg_chat','webhook_url','lib_collapsed',
                  'webdav_url','webdav_user','webdav_pass','webdav_auto'];

/* 建表语句；每个 isolate 只执行一次。首次部署无需手动 migration，本地 dev 也开箱可用。 */
const DDL = [
  `CREATE TABLE IF NOT EXISTS users(
     id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
     is_admin INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS subscriptions(
     id TEXT PRIMARY KEY, user_id TEXT NOT NULL DEFAULT '',
     name TEXT NOT NULL, domain TEXT DEFAULT '',
     cat TEXT NOT NULL DEFAULT 'ai', plan TEXT DEFAULT '',
     price REAL NOT NULL DEFAULT 0, cur TEXT NOT NULL DEFAULT 'CNY',
     cycle TEXT NOT NULL DEFAULT 'month', qty INTEGER NOT NULL DEFAULT 1,
     start TEXT NOT NULL, note TEXT DEFAULT '', nsfw INTEGER NOT NULL DEFAULT 0,
     enabled INTEGER NOT NULL DEFAULT 1, remind INTEGER NOT NULL DEFAULT 1,
     created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_subs_enabled ON subscriptions(enabled)`,
  `CREATE TABLE IF NOT EXISTS user_settings(
     user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
     updated_at INTEGER NOT NULL, PRIMARY KEY(user_id, key))`,
  `CREATE TABLE IF NOT EXISTS settings(
     key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS notify_log(
     id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL DEFAULT '',
     sub_id TEXT NOT NULL, due_date TEXT NOT NULL,
     channel TEXT NOT NULL, ok INTEGER NOT NULL DEFAULT 1, detail TEXT DEFAULT '',
     sent_at INTEGER NOT NULL, UNIQUE(sub_id, due_date, channel))`,
];
let schemaReady = false;
async function tryExec(env, sql){ try{ await env.DB.prepare(sql).run(); }catch(e){ /* 已存在等，忽略 */ } }
async function ensureSchema(env){
  if(schemaReady) return;
  await env.DB.batch(DDL.map(s => env.DB.prepare(s)));
  /* 既有库（单用户时代）补列：subscriptions / notify_log 加 user_id */
  await tryExec(env, "ALTER TABLE subscriptions ADD COLUMN user_id TEXT NOT NULL DEFAULT ''");
  await tryExec(env, "ALTER TABLE notify_log ADD COLUMN user_id TEXT NOT NULL DEFAULT ''");
  /* user_id 索引必须在补列之后建，否则既有库上引用未存在列会整批失败 */
  await tryExec(env, "CREATE INDEX IF NOT EXISTS idx_subs_user ON subscriptions(user_id)");
  await ensureAdmin(env);      // 首次：建管理员并迁移既有数据
  schemaReady = true;
}

export default {
  async fetch(req, env, ctx){
    const url = new URL(req.url);
    if(!url.pathname.startsWith('/api/')){
      /* run_worker_first：静态资源经 Worker 转发（不进边缘缓存）；HTML 声明 no-cache */
      const res = await env.ASSETS.fetch(req);
      if((res.headers.get('Content-Type') || '').includes('text/html')){
        const h = new Headers(res.headers);
        h.set('Cache-Control', 'no-cache');
        return new Response(res.body, { status: res.status, headers: h });
      }
      return res;
    }
    try{
      await ensureSchema(env);
      return await route(req, env, url, ctx);
    }catch(e){
      return J({ error:'服务端错误', detail:String(e && e.message || e) }, 500);
    }
  },
  async scheduled(event, env, ctx){
    ctx.waitUntil((async () => {
      await ensureSchema(env);
      /* 定时任务：刷新全局汇率后，逐用户推送到期提醒 */
      const r = await getRate(env, true);
      if(r.rate) await putGlobal(env, 'rate', String(r.rate));
      await runNotifyAll(env, {});
    })());
  },
};

async function route(req, env, url, ctx){
  const p = url.pathname.replace(/\/+$/, '') || '/api';
  const m = req.method;

  /* —— 无需鉴权 —— */
  if(p === '/api/health') return J({ ok:true, ts:Date.now() });

  if(p === '/api/auth/status'){
    const uid = await sessionUser(req, env);
    const user = uid ? await findUserById(env, uid) : null;
    return J({
      configured: true,
      registerOpen: !!env.REGISTER_CODE,
      authed: !!user,
      username: user ? user.username : null,
      isAdmin: user ? !!user.is_admin : false,
    });
  }
  if(p === '/api/auth/register' && m === 'POST'){
    const ip = req.headers.get('CF-Connecting-IP') || 'unknown';
    if(!await rateLimit(env, ip)) return bad('尝试过于频繁，请 15 分钟后再试', 429);
    if(!env.REGISTER_CODE) return bad('本站未开放注册', 403);
    const b = await req.json().catch(() => ({}));
    if(String(b.code || '') !== env.REGISTER_CODE) return bad('注册码不正确', 403);
    const username = normUsername(b.username);
    if(!validUsername(username)) return bad('用户名需为 3–24 位字母、数字、下划线或连字符');
    const pw = String(b.password || '');
    if(pw.length < 6) return bad('密码至少 6 位');
    const uid = await createUser(env, username, pw, 0);
    if(!uid) return bad('用户名已被占用', 409);
    await clearRateLimit(env, ip);
    const { sid, ttl } = await createSession(env, uid);
    return J({ ok:true, username }, 201, { 'Set-Cookie': sessionCookie(sid, ttl) });
  }
  if(p === '/api/auth/login' && m === 'POST'){
    const ip = req.headers.get('CF-Connecting-IP') || 'unknown';
    if(!await rateLimit(env, ip)) return bad('尝试过于频繁，请 15 分钟后再试', 429);
    const body = await req.json().catch(() => ({}));
    const user = await findUserByName(env, body.username);
    if(!user || !await verifyPassword(String(body.password || ''), user.password_hash))
      return bad('用户名或密码错误', 401);
    await clearRateLimit(env, ip);
    const { sid, ttl } = await createSession(env, user.id);
    return J({ ok:true, username: user.username }, 200, { 'Set-Cookie': sessionCookie(sid, ttl) });
  }
  if(p === '/api/auth/logout' && m === 'POST'){
    await destroySession(req, env);
    return J({ ok:true }, 200, { 'Set-Cookie': clearCookie() });
  }

  /* —— 以下全部需要登录，并绑定到该用户 —— */
  const uid = await sessionUser(req, env);
  if(!uid) return need();
  const me = await findUserById(env, uid);
  if(!me) return need();          // 会话指向已删除用户

  if(p === '/api/auth/password' && m === 'POST'){
    const b = await req.json().catch(() => ({}));
    if(!await verifyPassword(String(b.current || ''), me.password_hash))
      return bad('当前密码错误', 401);
    const np = String(b.password || '');
    if(np.length < 6) return bad('新密码至少 6 位');
    await env.DB.prepare('UPDATE users SET password_hash=? WHERE id=?')
      .bind(await hashPassword(np), uid).run();
    return J({ ok:true });
  }

  /* —— 订阅 CRUD（均按 user_id 隔离） —— */
  if(p === '/api/subscriptions'){
    if(m === 'GET'){
      const r = await env.DB.prepare(
        'SELECT * FROM subscriptions WHERE user_id=? ORDER BY created_at DESC').bind(uid).all();
      return J({ items: (r.results || []).map(normalize) });
    }
    if(m === 'POST'){
      const b = await req.json().catch(() => ({}));
      const v = validate(b);
      if(v.error) return bad(v.error);
      const now = Date.now();
      const id = randHex(8);
      const d = v.data;
      await env.DB.prepare(
        `INSERT INTO subscriptions(id,user_id,name,domain,cat,plan,price,cur,cycle,qty,start,
         note,nsfw,enabled,remind,created_at,updated_at)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
        .bind(id, uid, d.name, d.domain, d.cat, d.plan, d.price, d.cur, d.cycle, d.qty,
              d.start, d.note, d.nsfw, d.enabled, d.remind, now, now).run();
      return J({ ok:true, id }, 201);
    }
    if(m === 'DELETE'){          /* 清空自己的全部 */
      await env.DB.prepare('DELETE FROM subscriptions WHERE user_id=?').bind(uid).run();
      await env.DB.prepare('DELETE FROM notify_log WHERE user_id=?').bind(uid).run();
      return J({ ok:true });
    }
    return bad('方法不允许', 405);
  }
  /* 批量导入。放在 /:id 之前，且下方 id 正则显式排除 "bulk"。 */
  if(p === '/api/subscriptions/bulk'){
    if(m !== 'POST') return bad('方法不允许', 405);
    const b = await req.json().catch(() => ({}));
    const arr = Array.isArray(b.items) ? b.items : [];
    if(!arr.length) return bad('没有可导入的数据');
    if(arr.length > 2000) return bad('单次最多导入 2000 条');
    if(b.mode === 'replace') await env.DB.prepare('DELETE FROM subscriptions WHERE user_id=?').bind(uid).run();
    const now = Date.now();
    const stmt = env.DB.prepare(
      `INSERT INTO subscriptions(id,user_id,name,domain,cat,plan,price,cur,cycle,qty,start,
       note,nsfw,enabled,remind,created_at,updated_at)
       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`);
    const batch = [];
    let skipped = 0;
    for(const raw of arr){
      const v = validate(raw);
      if(v.error){ skipped++; continue; }
      const d = v.data;
      batch.push(stmt.bind(randHex(8), uid, d.name, d.domain, d.cat, d.plan, d.price, d.cur,
        d.cycle, d.qty, d.start, d.note, d.nsfw, d.enabled, d.remind,
        +raw.created_at || now, now));
    }
    if(!batch.length) return bad('全部记录校验失败，未导入');
    for(let i=0;i<batch.length;i+=50) await env.DB.batch(batch.slice(i, i+50));
    return J({ ok:true, imported: batch.length, skipped });
  }

  const sm = p.match(/^\/api\/subscriptions\/(?!bulk$)([A-Za-z0-9_-]+)$/);
  if(sm){
    const id = sm[1];
    if(m === 'GET'){
      const row = await env.DB.prepare('SELECT * FROM subscriptions WHERE id=? AND user_id=?')
        .bind(id, uid).first();
      return row ? J(normalize(row)) : bad('未找到', 404);
    }
    if(m === 'PUT' || m === 'PATCH'){
      const exist = await env.DB.prepare('SELECT * FROM subscriptions WHERE id=? AND user_id=?')
        .bind(id, uid).first();
      if(!exist) return bad('未找到', 404);
      const b = await req.json().catch(() => ({}));
      const merged = { ...normalize(exist), ...b };
      const v = validate(merged);
      if(v.error) return bad(v.error);
      const d = v.data;
      await env.DB.prepare(
        `UPDATE subscriptions SET name=?,domain=?,cat=?,plan=?,price=?,cur=?,cycle=?,
         qty=?,start=?,note=?,nsfw=?,enabled=?,remind=?,updated_at=? WHERE id=? AND user_id=?`)
        .bind(d.name, d.domain, d.cat, d.plan, d.price, d.cur, d.cycle, d.qty, d.start,
              d.note, d.nsfw, d.enabled, d.remind, Date.now(), id, uid).run();
      return J({ ok:true });
    }
    if(m === 'DELETE'){
      await env.DB.prepare('DELETE FROM subscriptions WHERE id=? AND user_id=?').bind(id, uid).run();
      await env.DB.prepare('DELETE FROM notify_log WHERE sub_id=? AND user_id=?').bind(id, uid).run();
      return J({ ok:true });
    }
    return bad('方法不允许', 405);
  }

  /* —— 设置（每用户） —— */
  if(p === '/api/settings'){
    if(m === 'GET'){
      const st = await loadUserSettings(env, uid);
      /* 生效汇率：手动模式用本人 rate，否则用全局自动汇率 */
      st.rate = String(effectiveRate(st, await globalRate(env)));
      const secretSet = {
        bark: !!(env.BARK_URL || st.bark_url),
        tg:   !!((env.TG_BOT_TOKEN || st.tg_token) && (env.TG_CHAT_ID || st.tg_chat)),
        bark_from_env: !!env.BARK_URL, tg_from_env: !!env.TG_BOT_TOKEN,
      };
      for(const k of ['tg_token','bark_url','webhook_url','webdav_pass'])
        if(st[k]) st[k] = mask(st[k]);
      return J({ settings: st, channels: secretSet, username: me.username });
    }
    if(m === 'PUT' || m === 'POST'){
      const b = await req.json().catch(() => ({}));
      const writes = [];
      for(const [k, v] of Object.entries(b)){
        if(!SET_KEYS.includes(k)) continue;
        if(typeof v === 'string' && v.startsWith('••')) continue;   /* 掩码值不覆盖 */
        writes.push([k, String(v)]);
      }
      if(!writes.length) return bad('没有可保存的设置项');
      const now = Date.now();
      const stmt = env.DB.prepare(
        'INSERT INTO user_settings(user_id,key,value,updated_at) VALUES(?,?,?,?) ' +
        'ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at');
      await env.DB.batch(writes.map(([k,v]) => stmt.bind(uid, k, v, now)));
      return J({ ok:true, saved: writes.length });
    }
    return bad('方法不允许', 405);
  }

  /* —— 汇率 —— */
  if(p === '/api/rate'){
    const force = url.searchParams.get('refresh') === '1';
    const r = await getRate(env, force);
    if(r.rate && force){
      await putGlobal(env, 'rate', String(r.rate));        /* 全局自动值 */
      await putUserSetting(env, uid, 'rate', String(r.rate)); /* 本人生效值 */
    }
    return J(r);
  }

  /* —— 提醒（本人订阅 + 本人渠道） —— */
  if(p === '/api/notify/test' && m === 'POST'){
    const b = await req.json().catch(() => ({}));
    const st = await loadUserSettings(env, uid);
    const cfg = notifyConfig(env, st);
    const ch = b.channel || 'bark';
    const title = 'SubStat 测试通知';
    const body = '这是一条测试消息，说明通知渠道配置正确。';
    let r;
    if(ch === 'bark') r = await sendBark(cfg.bark, title, body);
    else if(ch === 'tg') r = await sendTelegram(cfg.tg, `<b>${title}</b>\n${body}`);
    else if(ch === 'hook') r = await sendWebhook(cfg.hook, { event:'test', title, body });
    else return bad('未知渠道');
    return J({ ok:r.ok, status:r.status, detail:r.body });
  }
  if(p === '/api/notify/run' && m === 'POST'){
    const b = await req.json().catch(() => ({}));
    return J(await runNotify(env, uid, { force: !!b.force, all: !!b.all }));
  }
  if(p === '/api/notify/log'){
    const r = await env.DB.prepare(
      'SELECT * FROM notify_log WHERE user_id=? ORDER BY sent_at DESC LIMIT 50').bind(uid).all();
    return J({ items: r.results || [] });
  }

  /* —— WebDAV 云备份（每用户） —— */
  if(p === '/api/webdav/test' && m === 'POST'){
    const st = await loadUserSettings(env, uid);
    const b = await req.json().catch(() => ({}));
    const cfg = webdavConfig({
      webdav_url:  b.url  ?? st.webdav_url,
      webdav_user: b.user ?? st.webdav_user,
      webdav_pass: (b.pass && !String(b.pass).startsWith('••')) ? b.pass : st.webdav_pass,
    });
    if(!cfg.url) return bad('请先填写 WebDAV 地址');
    return J(await davTest(cfg));
  }
  if(p === '/api/webdav/backup' && m === 'POST'){
    const st = await loadUserSettings(env, uid);
    const { items, payload } = await buildBackup(env, uid, st);
    const out = await davBackup(webdavConfig(st), payload);
    if(out.ok) await putUserSetting(env, uid, 'webdav_last',
      JSON.stringify({ at: Date.now(), n: items.length }));
    return J({ ...out, count: items.length });
  }
  if(p === '/api/webdav/restore' && m === 'POST'){
    const st = await loadUserSettings(env, uid);
    return J(await davGet(webdavConfig(st)));
  }

  /* —— 服务库 / 元数据（全局，无需隔离） —— */
  if(p === '/api/catalog') return J({
    items: LIB.map(x => ({
      cat: x.cat, name: x.name, domain: x.domain, nsfw: x.nsfw ? 1 : 0,
      plans: x.plans.map(pl => ({ plan: pl[0], price: pl[1], cur: pl[2], cycle: pl[3] })),
    })),
  });
  if(p === '/api/meta') return J({ cycles: CYCLES, cycleKeys: CYC_KEYS });

  return bad('接口不存在', 404);
}

async function putGlobal(env, k, v){
  await env.DB.prepare(
    'INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) ' +
    'ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at')
    .bind(k, String(v), Date.now()).run();
}
async function putUserSetting(env, uid, k, v){
  await env.DB.prepare(
    'INSERT INTO user_settings(user_id,key,value,updated_at) VALUES(?,?,?,?) ' +
    'ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at')
    .bind(uid, k, String(v), Date.now()).run();
}
const mask = s => s.length <= 8 ? '••••' : '••••' + s.slice(-4);

/* 组装备份内容：与前端「导出 JSON」同构（v:2 + items），恢复时可直接走 bulk 导入 */
async function buildBackup(env, uid, st){
  const r = await env.DB.prepare(
    'SELECT * FROM subscriptions WHERE user_id=? ORDER BY created_at').bind(uid).all();
  const items = (r.results || []).map(normalize);
  const payload = JSON.stringify({
    v:2, at: new Date().toISOString(), source: 'manual',
    settings: { rate: st.rate, cur: st.cur }, items,
  }, null, 2);
  return { items, payload };
}

/* 服务端校验：前端可绕过，故所有写入必须过这里 */
function validate(b){
  const name = String(b.name ?? '').trim();
  if(!name) return { error:'服务名称不能为空' };
  if(name.length > 100) return { error:'服务名称过长' };
  const price = Number(b.price);
  if(!isFinite(price) || price < 0) return { error:'单价必须为非负数' };
  if(price > 1e9) return { error:'单价超出合理范围' };
  const cycle = String(b.cycle ?? 'month');
  if(!CYCLES[cycle]) return { error:'计费周期无效' };
  const cur = b.cur === 'USD' ? 'USD' : 'CNY';
  const qty = Math.max(1, Math.min(9999, parseInt(b.qty, 10) || 1));
  const start = String(b.start ?? '').slice(0,10);
  if(!/^\d{4}-\d{2}-\d{2}$/.test(start)) return { error:'付费日期格式应为 YYYY-MM-DD' };
  const d = new Date(start + 'T00:00:00');
  if(isNaN(d.getTime())) return { error:'付费日期无效' };
  const int01 = v => (v === true || v === 1 || v === '1') ? 1 : 0;
  return { data:{
    name, domain: String(b.domain ?? '').trim().replace(/^https?:\/\//,'').replace(/\/.*$/,'').slice(0,120),
    cat: String(b.cat ?? 'ai').slice(0,32),
    plan: String(b.plan ?? '').slice(0,80),
    price, cur, cycle, qty, start,
    note: String(b.note ?? '').slice(0,500),
    nsfw: int01(b.nsfw),
    enabled: (b.enabled === undefined) ? 1 : int01(b.enabled),
    remind:  (b.remind  === undefined) ? 1 : int01(b.remind),
  }};
}
