/* notify.js — 到期提醒推送：Bark / Telegram / 自定义 Webhook
 * 由 cron 触发（wrangler.toml crons），或 POST /api/notify/test 手动测试。 */

import { occurrences, nextDue, isoD, fmt, CYCLES, today, diffDays } from '../public/shared/billing.js';

const TIMEOUT = 8000;

async function post(url, opts){
  const ac = new AbortController();
  const tm = setTimeout(() => ac.abort(), TIMEOUT);
  try{
    const r = await fetch(url, { ...opts, signal: ac.signal });
    const txt = await r.text().catch(() => '');
    return { ok: r.ok, status: r.status, body: txt.slice(0, 200) };
  }catch(e){
    return { ok:false, status:0, body: String(e.message || e).slice(0,200) };
  }finally{
    clearTimeout(tm);
  }
}

/* —— Bark —— */
export async function sendBark(cfg, title, body, group){
  if(!cfg.url) return { ok:false, body:'未配置 Bark 地址' };
  const base = cfg.url.replace(/\/+$/, '');
  return post(base, {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify({
      title, body, group: group || 'SubStat',
      sound: cfg.sound || 'bell',
      icon: 'https://cdn.jsdelivr.net/gh/twitter/twemoji@latest/assets/72x72/1f4b8.png',
      level: cfg.level || 'active',
    }),
  });
}

/* —— Telegram —— */
export async function sendTelegram(cfg, text){
  if(!cfg.token || !cfg.chat) return { ok:false, body:'未配置 Telegram Token 或 Chat ID' };
  return post(`https://api.telegram.org/bot${cfg.token}/sendMessage`, {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify({
      chat_id: cfg.chat, text, parse_mode:'HTML', disable_web_page_preview:true,
    }),
  });
}

/* —— 自定义 Webhook —— */
export async function sendWebhook(cfg, payload){
  if(!cfg.url) return { ok:false, body:'未配置 Webhook 地址' };
  return post(cfg.url, {
    method:'POST',
    headers:{ 'Content-Type':'application/json' },
    body: JSON.stringify(payload),
  });
}

/* 读取通知配置：secret 优先，其次 D1 settings */
export function notifyConfig(env, st){
  return {
    bark: { url: env.BARK_URL || st.bark_url || '',
            sound: st.bark_sound || 'bell', level: st.bark_level || 'active' },
    tg:   { token: env.TG_BOT_TOKEN || st.tg_token || '',
            chat: env.TG_CHAT_ID || st.tg_chat || '' },
    hook: { url: st.webhook_url || '' },
    enabled: {
      bark: st.notify_bark !== '0',
      tg:   st.notify_tg   !== '0',
      hook: st.notify_hook === '1',
    },
  };
}

/* 组装提醒文案 */
function buildMsg(items, cur, rate){
  const t = today();
  const lines = items.map(o => {
    const d = diffDays(o.date, t);
    const when = d === 0 ? '今天' : d === 1 ? '明天' : `${d} 天后`;
    return `· ${o.sub.name}${o.sub.plan ? ' ' + o.sub.plan : ''} — ${fmt(o.amt, cur)}（${when} ${isoD(o.date)}）`;
  });
  const sum = items.reduce((s,o) => s + o.amt, 0);
  const title = items.length === 1
    ? `${items[0].sub.name} 即将扣费 ${fmt(items[0].amt, cur)}`
    : `${items.length} 笔订阅即将扣费 合计 ${fmt(sum, cur)}`;
  return { title, body: lines.join('\n'), sum };
}

/* 主流程：某个用户窗口内待提醒的账单，逐条推送并写去重日志 */
export async function runNotify(env, uid, opts = {}){
  const st = await loadUserSettings(env, uid);
  const cfg = notifyConfig(env, st);
  const cur = st.cur || 'CNY';
  const rate = effectiveRate(st, await globalRate(env));
  const warn = Math.max(0, Math.min(60, parseInt(st.notify_days || st.warn || '3', 10)));

  const rows = await env.DB.prepare(
    'SELECT * FROM subscriptions WHERE user_id=? AND enabled=1 AND remind=1').bind(uid).all();
  const subs = (rows.results || []).map(normalize);

  /* 只取窗口边界当天到期的（避免每天重复推同一笔） */
  const all = occurrences(subs, warn, cur, rate);
  const t = today();
  const hit = all.filter(o => {
    const d = diffDays(o.date, t);
    return opts.all ? true : d === warn || d === 0;
  });
  if(!hit.length) return { sent:0, skipped:0, items:[], msg:'窗口内无待提醒账单' };

  /* 去重：同订阅同扣费日同渠道只推一次 */
  const results = [];
  let sent = 0, skipped = 0;
  for(const o of hit){
    const due = isoD(o.date);
    const chans = [];
    if(cfg.enabled.bark && cfg.bark.url) chans.push('bark');
    if(cfg.enabled.tg && cfg.tg.token && cfg.tg.chat) chans.push('tg');
    if(cfg.enabled.hook && cfg.hook.url) chans.push('hook');
    for(const ch of chans){
      if(!opts.force){
        const dup = await env.DB.prepare(
          'SELECT 1 FROM notify_log WHERE sub_id=? AND due_date=? AND channel=? AND ok=1')
          .bind(o.sub.id, due, ch).first();
        if(dup){ skipped++; continue; }
      }
      const { title, body } = buildMsg([o], cur, rate);
      let r;
      if(ch === 'bark') r = await sendBark(cfg.bark, title, body);
      else if(ch === 'tg') r = await sendTelegram(cfg.tg,
        `<b>${escapeHtml(title)}</b>\n${escapeHtml(body)}`);
      else r = await sendWebhook(cfg.hook, {
        event:'subscription.due', name:o.sub.id, title, body,
        subscription:{ id:o.sub.id, name:o.sub.name, plan:o.sub.plan },
        amount:o.amt, currency:cur, due_date:due,
      });
      if(r.ok) sent++;
      results.push({ sub:o.sub.name, due, channel:ch, ok:r.ok, detail:r.body });
      await env.DB.prepare(
        'INSERT INTO notify_log(user_id,sub_id,due_date,channel,ok,detail,sent_at) VALUES(?,?,?,?,?,?,?) ' +
        'ON CONFLICT(sub_id,due_date,channel) DO UPDATE SET ' +
        'ok=excluded.ok, detail=excluded.detail, sent_at=excluded.sent_at')
        .bind(uid, o.sub.id, due, ch, r.ok ? 1 : 0, r.body || '', Date.now()).run();
    }
  }
  /* 清理本人 90 天前的日志 */
  await env.DB.prepare('DELETE FROM notify_log WHERE user_id=? AND sent_at < ?')
    .bind(uid, Date.now() - 90*864e5).run();
  return { sent, skipped, items:results };
}

/* cron：逐用户执行提醒 */
export async function runNotifyAll(env, opts = {}){
  const users = (await env.DB.prepare('SELECT id FROM users').all()).results || [];
  let sent = 0, skipped = 0;
  for(const u of users){
    try{
      const r = await runNotify(env, u.id, opts);
      sent += r.sent || 0; skipped += r.skipped || 0;
    }catch(e){ /* 单个用户失败不影响其余 */ }
  }
  return { users: users.length, sent, skipped };
}

/* 某用户的设置对象 */
export async function loadUserSettings(env, uid){
  const r = await env.DB.prepare(
    'SELECT key,value FROM user_settings WHERE user_id=?').bind(uid).all();
  const out = {};
  for(const row of (r.results || [])) out[row.key] = row.value;
  return out;
}
/* 全局自动汇率（cron/刷新写入 settings.rate） */
export async function globalRate(env){
  const r = await env.DB.prepare("SELECT value FROM settings WHERE key='rate'").first();
  return r && +r.value > 0 ? +r.value : 7.15;
}
/* 用户生效汇率：手动模式用本人 rate，否则用全局自动值 */
export function effectiveRate(st, gRate){
  if(st.rate_mode === 'manual' && +st.rate > 0) return +st.rate;
  return gRate;
}
export function normalize(row){
  return { ...row, qty:+row.qty || 1, price:+row.price || 0,
           enabled: row.enabled ? 1 : 0, nsfw: row.nsfw ? 1 : 0,
           remind: row.remind ? 1 : 0 };
}
const escapeHtml = s => String(s).replace(/[&<>]/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[m]));
