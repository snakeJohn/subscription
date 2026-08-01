/* main.js — 应用控制器：状态、路由、事件 */

import { $, $$, esc, toast, confirmDlg, closeDialog } from './ui-kit.js';
import { api, AuthError } from './api.js';
import { LIB, CATS } from './catalog.js';
import { renderDash, renderSubs, renderCal, renderLib, fillSelects } from './views.js';
import { openPicker, openForm } from './forms.js';
import { renderSettings } from './settings.js';
import { today, isoD } from '/shared/billing.js';

const S = {
  subs: [], raw: {}, channels: {},
  cur:'CNY', rate:7.15, rateMeta:null, warnDays:7, weekStart:1, theme:'light',
  view:'dash',
  filter:{ q:'', cat:'', cyc:'', nsfw:false, off:false },
  sort:{ key:'due', dir:'asc' },
  calY: today().getFullYear(), calM: today().getMonth(),
  libQ:'', libNsfw:false, libOpen:new Set(['ai']), libOpenAll:false,
};
const VIEWS = { dash:renderDash, subs:renderSubs, cal:renderCal, lib:renderLib };

/* ——— 控制器（传给子模块） ——— */
const ctl = {
  refresh, go, reload, setCur, fetchRate,
  save: saveSettings,
};

/* ════════════ 启动 ════════════ */
boot();
async function boot(){
  /* 本地偏好先应用，避免主题闪烁 */
  try{
    const p = JSON.parse(localStorage.getItem('substat.pref') || '{}');
    if(p.theme) S.theme = p.theme;
    if(p.cur) S.cur = p.cur;
  }catch(e){}
  document.documentElement.dataset.theme = S.theme;

  let st;
  try{ st = await api.status(); }
  catch(e){
    $('#boot').innerHTML = `<i style="color:var(--bad)">无法连接服务端<br>
      <span style="font-size:13px">${esc(e.message)}</span></i>`;
    return;
  }
  if(!st.configured){
    $('#boot').innerHTML = `<div style="text-align:center;max-width:400px;padding:20px">
      <i>尚未设置访问密码</i>
      <p style="font-size:13px;color:var(--ink-3);margin-top:14px;line-height:1.7">
        请执行 <code style="font-family:var(--mono)">wrangler secret put AUTH_PASSWORD</code>
        设置密码后刷新页面。</p></div>`;
    return;
  }
  if(!st.authed){ showLogin(); return; }
  await enterApp();
}

function showLogin(msg){
  $('#boot').classList.add('gone');
  $('#login').classList.remove('hide');
  $('#login-tip').textContent = '数据存储于你的 Cloudflare D1，仅本人可访问。';
  if(msg) $('#login-e').textContent = msg;
  const f = $('#login-f');
  f.onsubmit = async e => {
    e.preventDefault();
    const b = $('#login-b');
    b.disabled = true;
    $('#login-e').textContent = '';
    try{
      await api.login($('#login-p').value);
      $('#login').classList.add('hide');
      $('#boot').classList.remove('gone');
      await enterApp();
    }catch(err){
      $('#login-e').textContent = err.message;
      $('#login-p').select();
    }finally{ b.disabled = false; }
  };
  setTimeout(() => $('#login-p').focus(), 60);
}

async function enterApp(){
  fillSelects();
  bind();
  await loadAll();
  $('#app').classList.remove('hide');
  $('#boot').classList.add('gone');
  setTimeout(() => $('#boot').remove(), 400);
}

async function loadAll(){
  const [subs, set] = await Promise.all([api.list(), api.settings()]);
  S.subs = subs.items;
  applySettings(set);
  /* 汇率：自动模式且缓存较旧时后台刷新，不阻塞首屏 */
  if(S.raw.rate_mode !== 'manual') fetchRate(false).catch(() => {});
  refresh();
}
function applySettings(set){
  const st = set.settings || {};
  S.raw = st;
  S.channels = set.channels || {};
  if(+st.rate > 0) S.rate = +st.rate;
  if(st.cur === 'USD' || st.cur === 'CNY') S.cur = st.cur;
  if(+st.warn > 0) S.warnDays = Math.min(90, +st.warn);
  if(st.week === '0' || st.week === '1') S.weekStart = +st.week;
  if(st.nsfw === '1'){ S.filter.nsfw = true; S.libNsfw = true; }
  if(st.theme === 'dark' || st.theme === 'light'){
    S.theme = st.theme;
    document.documentElement.dataset.theme = S.theme;
  }
  if(st.lib_collapsed){
    try{ S.libOpen = new Set(JSON.parse(st.lib_collapsed)); }catch(e){}
  }
  syncControls();
}
function syncControls(){
  $('#m-rate').textContent = S.rate.toFixed(4).replace(/0+$/,'').replace(/\.$/,'');
  $('#m-date').textContent = isoD(today());
  $$('#cur-sw button').forEach(b =>
    b.setAttribute('aria-pressed', String(b.dataset.c === S.cur)));
  $('#f-nsfw').checked = S.filter.nsfw;
  $('#l-nsfw').checked = S.libNsfw;
  $('#f-off').checked = S.filter.off;
  $('#n-subs').textContent = S.subs.length ? ` ${S.subs.length}` : '';
  $('#n-lib').textContent = ` ${LIB.length}`;
  const m = S.rateMeta;
  $('#m-rate-src').textContent = m && m.source ? m.source.slice(0,4) : '';
}

/* ════════════ 渲染 ════════════ */
function refresh(){
  syncControls();
  $$('.view').forEach(v => v.classList.toggle('on', v.id === 'v-' + S.view));
  $$('.nav [data-v]').forEach(b =>
    b.setAttribute('aria-selected', String(b.dataset.v === S.view)));
  if(S.view === 'set') renderSettings(S, ctl);
  else VIEWS[S.view](S);
}
function go(v){ S.view = v; refresh(); }
async function reload(){
  const r = await api.list();
  S.subs = r.items;
  refresh();
}

/* ════════════ 状态变更 ════════════ */
function pref(){
  try{ localStorage.setItem('substat.pref',
    JSON.stringify({ theme:S.theme, cur:S.cur })); }catch(e){}
}
function setCur(c){
  S.cur = c;
  pref();
  saveSettings({ cur:c });
  refresh();
}
async function saveSettings(patch, after){
  try{
    await api.saveSettings(patch);
    Object.assign(S.raw, patch);
    if(after) after();
  }catch(e){ guard(e, () => toast('保存失败：' + e.message, true)); }
}
async function fetchRate(force){
  const r = await api.rate(force);
  S.rateMeta = r;
  if(r.rate > 0){ S.rate = r.rate; refresh(); }
  return r;
}
/* 401 统一处理：会话过期回登录页 */
function guard(e, fallback){
  if(e instanceof AuthError){
    $('#app').classList.add('hide');
    closeDialog();
    showLogin('会话已过期，请重新登录');
    return;
  }
  if(fallback) fallback(); else toast(e.message, true);
}

/* ════════════ 事件 ════════════ */
function bind(){
  $$('.nav [data-v]').forEach(b => b.onclick = () => go(b.dataset.v));
  $('#cur-sw').onclick = e => {
    const b = e.target.closest('[data-c]');
    if(b) setCur(b.dataset.c);
  };
  let themeT;
  $('#b-theme').onclick = () => {
    S.theme = S.theme === 'dark' ? 'light' : 'dark';
    /* 挂上 .theming 让全局色彩渐变过渡（见 base.css），结束后移除 */
    const root = document.documentElement;
    root.classList.add('theming');
    root.dataset.theme = S.theme;
    clearTimeout(themeT);
    themeT = setTimeout(() => root.classList.remove('theming'), 520);
    pref();
    saveSettings({ theme:S.theme });
  };
  $('#b-add').onclick = () => picker();
  $('#m-rate-btn').onclick = async () => {
    const chip = $('#rate-chip');
    chip.classList.add('spin');
    try{
      const r = await fetchRate(true);
      toast(r.rate ? `汇率已更新 ${r.rate}（${r.source||'缓存'}）` : '获取失败，仍用当前汇率',
        !r.rate);
    }catch(e){ guard(e); }
    finally{ chip.classList.remove('spin'); }
  };

  /* 筛选 */
  const fq = $('#f-q');
  let qt;
  fq.oninput = () => {
    clearTimeout(qt);
    qt = setTimeout(() => { S.filter.q = fq.value; renderSubs(S); }, 140);
  };
  $('#f-cat').onchange = e => { S.filter.cat = e.target.value; renderSubs(S); };
  $('#f-cyc').onchange = e => { S.filter.cyc = e.target.value; renderSubs(S); };
  $('#f-off').onchange = e => { S.filter.off = e.target.checked; renderSubs(S); };
  $('#f-nsfw').onchange = e => {
    S.filter.nsfw = e.target.checked;
    S.libNsfw = e.target.checked;
    $('#l-nsfw').checked = e.target.checked;
    saveSettings({ nsfw: e.target.checked ? '1' : '0' });
    renderSubs(S);
  };

  /* 表格：排序 + 行操作 */
  $('#subs-wrap').onclick = async e => {
    const th = e.target.closest('[data-sort]');
    if(th){
      const k = th.dataset.sort;
      if(S.sort.key === k) S.sort.dir = S.sort.dir === 'asc' ? 'desc' : 'asc';
      else S.sort = { key:k, dir: k === 'name' || k === 'cat' ? 'asc' : 'desc' };
      return renderSubs(S);
    }
    if(e.target.closest('[data-act=add]')) return picker();
    const btn = e.target.closest('[data-act]');
    if(!btn) return;
    const tr = btn.closest('tr[data-id]');
    if(!tr) return;
    const sub = S.subs.find(x => x.id === tr.dataset.id);
    if(!sub) return;
    const act = btn.dataset.act;
    if(act === 'edit') return edit(sub);
    if(act === 'open') return window.open('https://' + sub.domain, '_blank', 'noopener');
    if(act === 'toggle'){
      try{
        await api.patch(sub.id, { enabled: sub.enabled ? 0 : 1 });
        sub.enabled = sub.enabled ? 0 : 1;
        toast(sub.enabled ? '已启用' : '已停用');
        refresh();
      }catch(err){ guard(err); }
      return;
    }
    if(act === 'del') return del(sub);
  };

  /* 看板 / 日历里点条目直接编辑 */
  const jump = e => {
    const el = e.target.closest('[data-edit]');
    if(!el) return;
    const sub = S.subs.find(x => x.id === el.dataset.edit);
    if(sub) edit(sub);
  };
  $('#v-dash').onclick = jump;
  $('#c-grid').onclick = jump;

  /* 日历翻页 */
  $('#c-prev').onclick = () => { if(--S.calM < 0){ S.calM = 11; S.calY--; } renderCal(S); };
  $('#c-next').onclick = () => { if(++S.calM > 11){ S.calM = 0; S.calY++; } renderCal(S); };
  $('#c-today').onclick = () => {
    S.calY = today().getFullYear(); S.calM = today().getMonth(); renderCal(S);
  };

  /* 服务库 */
  const lq = $('#l-q');
  let lt;
  lq.oninput = () => {
    clearTimeout(lt);
    lt = setTimeout(() => { S.libQ = lq.value; renderLib(S); }, 140);
  };
  $('#l-nsfw').onchange = e => {
    S.libNsfw = e.target.checked;
    S.filter.nsfw = e.target.checked;
    $('#f-nsfw').checked = e.target.checked;
    saveSettings({ nsfw: e.target.checked ? '1' : '0' });
    renderLib(S);
  };
  $('#l-open').onclick  = () => { S.libOpenAll = true;  renderLib(S); persistOpen(); };
  $('#l-close').onclick = () => {
    S.libOpenAll = false; S.libOpen.clear(); renderLib(S); persistOpen();
  };
  $('#l-nav').onclick = e => {
    const n = e.target.closest('[data-nsfw-on]');
    if(n){
      S.libNsfw = true;
      $('#l-nsfw').checked = true;
      S.filter.nsfw = true;
      $('#f-nsfw').checked = true;
      saveSettings({ nsfw:'1' });
      renderLib(S);
      setTimeout(() => {
        const d = $('details[data-cat=nsfw]');
        if(d){ d.open = true; d.scrollIntoView({ behavior:'smooth', block:'start' }); }
      }, 40);
      return;
    }
    const b = e.target.closest('[data-jump]');
    if(!b) return;
    const d = $(`details[data-cat="${b.dataset.jump}"]`);
    if(d){
      d.open = true;
      S.libOpen.add(b.dataset.jump);
      persistOpen();
      d.scrollIntoView({ behavior:'smooth', block:'start' });
    }
  };
  /* 折叠状态记忆 */
  $('#l-body').addEventListener('toggle', e => {
    const d = e.target.closest('details[data-cat]');
    if(!d) return;
    const k = d.dataset.cat;
    if(d.open) S.libOpen.add(k); else S.libOpen.delete(k);
    S.libOpenAll = false;
    persistOpen();
  }, true);
  $('#l-body').onclick = e => {
    if(e.target.closest('[data-act=add]')) return picker();
    const b = e.target.closest('[data-lib]');
    if(b) form(null, LIB[+b.dataset.lib]);
  };

  /* 快捷键 */
  document.addEventListener('keydown', e => {
    if(e.target.matches('input,textarea,select')) return;
    if((e.metaKey || e.ctrlKey) && e.key === 'n'){ e.preventDefault(); return picker(); }
    if(e.key === '/'){ e.preventDefault(); go('subs'); setTimeout(() => $('#f-q').focus(), 40); }
    const map = { 1:'dash', 2:'subs', 3:'cal', 4:'lib', 5:'set' };
    if(map[e.key]) go(map[e.key]);
  });
}
let openTimer;
function persistOpen(){
  clearTimeout(openTimer);
  openTimer = setTimeout(() =>
    saveSettings({ lib_collapsed: JSON.stringify([...S.libOpen]) }), 500);
}

/* ——— 增删改 ——— */
function picker(){ openPicker(S, item => { closeDialog(); form(null, item); }); }
function edit(sub){ form(sub, null); }
function form(sub, item){
  openForm(S, {
    sub, item,
    onSave: async (data, id) => {
      try{
        if(id) await api.update(id, data);
        else   await api.create(data);
        closeDialog();
        toast(id ? '已保存修改' : `已添加「${data.name}」`);
        await reload();
        if(!id && S.view === 'lib') go('subs');
      }catch(e){ guard(e, () => toast(e.message, true)); }
    },
    onDelete: s => del(s, true),
  });
}
async function del(sub, fromForm){
  if(!await confirmDlg(`确认删除「${sub.name}」？此操作不可撤销。`, '删除')) return;
  try{
    await api.remove(sub.id);
    if(fromForm) closeDialog();
    toast('已删除');
    await reload();
  }catch(e){ guard(e); }
}
