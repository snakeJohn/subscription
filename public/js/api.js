/* api.js — 后端接口封装
 * 401 统一抛 AuthError，由 main.js 切回登录页。 */

export class AuthError extends Error {
  constructor(){ super('未登录'); this.name = 'AuthError'; }
}
export class ApiError extends Error {
  constructor(msg, status){ super(msg); this.name = 'ApiError'; this.status = status; }
}

async function req(path, opts = {}){
  const r = await fetch('/api' + path, {
    credentials:'same-origin',
    headers: opts.body ? { 'Content-Type':'application/json' } : {},
    ...opts,
  });
  if(r.status === 401) throw new AuthError();
  let data = null;
  const txt = await r.text();
  if(txt){
    try{ data = JSON.parse(txt); }
    catch(e){ throw new ApiError('响应格式错误', r.status); }
  }
  if(!r.ok) throw new ApiError((data && data.error) || `请求失败 (${r.status})`, r.status);
  return data;
}
const body = d => JSON.stringify(d);

export const api = {
  /* 鉴权 */
  status:   ()  => req('/auth/status'),
  login:    p   => req('/auth/login',  { method:'POST', body: body({ password:p }) }),
  logout:   ()  => req('/auth/logout', { method:'POST' }),
  setPassword: (current, password) =>
    req('/auth/password', { method:'POST', body: body({ current, password }) }),

  /* 订阅 */
  list:     ()  => req('/subscriptions'),
  create:   d   => req('/subscriptions', { method:'POST', body: body(d) }),
  update:   (id,d) => req('/subscriptions/' + id, { method:'PUT', body: body(d) }),
  patch:    (id,d) => req('/subscriptions/' + id, { method:'PATCH', body: body(d) }),
  remove:   id  => req('/subscriptions/' + id, { method:'DELETE' }),
  clearAll: ()  => req('/subscriptions', { method:'DELETE' }),
  bulk:     (items, mode) =>
    req('/subscriptions/bulk', { method:'POST', body: body({ items, mode }) }),

  /* 设置 / 汇率 / 提醒 */
  settings:    ()  => req('/settings'),
  saveSettings: d  => req('/settings', { method:'PUT', body: body(d) }),
  rate:      force => req('/rate' + (force ? '?refresh=1' : '')),
  notifyTest: ch   => req('/notify/test', { method:'POST', body: body({ channel:ch }) }),
  notifyRun:  o    => req('/notify/run',  { method:'POST', body: body(o || {}) }),
  notifyLog:  ()   => req('/notify/log'),
};
