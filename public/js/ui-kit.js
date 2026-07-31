/* ui-kit.js — DOM 助手、图标加载、toast */

export const $  = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];
export const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
  m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));

/* ——— toast ——— */
let tTimer;
export function toast(msg, isErr){
  const el = $('#toast');
  el.textContent = msg;
  el.classList.toggle('err', !!isErr);
  el.classList.add('on');
  clearTimeout(tTimer);
  tTimer = setTimeout(() => el.classList.remove('on'), isErr ? 3600 : 2200);
}

/* ——— 图标 ———
 * 先出字母块（永不空白、无布局抖动），再异步探测 favicon 成功后替换。
 * 关键：不能依赖 <img onerror>——请求被墙时是「挂起」而非报错，
 * onerror 永不触发，会空白到浏览器 ~30s 超时。必须自己超时推进。 */
const CACHE = new Map();          // domain -> url | null
const probing = new Set();
const pending = [];              // {id, dom, cls}
const TIMEOUT = 2600;
let seq = 0;

const sources = d => [
  `https://${d}/favicon.ico`,
  `https://www.google.com/s2/favicons?domain=${encodeURIComponent(d)}&sz=64`,
  `https://icons.duckduckgo.com/ip3/${encodeURIComponent(d)}.ico`,
];

export function strColor(s){
  let h = 0;
  for(let i=0;i<s.length;i++) h = (h*31 + s.charCodeAt(i)) % 360;
  return `hsl(${h} 42% 42%)`;
}

export function icon(item, cls = ''){
  const c = ('ico ' + cls).trim();
  const name = item.name || item.domain || '?';
  const ch = (String(name).trim()[0] || '?').toUpperCase();
  const dom = item.domain;
  if(dom && CACHE.get(dom))
    return `<img class="${c}" alt="" loading="lazy" src="${esc(CACHE.get(dom))}">`;
  const id = 'i' + (++seq);
  if(dom){
    if(!CACHE.has(dom) && !probing.has(dom)) probe(dom);
    if(pending.length > 500) prune();
    pending.push({ id, dom, cls:c });
  }
  return `<div class="${c} txt" id="${id}" style="background:${strColor(name)}">${esc(ch)}</div>`;
}
function prune(){
  for(let i = pending.length - 1; i >= 0; i--)
    if(!document.getElementById(pending[i].id)) pending.splice(i, 1);
}
function probe(dom){
  probing.add(dom);
  step(dom, sources(dom), 0);
}
function step(dom, srcs, i){
  if(i >= srcs.length){ CACHE.set(dom, null); probing.delete(dom); return; }
  const img = new Image();
  let done = false;
  const finish = ok => {
    if(done) return;
    done = true;
    clearTimeout(tm);
    img.onload = img.onerror = null;
    if(ok && img.naturalWidth > 0){
      CACHE.set(dom, srcs[i]);
      probing.delete(dom);
      apply(dom, srcs[i]);
    }else step(dom, srcs, i + 1);
  };
  const tm = setTimeout(() => finish(false), TIMEOUT);
  img.onload = () => finish(true);
  img.onerror = () => finish(false);
  img.src = srcs[i];
}
function apply(dom, url){
  for(let i = pending.length - 1; i >= 0; i--){
    const p = pending[i];
    if(p.dom !== dom) continue;
    pending.splice(i, 1);
    const el = document.getElementById(p.id);
    if(!el) continue;
    const img = document.createElement('img');
    img.className = p.cls;
    img.alt = '';
    img.loading = 'lazy';
    img.src = url;
    img.onerror = () => img.replaceWith(el);   /* 二次失败退回字母块 */
    el.replaceWith(img);
  }
}

/* ——— 弹窗 ——— */
let onEsc = null;
export function dialog(html, opts = {}){
  const wrap = $('#modal');
  wrap.innerHTML = `<div class="mask"><div class="dlg ${opts.wide?'wide':''}">${html}</div></div>`;
  const close = () => closeDialog();
  $('.mask', wrap).addEventListener('click', e => {
    if(e.target.classList.contains('mask')) close();
  });
  $$('[data-close]', wrap).forEach(b => b.onclick = close);
  onEsc = close;
  const first = $('[autofocus]', wrap) || $('input,select,textarea,button', wrap);
  if(first) setTimeout(() => first.focus(), 30);
  return wrap;
}
export function closeDialog(){
  $('#modal').innerHTML = '';
  onEsc = null;
}
document.addEventListener('keydown', e => {
  if(e.key === 'Escape' && onEsc) onEsc();
});

export const confirmDlg = (msg, okText = '确认') => new Promise(res => {
  dialog(`<div class="dlg-h"><h3>请确认</h3>
      <button class="x" data-close>×</button></div>
    <div class="dlg-b"><p style="font-size:13.5px;line-height:1.7">${esc(msg)}</p></div>
    <div class="dlg-f"><button class="btn" data-close>取消</button>
      <button class="btn dgr" id="cf-ok" style="flex:1;justify-content:center">${esc(okText)}</button></div>`);
  $('#cf-ok').onclick = () => { closeDialog(); res(true); };
  const m = $('.mask');
  m.addEventListener('click', e => { if(e.target === m) res(false); });
  $$('[data-close]').forEach(b => b.addEventListener('click', () => res(false)));
});
