/* forms.js — 添加/编辑表单、服务选择器 */

import { $, $$, esc, icon, dialog, closeDialog, toast } from './ui-kit.js';
import { CATS, LIB, firstPlan, libIndex } from './catalog.js';
import { CYCLES, CYC_KEYS, monthly, yearly, daily, amtIn, isoD, today, conv, fmt }
  from '/shared/billing.js';

/* ——— 服务选择器 ——— */
export function openPicker(S, onPick){
  dialog(`<div class="dlg-h"><h3>选择服务</h3><button class="x" data-close>×</button></div>
    <div class="pk-s"><input class="in" id="pk-q" placeholder="搜索服务名称或域名…" autofocus></div>
    <div class="pk-l" id="pk-l"></div>
    <div class="dlg-f"><button class="btn" data-close>取消</button>
      <button class="btn pri" id="pk-manual">＋ 手动添加自定义订阅</button></div>`);
  const draw = () => {
    const q = $('#pk-q').value.trim().toLowerCase();
    let list = LIB.filter(x => {
      if(!S.libNsfw && x.nsfw) return false;
      return !q || `${x.name} ${x.domain||''}`.toLowerCase().includes(q);
    });
    const el = $('#pk-l');
    if(!list.length){
      el.innerHTML = `<div class="mini-empty">没有匹配结果${
        S.libNsfw ? '' : '<br>（成人内容已隐藏，可在服务库中开启）'}</div>`;
      return;
    }
    const shown = list.slice(0, 80);
    el.innerHTML = (q ? '' : `<div class="pk-h">共 ${list.length} 项，输入关键词可筛选</div>`) +
      shown.map(x => {
        const p = firstPlan(x);
        const pr = p ? (p.cycle === 'once'
          ? `${fmt(conv(p.price,p.cur,S.cur,S.rate), S.cur)} 起`
          : `${fmt(conv(p.price,p.cur,S.cur,S.rate), S.cur)}/${CYCLES[p.cycle].short}`) : '';
        return `<button class="pk-i" data-lib="${libIndex(x)}">${icon(x)}
          <div class="bd"><b>${esc(x.name)}${x.nsfw?' <span class="tg nsfw">NSFW</span>':''}</b>
            <span>${esc((CATS[x.cat]||{}).name||'')} · ${esc(x.domain||'')} · ${x.plans.length} 方案</span>
          </div><span class="pr">${esc(pr)}</span></button>`;
      }).join('') +
      (list.length > shown.length
        ? `<div class="pk-h">仅显示前 ${shown.length} 项，继续输入以缩小范围</div>` : '');
  };
  $('#pk-q').oninput = draw;
  draw();
  $('#pk-l').onclick = e => {
    const b = e.target.closest('[data-lib]');
    if(!b) return;
    onPick(LIB[+b.dataset.lib]);
  };
  $('#pk-manual').onclick = () => onPick(null);
}

/* ——— 表单 ——— */
/* sub: 已有记录（编辑）；item: 服务库条目（新增预填） */
export function openForm(S, { sub, item, onSave, onDelete }){
  const p = item ? (firstPlan(item) || {}) : {};
  const base = sub || (item ? {
    name:item.name, domain:item.domain, cat:item.cat, nsfw:item.nsfw?1:0,
    plan:p.name||'', price:p.price ?? '', cur:p.cur||'CNY', cycle:p.cycle||'month',
    qty:1, start:isoD(today()), note:'', enabled:1, remind:1,
  } : {
    name:'', domain:'', cat:'ai', nsfw:0, plan:'', price:'', cur:S.cur,
    cycle:'month', qty:1, start:isoD(today()), note:'', enabled:1, remind:1,
  });
  const plans = item ? item.plans : null;
  const isEdit = !!sub;

  dialog(`<div class="dlg-h">${icon(base,'lg')}
      <h3>${isEdit ? '编辑订阅' : (item ? esc(item.name) : '添加自定义订阅')}</h3>
      <button class="x" data-close>×</button></div>
    <div class="dlg-b"><div class="fgrid">
      ${plans ? `<div class="fld full"><span>选择方案</span><div class="plans" id="f-plans">
        ${plans.map(x => `<button class="plan" data-p='${esc(JSON.stringify(x))}'
            aria-pressed="${x[0]===base.plan}">
            <span class="nm">${esc(x[0])}</span>
            <span class="vl">${esc((x[2]==='USD'?'$':'¥') + x[1])}</span>
            <span class="un">/${esc(CYCLES[x[3]].short)}</span></button>`).join('')}
        </div></div>` : ''}
      <label class="fld full"><span>服务名称 *</span>
        <input class="in" id="x-name" value="${esc(base.name)}" placeholder="例如 Netflix" ${
          isEdit||item?'':'autofocus'}></label>
      <label class="fld"><span>官网域名</span>
        <input class="in" id="x-dom" value="${esc(base.domain||'')}" placeholder="netflix.com"></label>
      <label class="fld"><span>分类</span><select class="sel" id="x-cat">
        ${Object.entries(CATS).map(([k,v]) =>
          `<option value="${k}"${k===base.cat?' selected':''}>${esc(v.name)}</option>`).join('')}
      </select></label>
      <div class="fld full"><span>计费周期</span><div class="pick" id="x-cyc">
        ${CYC_KEYS.map(k => `<button data-c="${k}" aria-pressed="${k===base.cycle}">
          ${esc(CYCLES[k].name)}</button>`).join('')}</div></div>
      <label class="fld"><span>单价 *</span>
        <input class="in" id="x-price" type="number" step="0.01" min="0"
          value="${base.price}" placeholder="0.00"></label>
      <div class="fld"><span>币种</span><div class="pick c2" id="x-cur">
        <button data-c="CNY" aria-pressed="${base.cur!=='USD'}">¥ 人民币</button>
        <button data-c="USD" aria-pressed="${base.cur==='USD'}">$ 美元</button></div></div>
      <label class="fld"><span>份数 / 数量</span>
        <input class="in" id="x-qty" type="number" min="1" step="1" value="${+base.qty||1}"></label>
      <label class="fld"><span id="x-start-l">首次付费日 *</span>
        <input class="in" id="x-start" type="date" value="${esc(base.start||'')}"></label>
      <label class="fld full"><span>方案 / 档位名称</span>
        <input class="in" id="x-plan" value="${esc(base.plan||'')}" placeholder="例如 高级版 4K"></label>
      <label class="fld full"><span>备注</span>
        <textarea class="ta" id="x-note" placeholder="账号、合租人、续费方式…">${esc(base.note||'')}</textarea></label>
      <div class="full" style="display:flex;gap:18px;flex-wrap:wrap">
        <label class="chk"><input type="checkbox" id="x-nsfw" ${base.nsfw?'checked':''}>
          <span>标记为 NSFW（列表默认隐藏）</span></label>
        <label class="chk"><input type="checkbox" id="x-remind" ${base.remind?'checked':''}>
          <span>参与到期提醒</span></label>
      </div>
      <div class="calc" id="x-calc"></div>
    </div></div>
    <div class="dlg-f">
      ${isEdit ? `<button class="btn dgr" id="x-del">删除</button>` : ''}
      <button class="btn" data-close>取消</button>
      <button class="btn pri" id="x-ok">${isEdit?'保存修改':'添加订阅'}</button></div>`,
    { wide: !!plans });

  const cyc = () => { const b = $('#x-cyc [aria-pressed=true]'); return b ? b.dataset.c : 'month'; };
  const curr = () => { const b = $('#x-cur [aria-pressed=true]'); return b ? b.dataset.c : 'CNY'; };
  const setPressed = (sel, c) =>
    $$(sel + ' button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.c === c)));

  const calc = () => {
    const tmp = { price:+$('#x-price').value||0, qty:+$('#x-qty').value||1,
                  cur:curr(), cycle:cyc() };
    const el = $('#x-calc');
    const oc = S.cur === 'CNY' ? 'USD' : 'CNY';
    if(tmp.cycle === 'once'){
      el.innerHTML =
        `<div><span>一次性支出</span><b>${esc(fmt(amtIn(tmp,S.cur,S.rate), S.cur))}</b></div>
         <div><span>另一币种</span><b>${esc(fmt(amtIn(tmp,oc,S.rate), oc))}</b></div>
         <div><span>说明</span><b class="sm">不计入周期支出</b></div>`;
      return;
    }
    el.innerHTML =
      `<div><span>折算月均</span><b>${esc(fmt(monthly(tmp,S.cur,S.rate), S.cur))}</b></div>
       <div><span>折算年均</span><b>${esc(fmt(yearly(tmp,S.cur,S.rate), S.cur))}</b></div>
       <div><span>折算日均</span><b>${esc(fmt(daily(tmp,S.cur,S.rate), S.cur))}</b></div>`;
  };
  $('#x-cyc').onclick = e => {
    const b = e.target.closest('[data-c]'); if(!b) return;
    setPressed('#x-cyc', b.dataset.c);
    $('#x-start-l').textContent = b.dataset.c === 'once' ? '付费日期 *' : '首次付费日 *';
    calc();
  };
  $('#x-cur').onclick = e => {
    const b = e.target.closest('[data-c]'); if(!b) return;
    setPressed('#x-cur', b.dataset.c); calc();
  };
  if(plans) $('#f-plans').onclick = e => {
    const b = e.target.closest('.plan'); if(!b) return;
    const p = JSON.parse(b.dataset.p);
    $$('#f-plans .plan').forEach(x => x.setAttribute('aria-pressed','false'));
    b.setAttribute('aria-pressed','true');
    $('#x-plan').value = p[0];
    $('#x-price').value = p[1];
    setPressed('#x-cur', p[2]);
    setPressed('#x-cyc', p[3]);
    $('#x-start-l').textContent = p[3] === 'once' ? '付费日期 *' : '首次付费日 *';
    calc();
  };
  $('#x-price').oninput = calc;
  $('#x-qty').oninput = calc;
  calc();

  $('#x-ok').onclick = async () => {
    const name = $('#x-name').value.trim();
    const price = parseFloat($('#x-price').value);
    const start = $('#x-start').value;
    if(!name)  return toast('请填写服务名称', true);
    if(!isFinite(price) || price < 0) return toast('请填写有效的单价', true);
    if(!start) return toast('请选择付费日期', true);
    const data = {
      name, domain: $('#x-dom').value.trim(), cat: $('#x-cat').value,
      plan: $('#x-plan').value.trim(), price, cur: curr(), cycle: cyc(),
      qty: Math.max(1, +$('#x-qty').value||1), start,
      note: $('#x-note').value.trim(),
      nsfw: $('#x-nsfw').checked ? 1 : 0,
      remind: $('#x-remind').checked ? 1 : 0,
      enabled: base.enabled === 0 ? 0 : 1,
    };
    const btn = $('#x-ok');
    btn.disabled = true;
    try{ await onSave(data, sub && sub.id); }
    finally{ btn.disabled = false; }
  };
  if(isEdit) $('#x-del').onclick = () => onDelete(sub);
}
