/* index.js — Worker 入口：/api/* 路由 + cron 触发
 * 静态资源由 wrangler.toml [assets] 托管，未匹配 /api 的请求不进此文件的业务逻辑。 */

import { ensurePassword, verifyPassword, hashPassword, createSession, checkSession,
         destroySession, sessionCookie, clearCookie, rateLimit, clearRateLimit,
         randHex } from './auth.js';
import { getRate } from './rate.js';
import { runNotify, loadSettings, notifyConfig, normalize,
         sendBark, sendTelegram, sendWebhook } from './notify.js';
import { webdavConfig, davTest, davBackup, davGet } from './webdav.js';
import { CYCLES, CYC_KEYS } from '../public/shared/billing.js';

const J = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {
  status, headers: { 'Content-Type':'application/json; charset=utf-8',
                     'Cache-Control':'no-store', ...headers },
});
const bad  = (msg, s = 400) => J({ error: msg }, s);
const need = () => J({ error:'未登录' }, 401);

const SUB_FIELDS = ['name','domain','cat','plan','price','cur','cycle','qty','start',
                    'note','nsfw','enabled','remind'];
/* 允许前端写入的设置键（白名单，防止越权写 password_hash） */
const SET_KEYS = ['rate','rate_mode','cur','warn','theme','week','nsfw','notify_days',
                  'notify_bark','notify_tg','notify_hook','bark_url','bark_sound',
                  'bark_level','tg_token','tg_chat','webhook_url','lib_collapsed',
                  'webdav_url','webdav_user','webdav_pass','webdav_auto'];

/* 建表语句与 schema.sql 保持一致；每个 isolate 只执行一次。
 * 好处是首次部署无需手动跑 migration，本地 dev 也开箱可用。 */
const DDL = [
  `CREATE TABLE IF NOT EXISTS subscriptions(
     id TEXT PRIMARY KEY, name TEXT NOT NULL, domain TEXT DEFAULT '',
     cat TEXT NOT NULL DEFAULT 'ai', plan TEXT DEFAULT '',
     price REAL NOT NULL DEFAULT 0, cur TEXT NOT NULL DEFAULT 'CNY',
     cycle TEXT NOT NULL DEFAULT 'month', qty INTEGER NOT NULL DEFAULT 1,
     start TEXT NOT NULL, note TEXT DEFAULT '', nsfw INTEGER NOT NULL DEFAULT 0,
     enabled INTEGER NOT NULL DEFAULT 1, remind INTEGER NOT NULL DEFAULT 1,
     created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_subs_enabled ON subscriptions(enabled)`,
  `CREATE TABLE IF NOT EXISTS settings(
     key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS notify_log(
     id INTEGER PRIMARY KEY AUTOINCREMENT, sub_id TEXT NOT NULL, due_date TEXT NOT NULL,
     channel TEXT NOT NULL, ok INTEGER NOT NULL DEFAULT 1, detail TEXT DEFAULT '',
     sent_at INTEGER NOT NULL, UNIQUE(sub_id, due_date, channel))`,
];
let schemaReady = false;
async function ensureSchema(env){
  if(schemaReady) return;
  await env.DB.batch(DDL.map(s => env.DB.prepare(s)));
  schemaReady = true;
}

export default {
  async fetch(req, env, ctx){
    const url = new URL(req.url);
    if(!url.pathname.startsWith('/api/')){
      /* run_worker_first 开启后静态资源也先进 Worker：Worker 响应不进边缘 HTTP 缓存，
       * 避免个别节点在部署后仍吐旧 HTML；HTML 额外声明 no-cache 让浏览器必回源校验 */
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
      /* 定时任务同时刷新汇率（若为自动模式）并推送到期提醒 */
      const st = await loadSettings(env);
      if(st.rate_mode !== 'manual'){
        const r = await getRate(env, true);
        if(r.rate) await putSetting(env, 'rate', String(r.rate));
      }
      await runNotify(env, {});
      /* 每日自动备份到 WebDAV（若启用）；失败不影响提醒任务 */
      if(st.webdav_auto === '1' && st.webdav_url){
        try{
          const { items, payload } = await buildBackup(env, st, 'cron');
          const out = await davBackup(webdavConfig(st), payload);
          if(out.ok) await putSetting(env, 'webdav_last',
            JSON.stringify({ at: Date.now(), n: items.length }));
        }catch(e){ /* 网络异常等，静默跳过，下次 cron 再试 */ }
      }
    })());
  },
};

async function route(req, env, url, ctx){
  const p = url.pathname.replace(/\/+$/, '') || '/api';
  const m = req.method;

  /* —— 无需鉴权 —— */
  if(p === '/api/health') return J({ ok:true, ts:Date.now() });

  if(p === '/api/auth/status'){
    const configured = await ensurePassword(env);
    return J({ configured, authed: await checkSession(req, env) });
  }
  if(p === '/api/auth/login' && m === 'POST'){
    const ip = req.headers.get('CF-Connecting-IP') || 'unknown';
    if(!await rateLimit(env, ip)) return bad('尝试过于频繁，请 15 分钟后再试', 429);
    await ensurePassword(env);
    const body = await req.json().catch(() => ({}));
    const row = await env.DB.prepare('SELECT value FROM settings WHERE key=?')
      .bind('password_hash').first();
    if(!row || !row.value) return bad('服务端未设置密码，请先配置 AUTH_PASSWORD', 503);
    if(!await verifyPassword(String(body.password || ''), row.value))
      return bad('密码错误', 401);
    await clearRateLimit(env, ip);
    const { sid, ttl } = await createSession(env);
    return J({ ok:true }, 200, { 'Set-Cookie': sessionCookie(sid, ttl) });
  }
  if(p === '/api/auth/logout' && m === 'POST'){
    await destroySession(req, env);
    return J({ ok:true }, 200, { 'Set-Cookie': clearCookie() });
  }

  /* —— 以下全部需要登录 —— */
  if(!await checkSession(req, env)) return need();

  if(p === '/api/auth/password' && m === 'POST'){
    const b = await req.json().catch(() => ({}));
    const old = await env.DB.prepare('SELECT value FROM settings WHERE key=?')
      .bind('password_hash').first();
    if(old && old.value && !await verifyPassword(String(b.current || ''), old.value))
      return bad('当前密码错误', 401);
    const np = String(b.password || '');
    if(np.length < 6) return bad('新密码至少 6 位');
    await putSetting(env, 'password_hash', await hashPassword(np));
    return J({ ok:true });
  }

  /* —— 订阅 CRUD —— */
  if(p === '/api/subscriptions'){
    if(m === 'GET'){
      const r = await env.DB.prepare(
        'SELECT * FROM subscriptions ORDER BY created_at DESC').all();
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
        `INSERT INTO subscriptions(id,name,domain,cat,plan,price,cur,cycle,qty,start,
         note,nsfw,enabled,remind,created_at,updated_at)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
        .bind(id, d.name, d.domain, d.cat, d.plan, d.price, d.cur, d.cycle, d.qty,
              d.start, d.note, d.nsfw, d.enabled, d.remind, now, now).run();
      return J({ ok:true, id }, 201);
    }
    if(m === 'DELETE'){          /* 清空全部 */
      await env.DB.prepare('DELETE FROM subscriptions').run();
      await env.DB.prepare('DELETE FROM notify_log').run();
      return J({ ok:true });
    }
    return bad('方法不允许', 405);
  }
  /* 批量导入。放在 /:id 之前，且下方 id 正则显式排除 "bulk"，
   * 避免将来新增子路径时再次被 /:id 抢先匹配。 */
  if(p === '/api/subscriptions/bulk'){
    if(m !== 'POST') return bad('方法不允许', 405);
    const b = await req.json().catch(() => ({}));
    const arr = Array.isArray(b.items) ? b.items : [];
    if(!arr.length) return bad('没有可导入的数据');
    if(arr.length > 2000) return bad('单次最多导入 2000 条');
    if(b.mode === 'replace') await env.DB.prepare('DELETE FROM subscriptions').run();
    const now = Date.now();
    const stmt = env.DB.prepare(
      `INSERT INTO subscriptions(id,name,domain,cat,plan,price,cur,cycle,qty,start,
       note,nsfw,enabled,remind,created_at,updated_at)
       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`);
    const batch = [];
    let skipped = 0;
    for(const raw of arr){
      const v = validate(raw);
      if(v.error){ skipped++; continue; }
      const d = v.data;
      batch.push(stmt.bind(randHex(8), d.name, d.domain, d.cat, d.plan, d.price, d.cur,
        d.cycle, d.qty, d.start, d.note, d.nsfw, d.enabled, d.remind,
        +raw.created_at || now, now));
    }
    if(!batch.length) return bad('全部记录校验失败，未导入');
    /* D1 批量上限保守分片 */
    for(let i=0;i<batch.length;i+=50) await env.DB.batch(batch.slice(i, i+50));
    return J({ ok:true, imported: batch.length, skipped });
  }

  const sm = p.match(/^\/api\/subscriptions\/(?!bulk$)([A-Za-z0-9_-]+)$/);
  if(sm){
    const id = sm[1];
    if(m === 'GET'){
      const row = await env.DB.prepare('SELECT * FROM subscriptions WHERE id=?').bind(id).first();
      return row ? J(normalize(row)) : bad('未找到', 404);
    }
    if(m === 'PUT' || m === 'PATCH'){
      const exist = await env.DB.prepare('SELECT * FROM subscriptions WHERE id=?')
        .bind(id).first();
      if(!exist) return bad('未找到', 404);
      const b = await req.json().catch(() => ({}));
      /* PATCH 允许部分字段，合并后再校验 */
      const merged = { ...normalize(exist), ...b };
      const v = validate(merged);
      if(v.error) return bad(v.error);
      const d = v.data;
      await env.DB.prepare(
        `UPDATE subscriptions SET name=?,domain=?,cat=?,plan=?,price=?,cur=?,cycle=?,
         qty=?,start=?,note=?,nsfw=?,enabled=?,remind=?,updated_at=? WHERE id=?`)
        .bind(d.name, d.domain, d.cat, d.plan, d.price, d.cur, d.cycle, d.qty, d.start,
              d.note, d.nsfw, d.enabled, d.remind, Date.now(), id).run();
      return J({ ok:true });
    }
    if(m === 'DELETE'){
      await env.DB.prepare('DELETE FROM subscriptions WHERE id=?').bind(id).run();
      await env.DB.prepare('DELETE FROM notify_log WHERE sub_id=?').bind(id).run();
      return J({ ok:true });
    }
    return bad('方法不允许', 405);
  }

  /* —— 设置 —— */
  if(p === '/api/settings'){
    if(m === 'GET'){
      const st = await loadSettings(env);
      delete st.password_hash;
      /* 敏感项只返回是否已配置，不回显明文 */
      const secretSet = {
        bark: !!(env.BARK_URL || st.bark_url),
        tg:   !!((env.TG_BOT_TOKEN || st.tg_token) && (env.TG_CHAT_ID || st.tg_chat)),
        bark_from_env: !!env.BARK_URL, tg_from_env: !!env.TG_BOT_TOKEN,
      };
      for(const k of ['tg_token','bark_url','webhook_url','webdav_pass'])
        if(st[k]) st[k] = mask(st[k]);
      return J({ settings: st, channels: secretSet });
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
        'INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) ' +
        'ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at');
      await env.DB.batch(writes.map(([k,v]) => stmt.bind(k, v, now)));
      return J({ ok:true, saved: writes.length });
    }
    return bad('方法不允许', 405);
  }

  /* —— 汇率 —— */
  if(p === '/api/rate'){
    const force = url.searchParams.get('refresh') === '1';
    const r = await getRate(env, force);
    if(r.rate && force) await putSetting(env, 'rate', String(r.rate));
    return J(r);
  }

  /* —— 提醒 —— */
  if(p === '/api/notify/test' && m === 'POST'){
    const b = await req.json().catch(() => ({}));
    const st = await loadSettings(env);
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
    return J(await runNotify(env, { force: !!b.force, all: !!b.all }));
  }
  if(p === '/api/notify/log'){
    const r = await env.DB.prepare(
      'SELECT * FROM notify_log ORDER BY sent_at DESC LIMIT 50').all();
    return J({ items: r.results || [] });
  }

  /* —— WebDAV 云备份 —— */
  if(p === '/api/webdav/test' && m === 'POST'){
    const st = await loadSettings(env);
    const b = await req.json().catch(() => ({}));
    /* 允许直接用表单里未保存的值测试；掩码密码回退到已保存值 */
    const cfg = webdavConfig({
      webdav_url:  b.url  ?? st.webdav_url,
      webdav_user: b.user ?? st.webdav_user,
      webdav_pass: (b.pass && !String(b.pass).startsWith('••')) ? b.pass : st.webdav_pass,
    });
    if(!cfg.url) return bad('请先填写 WebDAV 地址');
    return J(await davTest(cfg));
  }
  if(p === '/api/webdav/backup' && m === 'POST'){
    const st = await loadSettings(env);
    const { items, payload } = await buildBackup(env, st, 'manual');
    const out = await davBackup(webdavConfig(st), payload);
    if(out.ok) await putSetting(env, 'webdav_last',
      JSON.stringify({ at: Date.now(), n: items.length }));
    return J({ ...out, count: items.length });
  }
  if(p === '/api/webdav/restore' && m === 'POST'){
    const st = await loadSettings(env);
    return J(await davGet(webdavConfig(st)));
  }

  /* —— 元数据 —— */
  if(p === '/api/meta') return J({ cycles: CYCLES, cycleKeys: CYC_KEYS });

  return bad('接口不存在', 404);
}

async function putSetting(env, k, v){
  await env.DB.prepare(
    'INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) ' +
    'ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at')
    .bind(k, String(v), Date.now()).run();
}

/* 组装备份内容：与前端「导出 JSON」同构（v:2 + items），恢复时可直接走 bulk 导入 */
async function buildBackup(env, st, source){
  const r = await env.DB.prepare(
    'SELECT * FROM subscriptions ORDER BY created_at').all();
  const items = (r.results || []).map(normalize);
  const payload = JSON.stringify({
    v:2, at: new Date().toISOString(), source,
    settings: { rate: st.rate, cur: st.cur }, items,
  }, null, 2);
  return { items, payload };
}
const mask = s => s.length <= 8 ? '••••' : '••••' + s.slice(-4);

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
