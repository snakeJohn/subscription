/* webdav.js — WebDAV 云备份：把订阅数据 PUT 为目录下的 substat-backup.json，可拉回恢复。
 * 所有请求由 Worker 代理发出，浏览器不直连（多数 WebDAV 服务不带 CORS 头）。 */

const FILE = 'substat-backup.json';

/* Basic 认证：凭据可能含非 ASCII，先转 UTF-8 字节再 base64 */
const b64 = s => btoa(String.fromCharCode(...new TextEncoder().encode(s)));

export function webdavConfig(st){
  return {
    url:  String(st.webdav_url  || '').trim().replace(/\/+$/, ''),
    user: String(st.webdav_user || '').trim(),
    pass: String(st.webdav_pass || ''),
    auto: st.webdav_auto === '1',
  };
}

const davHeaders = (cfg, extra) =>
  ({ Authorization: 'Basic ' + b64(cfg.user + ':' + cfg.pass), ...extra });
const okUrl = u => /^https?:\/\//i.test(u);

/* PROPFIND Depth:0 探测目录是否存在且凭据有效 */
export async function davTest(cfg){
  if(!okUrl(cfg.url)) return { ok:false, detail:'地址需以 http(s):// 开头' };
  try{
    const r = await fetch(cfg.url, { method:'PROPFIND', headers: davHeaders(cfg, { Depth:'0' }) });
    if(r.status === 207 || r.status === 200) return { ok:true, status:r.status };
    if(r.status === 401) return { ok:false, status:401, detail:'认证失败，检查账号或应用密码' };
    if(r.status === 404) return { ok:false, status:404, detail:'目录不存在，请先在网盘里创建' };
    return { ok:false, status:r.status, detail:`服务器返回 ${r.status}` };
  }catch(e){ return { ok:false, detail:String(e && e.message || e) }; }
}

/* 上传备份；目录不存在时 MKCOL 一次后重试 */
export async function davBackup(cfg, payload){
  if(!okUrl(cfg.url)) return { ok:false, detail:'未配置有效的 WebDAV 地址' };
  const put = () => fetch(cfg.url + '/' + FILE, { method:'PUT',
    headers: davHeaders(cfg, { 'Content-Type':'application/json' }), body: payload });
  try{
    let r = await put();
    if(r.status === 404 || r.status === 409){
      await fetch(cfg.url, { method:'MKCOL', headers: davHeaders(cfg) });
      r = await put();
    }
    if(r.status === 401) return { ok:false, status:401, detail:'认证失败，检查账号或应用密码' };
    if(r.ok) return { ok:true, status:r.status, name:FILE, size:payload.length };
    return { ok:false, status:r.status, detail:`上传失败，服务器返回 ${r.status}` };
  }catch(e){ return { ok:false, detail:String(e && e.message || e) }; }
}

/* 拉取云端备份，返回解析后的 JSON */
export async function davGet(cfg){
  if(!okUrl(cfg.url)) return { ok:false, detail:'未配置有效的 WebDAV 地址' };
  try{
    const r = await fetch(cfg.url + '/' + FILE, { headers: davHeaders(cfg) });
    if(r.status === 404) return { ok:false, status:404, detail:'云端还没有备份文件' };
    if(r.status === 401) return { ok:false, status:401, detail:'认证失败，检查账号或应用密码' };
    if(!r.ok) return { ok:false, status:r.status, detail:`服务器返回 ${r.status}` };
    let data;
    try{ data = JSON.parse(await r.text()); }
    catch(e){ return { ok:false, detail:'备份文件不是有效 JSON' }; }
    return { ok:true, data };
  }catch(e){ return { ok:false, detail:String(e && e.message || e) }; }
}
