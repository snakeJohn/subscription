/* billing.js — 计费引擎（前后端共用，ESM，无任何环境依赖）
 * 浏览器：import from '/shared/billing.js'
 * Worker ：import from '../shared/billing.js' */

export const CYCLES = {
  once:    { name:'一次性', short:'一次', days:0,       step:null },
  day:     { name:'日费',   short:'日',   days:1,       step:{d:1} },
  week:    { name:'周费',   short:'周',   days:7,       step:{d:7} },
  month:   { name:'月费',   short:'月',   days:30.4375, step:{m:1} },
  quarter: { name:'季度',   short:'季',   days:91.3125, step:{m:3} },
  half:    { name:'半年',   short:'半年', days:182.625, step:{m:6} },
  year:    { name:'年费',   short:'年',   days:365,     step:{m:12} },
};
export const CYC_KEYS = Object.keys(CYCLES);
export const DAY_MS = 864e5;

/* ——— 金额 ——— */
export function conv(amt, from, to, rate){
  if(!isFinite(amt)) return 0;
  if(from === to) return amt;
  const r = +rate > 0 ? +rate : 7.15;
  return from === 'USD' ? amt * r : amt / r;
}
/* 订阅折算到目标币种（含份数） */
export function amtIn(sub, cur, rate){
  return conv((+sub.price||0) * (+sub.qty||1), sub.cur||'CNY', cur, rate);
}
/* 年度等效；一次性返回 0（单列统计） */
export function yearly(sub, cur, rate){
  const c = CYCLES[sub.cycle];
  if(!c || !c.days) return 0;
  return amtIn(sub, cur, rate) * (365 / c.days);
}
export const monthly = (sub, cur, rate) => yearly(sub, cur, rate) / 12;
export const daily   = (sub, cur, rate) => yearly(sub, cur, rate) / 365;

export function totals(list, cur, rate){
  const act = (list||[]).filter(s => s.enabled !== false && s.enabled !== 0);
  let y = 0, once = 0;
  for(const s of act){
    if(s.cycle === 'once') once += amtIn(s, cur, rate);
    else y += yearly(s, cur, rate);
  }
  return { year:y, month:y/12, day:y/365, once, count:act.length, all:(list||[]).length };
}

/* ——— 日期 ——— */
/* 统一以本地零点为基准，避免跨时区把扣费日算偏一天 */
export function today(){ const d = new Date(); d.setHours(0,0,0,0); return d; }
export function parseD(s){
  if(!s) return null;
  const p = String(s).slice(0,10).split('-').map(Number);
  if(p.length < 3 || !p[0] || !p[1] || !p[2]) return null;
  const d = new Date(p[0], p[1]-1, p[2]);
  d.setHours(0,0,0,0);
  return isNaN(d.getTime()) ? null : d;
}
export function isoD(d){
  if(!d) return '';
  const p = n => String(n).padStart(2,'0');
  return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())}`;
}
export const diffDays = (a, b) => Math.round((a - b) / DAY_MS);

/* 从锚点 d 步进 n 个周期。必须一次性从锚点算出：
 * 逐次 advance(上次结果) 会让 1/31 退化成 1/31→2/28→3/28…，正确应为 3/31。 */
export function advance(d, cycle, n){
  n = (n == null) ? 1 : n;          /* 不可写 n||1，n=0 表示锚点自身 */
  const c = CYCLES[cycle];
  if(!c || !c.step) return null;
  if(n === 0){ const r = new Date(d); r.setHours(0,0,0,0); return r; }
  if(c.step.d){
    const r = new Date(d);
    r.setDate(r.getDate() + c.step.d * n);
    r.setHours(0,0,0,0);
    return r;
  }
  const dom = d.getDate();
  const r = new Date(d.getFullYear(), d.getMonth() + c.step.m * n, 1);
  const last = new Date(r.getFullYear(), r.getMonth()+1, 0).getDate();
  r.setDate(Math.min(dom, last));
  r.setHours(0,0,0,0);
  return r;
}

/* 下次扣费日：以 start 为锚点找第一个 >= from 的周期点 */
export function nextDue(sub, from){
  if(sub.cycle === 'once') return null;
  const st = parseD(sub.start);
  if(!st) return null;
  const t = from || today();
  if(st >= t) return st;
  const c = CYCLES[sub.cycle];
  if(!c || !c.step) return null;
  let k = Math.max(0, Math.floor(diffDays(t, st) / c.days));
  let cur = advance(st, sub.cycle, k), g = 0;
  while(cur < t && g++ < 800) cur = advance(st, sub.cycle, ++k);
  g = 0;
  while(k > 0 && g++ < 800){
    const prev = advance(st, sub.cycle, k-1);
    if(prev < t) break;
    k--; cur = prev;
  }
  return cur;
}
export function daysLeft(sub, from){
  const n = nextDue(sub, from);
  return n ? diffDays(n, from || today()) : null;
}
/* 当前周期已过比例 0~1 */
export function progress(sub, from){
  const n = nextDue(sub, from);
  const c = CYCLES[sub.cycle];
  if(!n || !c || !c.days) return 0;
  const left = diffDays(n, from || today());
  const span = Math.max(1, Math.round(c.days));
  return Math.max(0, Math.min(1, (span - left) / span));
}

/* ——— 账单展开 ——— */
/* 今天起 days 天内的所有扣费点 */
export function occurrences(list, days, cur, rate, from){
  const out = [];
  const t = from || today();
  const end = new Date(t.getTime() + days * DAY_MS);
  for(const s of (list||[])){
    if(s.enabled === false || s.enabled === 0) continue;
    if(s.cycle === 'once'){
      const d = parseD(s.start);
      if(d && d >= t && d <= end) out.push({sub:s, date:d, amt:amtIn(s,cur,rate)});
      continue;
    }
    const st = parseD(s.start);
    const first = nextDue(s, t);
    if(!st || !first) continue;
    const amt = amtIn(s, cur, rate);
    let k = Math.round(diffDays(first, st) / CYCLES[s.cycle].days);
    let d = advance(st, s.cycle, k), g = 0;
    while(d && d < t && g++ < 800) d = advance(st, s.cycle, ++k);
    g = 0;
    while(d && d <= end && g++ < 800){
      out.push({sub:s, date:d, amt});
      d = advance(st, s.cycle, ++k);
    }
  }
  return out.sort((a,b) => a.date - b.date);
}
/* 指定自然月内的扣费点（含已过去的），用于日历 */
export function monthOccurrences(list, y, m, cur, rate){
  const first = new Date(y, m, 1), last = new Date(y, m+1, 0);
  first.setHours(0,0,0,0); last.setHours(0,0,0,0);
  const out = [];
  for(const s of (list||[])){
    if(s.enabled === false || s.enabled === 0) continue;
    const st = parseD(s.start);
    if(!st) continue;
    const amt = amtIn(s, cur, rate);
    if(s.cycle === 'once'){
      if(st >= first && st <= last) out.push({sub:s, date:st, amt});
      continue;
    }
    const c = CYCLES[s.cycle];
    if(!c || !c.step) continue;
    let k = Math.max(0, Math.floor(diffDays(first, st) / c.days));
    let d = advance(st, s.cycle, k), g = 0;
    while(d && d < first && g++ < 3000) d = advance(st, s.cycle, ++k);
    g = 0;
    while(k > 0 && g++ < 3000){
      const prev = advance(st, s.cycle, k-1);
      if(prev < first) break;
      k--; d = prev;
    }
    g = 0;
    while(d && d <= last && g++ < 500){
      if(d >= st) out.push({sub:s, date:d, amt});
      d = advance(st, s.cycle, ++k);
    }
  }
  return out.sort((a,b) => a.date - b.date);
}

/* ——— 格式化 ——— */
export function fmt(v, cur){
  const sym = cur === 'USD' ? '$' : '¥';
  const n = Math.abs(v) >= 1000
    ? Math.round(v).toLocaleString('en-US')
    : (Math.round(v*100)/100).toLocaleString('en-US',{maximumFractionDigits:2});
  return sym + n;
}
export function fmtK(v, cur){
  const sym = cur === 'USD' ? '$' : '¥';
  if(Math.abs(v) >= 10000) return sym + (v/10000).toFixed(1) + 'w';
  if(Math.abs(v) >= 1000)  return sym + (v/1000).toFixed(1) + 'k';
  return sym + Math.round(v);
}
/* 精确到分，用于表格右对齐 */
export function fmt2(v, cur){
  const sym = cur === 'USD' ? '$' : '¥';
  return sym + v.toLocaleString('en-US',{minimumFractionDigits:2, maximumFractionDigits:2});
}
