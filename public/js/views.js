/* views.js — 各视图渲染（纯函数，接收 state 返回 HTML 或直接写入 DOM） */

import { $, $$, esc, icon } from './ui-kit.js';
import { CATS, LIB, firstPlan, libIndex } from './catalog.js';
import { CYCLES, CYC_KEYS, totals, yearly, monthly, amtIn, nextDue, daysLeft,
         progress, occurrences, monthOccurrences, isoD, today, diffDays,
         fmt, fmtK, fmt2, conv } from '/shared/billing.js';

const PAL = ['var(--c1)','var(--c2)','var(--c3)','var(--c4)','var(--c5)','var(--c6)',
             'var(--c7)','var(--c8)','var(--c9)','var(--c10)','var(--c11)','var(--c12)'];
const miniEmpty = t => `<div class="mini-empty">${esc(t)}</div>`;

/* ════════════ 总览 ════════════ */
export function renderDash(S){
  const { subs, cur, rate } = S;
  const other = cur === 'CNY' ? 'USD' : 'CNY';
  const t = totals(subs, cur, rate), to = totals(subs, other, rate);
  const warn = S.warnDays;

  $('#h-mon').innerHTML = `${esc(fmt(t.month, cur))}<small> /月</small>`;
  $('#h-sub').innerHTML =
    `<span>约 <b>${esc(fmt(to.month, other))}</b></span>
     <span>生效 <b>${t.count}</b> 项${t.all > t.count ? `（共 ${t.all}）` : ''}</span>`;
  $('#h-year').textContent = fmt(t.year, cur);
  $('#h-day').textContent  = fmt(t.day, cur);
  $('#h-once').textContent = fmt(t.once, cur);

  const soon = occurrences(subs, warn, cur, rate);
  const soonSum = soon.reduce((s,o) => s + o.amt, 0);
  const hDue = $('#h-due');
  hDue.textContent = fmt(soonSum, cur);
  hDue.className = soon.length ? (soonSum > 0 ? 'red' : '') : '';
  $('#h-due2').textContent = `${warn} 天内 ${soon.length} 笔`;

  /* 分类结构：年度等效 + 一次性并入 */
  const by = {};
  for(const s of subs){
    if(!s.enabled) continue;
    const v = s.cycle === 'once' ? amtIn(s, cur, rate) : yearly(s, cur, rate);
    if(v > 0) by[s.cat] = (by[s.cat] || 0) + v;
  }
  const rows = Object.entries(by)
    .map(([k,v]) => ({ name:(CATS[k]||{name:k}).name, val:v }))
    .sort((a,b) => b.val - a.val);
  const sum = rows.reduce((s,r) => s + r.val, 0);
  $('#d-share').innerHTML = rows.length ? `<div class="share">` + rows.map((r,i) => {
    const pct = sum ? r.val / sum * 100 : 0;
    return `<div class="sh-i">
      <div class="nm"><i style="background:${PAL[i % PAL.length]}"></i>${esc(r.name)}</div>
      <div class="vl">${esc(fmt(r.val, cur))}<em>${pct.toFixed(1)}%</em></div>
      <div class="tr"><i style="width:${pct}%;background:${PAL[i % PAL.length]}"></i></div>
    </div>`;
  }).join('') + `</div>` : miniEmpty('暂无支出数据');

  /* 未来 12 个月现金流 */
  const occ = occurrences(subs, 366, cur, rate);
  const t0 = today();
  const buckets = [];
  for(let i=0;i<12;i++){
    const d = new Date(t0.getFullYear(), t0.getMonth()+i, 1);
    buckets.push({ y:d.getFullYear(), m:d.getMonth(), val:0, now:i===0,
      label:`${d.getFullYear()} 年 ${d.getMonth()+1} 月`,
      short:(i===0 || d.getMonth()===0) ? `${d.getMonth()+1}月` : `${d.getMonth()+1}` });
  }
  for(const o of occ){
    const b = buckets.find(x => x.y === o.date.getFullYear() && x.m === o.date.getMonth());
    if(b) b.val += o.amt;
  }
  const max = Math.max(...buckets.map(b => b.val), 1);
  $('#d-flow').innerHTML =
    `<div class="flow">` + buckets.map(b => `
      <div class="fl-c ${b.now?'now':''}" title="${esc(b.label)} ${esc(fmt(b.val,cur))}">
        <span class="tip">${esc(fmtK(b.val, cur))}</span>
        <div class="bv" style="height:${Math.max(1, b.val/max*100)}%"></div>
      </div>`).join('') + `</div>
     <div class="flow-x">` + buckets.map(b =>
      `<span class="${b.now?'now':''}">${esc(b.short)}</span>`).join('') + `</div>`;

  /* 排行 */
  const rank = subs.filter(s => s.enabled)
    .map(s => ({ s, val: s.cycle === 'once' ? amtIn(s,cur,rate) : yearly(s,cur,rate) }))
    .filter(r => r.val > 0).sort((a,b) => b.val - a.val).slice(0,10);
  const rmax = rank.length ? rank[0].val : 1;
  $('#d-rank').innerHTML = rank.length ? `<div class="rank">` + rank.map((r,i) => `
    <div class="rk"><span class="n">${i+1}</span>${icon(r.s)}
      <div class="bd"><b class="trunc">${esc(r.s.name)}</b>
        <div class="tr"><i style="width:${r.val/rmax*100}%"></i></div></div>
      <div class="vl">${esc(fmt(r.val,cur))}<em>${esc(fmt(r.val/12,cur))}/月</em></div>
    </div>`).join('') + `</div>` : miniEmpty('暂无支出数据');

  /* 即将扣费 */
  const up = occ.slice(0,12);
  $('#d-due').innerHTML = up.length ? `<div class="due-list">` + up.map(o => {
    const d = diffDays(o.date, t0);
    const cls = d <= 2 ? 'd0' : d <= 7 ? 'd1' : 'd2';
    const txt = d === 0 ? '今天' : d === 1 ? '明天' : `${d} 天后`;
    return `<div class="due-i" data-edit="${esc(o.sub.id)}">${icon(o.sub)}
      <div class="bd"><b class="trunc">${esc(o.sub.name)}</b>
        <span>${esc(isoD(o.date))} · ${esc(CYCLES[o.sub.cycle].name)}${
          o.sub.plan ? ' · ' + esc(o.sub.plan) : ''}</span></div>
      <div class="vl"><b>${esc(fmt(o.amt,cur))}</b>
        <span class="${cls}">${txt}</span></div></div>`;
  }).join('') + `</div>` : miniEmpty('近期没有账单');
}

/* ════════════ 订阅明细表 ════════════ */
export function renderSubs(S){
  const { cur, rate } = S;
  const q = S.filter.q.trim().toLowerCase();
  let list = S.subs.filter(s => {
    if(!S.filter.nsfw && s.nsfw) return false;
    if(!S.filter.off && !s.enabled) return false;
    if(S.filter.cat && s.cat !== S.filter.cat) return false;
    if(S.filter.cyc && s.cycle !== S.filter.cyc) return false;
    if(q && !(`${s.name} ${s.plan||''} ${s.note||''} ${s.domain||''}`.toLowerCase().includes(q)))
      return false;
    return true;
  });
  const { key, dir } = S.sort;
  const val = s => {
    if(key === 'name') return s.name;
    if(key === 'cat')  return (CATS[s.cat]||{}).name || s.cat;
    if(key === 'price') return amtIn(s, cur, rate);
    if(key === 'year') return s.cycle === 'once' ? amtIn(s,cur,rate) : yearly(s,cur,rate);
    if(key === 'due'){ const d = daysLeft(s); return d === null ? 1e9 : d; }
    return 0;
  };
  list.sort((a,b) => {
    const x = val(a), y = val(b);
    const c = typeof x === 'string' ? x.localeCompare(y,'zh') : x - y;
    return dir === 'desc' ? -c : c;
  });

  const tt = totals(list, cur, rate);
  $('#f-cnt').textContent = `${list.length} / ${S.subs.length} 项`;
  const wrap = $('#subs-wrap');
  if(!list.length){
    wrap.innerHTML = `<div class="empty"><b>没有匹配的订阅</b>
      <p>${S.subs.length ? '试试调整筛选条件' : '从服务库添加，或手动新建一条'}</p>
      <button class="btn pri" data-act="add">＋ 添加订阅</button></div>`;
    return;
  }
  const th = (k, label, cls = '') =>
    `<th class="${cls} sortable" data-sort="${k}">${label}${
      key === k ? `<span class="ar">${dir==='asc'?'▲':'▼'}</span>` : ''}</th>`;
  wrap.innerHTML = `<table class="tbl"><thead><tr>
      ${th('name','服务')}${th('cat','分类')}
      <th>周期</th>${th('price','单价','r')}${th('due','下次扣费')}
      ${th('year','年度等效','r')}<th class="r">操作</th>
    </tr></thead><tbody>` + list.map(s => row(s, cur, rate)).join('') +
    `</tbody><tfoot><tr>
      <td colspan="3">合计 ${list.filter(x=>x.enabled).length} 项生效</td>
      <td class="r">${esc(fmt(tt.month, cur))}/月</td>
      <td>${tt.once > 0 ? '一次性 ' + esc(fmt(tt.once, cur)) : ''}</td>
      <td class="r">${esc(fmt(tt.year, cur))}</td><td></td>
    </tr></tfoot></table>`;
}
function row(s, cur, rate){
  const c = CYCLES[s.cycle] || CYCLES.month;
  const once = s.cycle === 'once';
  const d = daysLeft(s);
  const nd = nextDue(s);
  const pr = progress(s);
  const bcls = d === null ? '' : d <= 2 ? 'd' : d <= 7 ? 'w' : '';
  const dtxt = once ? '一次性'
    : d === null ? '—'
    : d === 0 ? '今天' : d === 1 ? '明天' : `${d} 天后`;
  const dcls = once ? '' : d === null ? '' : d <= 2 ? 'd0' : d <= 7 ? 'd1' : 'd2';
  return `<tr class="${s.enabled?'':'off'}" data-id="${esc(s.id)}">
    <td><div class="cell-nm">${icon(s)}
      <div class="bd"><b class="trunc">${esc(s.name)}</b>
        <div class="sub">
          ${s.plan ? `<span>${esc(s.plan)}</span>` : ''}
          ${(+s.qty||1) > 1 ? `<span class="tg">×${+s.qty}</span>` : ''}
          ${s.nsfw ? '<span class="tg nsfw">NSFW</span>' : ''}
          ${!s.enabled ? '<span class="tg off">已停用</span>' : ''}
          ${!s.remind ? '<span class="tg">不提醒</span>' : ''}
          ${s.note ? `<span class="note" title="${esc(s.note)}">${esc(s.note)}</span>` : ''}
        </div></div></div></td>
    <td><span class="tg">${esc((CATS[s.cat]||{}).name || s.cat)}</span></td>
    <td><span class="tg ${once?'once':'cyc'}">${esc(c.name)}</span></td>
    <td class="r">${esc(fmt2(amtIn(s,cur,rate), cur))}
      <em style="font-style:normal;color:var(--ink-4);font-size:10px;display:block">
        ${esc(s.cur)} ${s.price}</em></td>
    <td>${once ? `<span style="color:var(--ink-4)">${esc(s.start)}</span>`
      : `<span class="mini-tr"><i class="${bcls}" style="width:${pr*100}%"></i></span>
         <span class="num" style="font-size:11.5px">${esc(isoD(nd))}</span>
         <em class="${dcls}" style="font-style:normal;font-size:10px;display:block;
           padding-left:63px">${dtxt}</em>`}</td>
    <td class="r">${once ? '—' : esc(fmt(yearly(s,cur,rate), cur))}
      ${once ? '' : `<em style="font-style:normal;color:var(--ink-4);font-size:10px;display:block">
        ${esc(fmt(monthly(s,cur,rate), cur))}/月</em>`}</td>
    <td class="r"><div class="row-act">
      <button data-act="edit">编辑</button>
      <button data-act="toggle">${s.enabled?'停用':'启用'}</button>
      ${s.domain ? `<button data-act="open">官网</button>` : ''}
      <button data-act="del" class="del">删除</button>
    </div></td></tr>`;
}

/* ════════════ 日历 ════════════ */
export function renderCal(S){
  const { cur, rate, calY, calM } = S;
  const occ = monthOccurrences(S.subs, calY, calM, cur, rate);
  $('#c-label').textContent = `${calY} 年 ${calM+1} 月`;
  const sum = occ.reduce((s,o) => s + o.amt, 0);
  $('#c-sum').textContent = `${occ.length} 笔 · ${fmt(sum, cur)}`;

  const wk = S.weekStart;
  const names = ['日','一','二','三','四','五','六'];
  const heads = [];
  for(let i=0;i<7;i++) heads.push(names[(wk+i)%7]);

  const first = new Date(calY, calM, 1);
  const lead = (first.getDay() - wk + 7) % 7;
  const dim = new Date(calY, calM+1, 0).getDate();
  const prevDim = new Date(calY, calM, 0).getDate();
  const t = today();
  const byDay = {};
  occ.forEach(o => { (byDay[o.date.getDate()] = byDay[o.date.getDate()] || []).push(o); });

  let h = heads.map(x => `<div class="cal-hd">${x}</div>`).join('');
  for(let i=0;i<lead;i++)
    h += `<div class="cal-d pad"><div class="cal-n"><b>${prevDim-lead+i+1}</b></div></div>`;
  for(let d=1;d<=dim;d++){
    const items = byDay[d] || [];
    const isT = t.getFullYear()===calY && t.getMonth()===calM && t.getDate()===d;
    const ds = items.reduce((s,o) => s + o.amt, 0);
    const shown = items.slice(0,3).map(o =>
      `<div class="cal-e" data-edit="${esc(o.sub.id)}" title="${esc(o.sub.name)} ${esc(fmt(o.amt,cur))}">
         ${icon(o.sub,'xs')}<s>${esc(o.sub.name)}</s>
         <span class="amt">${esc(fmtK(o.amt,cur))}</span></div>`).join('');
    h += `<div class="cal-d ${isT?'today':''}">
      <div class="cal-n"><b>${d}</b>${ds>0?`<em>${esc(fmtK(ds,cur))}</em>`:''}</div>
      ${shown}${items.length>3?`<div class="cal-more">+${items.length-3} 笔</div>`:''}</div>`;
  }
  const tail = (7 - (lead+dim) % 7) % 7;
  for(let i=1;i<=tail;i++)
    h += `<div class="cal-d pad"><div class="cal-n"><b>${i}</b></div></div>`;
  $('#c-grid').innerHTML = h;
}

/* ════════════ 服务库 ════════════ */
export function renderLib(S){
  const q = S.libQ.trim().toLowerCase();
  const showN = S.libNsfw;
  const list = LIB.filter(x => {
    if(!showN && x.nsfw) return false;
    if(q && !(`${x.name} ${x.domain||''}`.toLowerCase().includes(q))) return false;
    return true;
  });
  $('#l-cnt').textContent = `${list.length} 项服务 · ${
    list.reduce((s,x) => s + x.plans.length, 0)} 个方案`;

  /* 分类导航 */
  const groups = {};
  list.forEach(x => { (groups[x.cat] = groups[x.cat] || []).push(x); });
  const cats = Object.keys(CATS).filter(k => groups[k]);
  $('#l-nav').innerHTML = cats.map(k =>
    `<button data-jump="${k}">${esc(CATS[k].name)}<em>${groups[k].length}</em></button>`).join('')
    + (showN ? '' : `<button data-nsfw-on style="border-color:var(--nsfw);color:var(--nsfw)">
        + 成人内容<em>${LIB.filter(x=>x.nsfw).length}</em></button>`);

  const body = $('#l-body');
  if(!list.length){
    body.innerHTML = `<div class="empty"><b>没有匹配的服务</b>
      <p>换个关键词，或手动添加自定义订阅</p>
      <button class="btn pri" data-act="add">＋ 手动添加</button></div>`;
    return;
  }
  /* q 非空时自动展开，便于直接看到结果 */
  const openAll = !!q || S.libOpenAll;
  body.innerHTML = cats.map(k => {
    const c = CATS[k];
    const open = openAll || S.libOpen.has(k);
    return `<details class="grp" data-cat="${k}"${open?' open':''}>
      <summary><span class="ar">▶</span><h3>${esc(c.name)}</h3>
        <span class="n">${c.en} · ${groups[k].length}</span></summary>
      <div class="grp-b">${groups[k].map(x => {
        const p = firstPlan(x);
        const pr = p ? (p.cycle === 'once'
          ? `${fmt(conv(p.price,p.cur,S.cur,S.rate), S.cur)} 起`
          : `${fmt(conv(p.price,p.cur,S.cur,S.rate), S.cur)}/${CYCLES[p.cycle].short}`) : '';
        return `<button class="lib-i" data-lib="${libIndex(x)}">${icon(x)}
          <div class="bd"><b>${esc(x.name)}</b><span>${esc(x.domain||'')}</span></div>
          <span class="pr">${esc(pr)}</span></button>`;
      }).join('')}</div></details>`;
  }).join('');
}

/* 下拉选项 */
export function fillSelects(){
  $('#f-cat').innerHTML = '<option value="">全部分类</option>' +
    Object.entries(CATS).map(([k,v]) => `<option value="${k}">${esc(v.name)}</option>`).join('');
  $('#f-cyc').innerHTML = '<option value="">全部周期</option>' +
    CYC_KEYS.map(k => `<option value="${k}">${esc(CYCLES[k].name)}</option>`).join('');
}
