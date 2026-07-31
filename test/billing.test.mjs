/* 计费引擎测试：node test/billing.test.mjs */
import * as B from '../public/shared/billing.js';
import { LIB, CATS } from '../public/js/catalog.js';

const { CYCLES, conv, amtIn, yearly, monthly, daily, totals, advance, nextDue,
        daysLeft, progress, occurrences, monthOccurrences, isoD, parseD,
        diffDays, today, fmt, fmt2, fmtK } = B;

let pass = 0, fail = 0;
const eq = (n, got, want, tol = 0.01) => {
  const ok = typeof want === 'number' && typeof got === 'number'
    ? Math.abs(got - want) <= tol : got === want;
  ok ? pass++ : (fail++, console.log(`  FAIL ${n}\n    got  ${got}\n    want ${want}`));
};
const R = 7.15;
const mk = o => ({ price:0, qty:1, cur:'CNY', cycle:'month', enabled:1, ...o });
const D = (y,m,d) => new Date(y, m-1, d);

/* ── 周期归一 ── */
eq('年费→年', yearly(mk({price:1200,cycle:'year'}),'CNY',R), 1200);
eq('月费→年', yearly(mk({price:100,cycle:'month'}),'CNY',R), 100*(365/30.4375), .5);
eq('日费→年', yearly(mk({price:1,cycle:'day'}),'CNY',R), 365);
eq('周费→年', yearly(mk({price:7,cycle:'week'}),'CNY',R), 365);
eq('季度→年', yearly(mk({price:300,cycle:'quarter'}),'CNY',R), 1199.3, 1);
eq('半年→年', yearly(mk({price:600,cycle:'half'}),'CNY',R), 1199.3, 1);
eq('一次性不入周期', yearly(mk({price:999,cycle:'once'}),'CNY',R), 0);
eq('月均=年/12', monthly(mk({price:1200,cycle:'year'}),'CNY',R), 100);
eq('日均=年/365', daily(mk({price:365,cycle:'year'}),'CNY',R), 1);
eq('份数乘算', yearly(mk({price:100,cycle:'year',qty:3}),'CNY',R), 300);

/* ── 汇率 ── */
eq('USD→CNY', conv(100,'USD','CNY',R), 715);
eq('CNY→USD', conv(715,'CNY','USD',R), 100);
eq('同币种直通', conv(50,'USD','USD',R), 50);
eq('往返一致', conv(conv(37,'CNY','USD',R),'USD','CNY',R), 37);
eq('缺省汇率兜底', conv(1,'USD','CNY',0), 7.15);
eq('非法金额→0', conv(NaN,'USD','CNY',R), 0);

/* ── 汇总 ── */
const set = [
  mk({id:'a',price:20,cur:'USD',cycle:'month'}),
  mk({id:'b',price:1200,cycle:'year'}),
  mk({id:'c',price:500,cycle:'once'}),
  mk({id:'d',price:100,cycle:'month',enabled:0}),
];
const T = totals(set,'CNY',R);
eq('汇总年度', T.year, 20*R*(365/30.4375)+1200, 2);
eq('一次性单列', T.once, 500);
eq('停用不计数', T.count, 3);
eq('总数含停用', T.all, 4);
eq('enabled=0 视为停用', totals([mk({price:9,enabled:0})],'CNY',R).count, 0);

/* ── 日期推进 ── */
eq('月推进', isoD(advance(D(2026,1,15),'month',1)), '2026-02-15');
eq('季度推进', isoD(advance(D(2026,1,15),'quarter',1)), '2026-04-15');
eq('半年推进', isoD(advance(D(2026,1,15),'half',1)), '2026-07-15');
eq('年推进', isoD(advance(D(2026,3,1),'year',1)), '2027-03-01');
eq('周推进', isoD(advance(D(2026,1,1),'week',1)), '2026-01-08');
eq('日推进', isoD(advance(D(2026,1,1),'day',1)), '2026-01-02');
eq('跨年推进', isoD(advance(D(2026,12,15),'month',1)), '2027-01-15');
eq('n=0 返回锚点', isoD(advance(D(2026,5,9),'month',0)), '2026-05-09');
eq('一次性无步进', advance(D(2026,1,1),'once',1), null);

/* ── 月末锚点（核心回归）── */
eq('1/31 第1期', isoD(advance(D(2026,1,31),'month',1)), '2026-02-28');
eq('1/31 第2期', isoD(advance(D(2026,1,31),'month',2)), '2026-03-31');
eq('1/31 第3期', isoD(advance(D(2026,1,31),'month',3)), '2026-04-30');
eq('1/31 第4期', isoD(advance(D(2026,1,31),'month',4)), '2026-05-31');
eq('1/30 跨2月', isoD(advance(D(2026,1,30),'month',2)), '2026-03-30');
eq('闰年 1/31→2/29', isoD(advance(D(2028,1,31),'month',1)), '2028-02-29');
eq('闰日年推进', isoD(advance(D(2028,2,29),'year',1)), '2029-02-28');
eq('3/31 季度', isoD(advance(D(2026,3,31),'quarter',1)), '2026-06-30');
eq('8/31 半年', isoD(advance(D(2026,8,31),'half',1)), '2027-02-28');

/* ── 下次扣费 ── */
const t = today();
const back = n => { const d = new Date(t); d.setDate(d.getDate()-n); return isoD(d); };
const fwd  = n => { const d = new Date(t); d.setDate(d.getDate()+n); return isoD(d); };
eq('今天起订→今天', isoD(nextDue(mk({cycle:'month',start:isoD(t)}))), isoD(t));
eq('未来起订→起订日', isoD(nextDue(mk({cycle:'month',start:fwd(10)}))), fwd(10));
eq('日费昨起→今天', isoD(nextDue(mk({cycle:'day',start:back(1)}))), isoD(t));
eq('一次性无到期', daysLeft(mk({cycle:'once',start:back(5)})), null);
eq('无日期→null', nextDue(mk({cycle:'month',start:''})), null);
eq('非法日期→null', nextDue(mk({cycle:'month',start:'不是日期'})), null);
const nd = nextDue(mk({cycle:'month',start:back(45)}));
eq('不回退到过去', diffDays(nd, t) >= 0, true);
eq('落在一周期内', diffDays(nd, t) <= 31, true);
eq('久远年费推进到未来', nextDue(mk({cycle:'year',start:back(3000)})) >= t, true);
const a31 = nextDue(mk({cycle:'month',start:'2020-01-31'}));
const l31 = new Date(a31.getFullYear(), a31.getMonth()+1, 0).getDate();
eq('nextDue 保持 31 锚点', a31.getDate() === Math.min(31,l31), true);
eq('进度 0~1', progress(mk({cycle:'month',start:back(15)})) <= 1, true);
eq('进度非负', progress(mk({cycle:'month',start:back(15)})) >= 0, true);

/* ── 账单展开 ── */
eq('月费一年12次', occurrences([mk({id:'x',price:30,cycle:'month',start:back(5)})],365,'CNY',R).length, 12);
eq('日费30天31次', occurrences([mk({id:'y',price:9,cycle:'day',start:back(2)})],30,'CNY',R).length, 31);
eq('一次性未来计入', occurrences([mk({id:'z',price:99,cycle:'once',start:fwd(10)})],30,'CNY',R).length, 1);
eq('一次性过去不计入', occurrences([mk({id:'p',price:99,cycle:'once',start:back(10)})],30,'CNY',R).length, 0);
eq('停用不展开', occurrences([mk({id:'q',price:5,cycle:'month',start:back(3),enabled:0})],60,'CNY',R).length, 0);
const o31 = occurrences([mk({id:'a',price:31,cycle:'month',start:'2026-01-31'})],200,'CNY',R);
eq('展开保持月末锚点', o31.every(o => o.date.getDate() >= 28), true);
eq('展开无重复', new Set(o31.map(o=>isoD(o.date))).size, o31.length);
eq('展开升序', o31.every((o,i)=>i===0||o.date>=o31[i-1].date), true);

/* ── 日历 ── */
const cal31 = [mk({id:'c',price:31,cycle:'month',start:'2026-01-31'})];
eq('日历月末夹取', isoD(monthOccurrences(cal31,2026,1,'CNY',R)[0].date), '2026-02-28');
const per = [];
for(let i=0;i<6;i++){
  const d = new Date(2026, 6+i, 1);
  per.push(monthOccurrences(cal31, d.getFullYear(), d.getMonth(),'CNY',R).length);
}
eq('日历每月恰一次', per.join(','), '1,1,1,1,1,1');
const futr = [mk({id:'f',price:10,cycle:'month',start:'2026-10-15'})];
eq('起订前不展开', monthOccurrences(futr,2026,7,'CNY',R).length, 0);
eq('起订当月展开', monthOccurrences(futr,2026,9,'CNY',R).length, 1);

/* ── 格式化 ── */
eq('CNY 符号', fmt(1234.5,'CNY'), '¥1,235');
eq('USD 符号', fmt(9.99,'USD'), '$9.99');
eq('零值', fmt(0,'CNY'), '¥0');
eq('两位小数', fmt2(1234.5,'CNY'), '¥1,234.50');
eq('万级缩写', fmtK(23456,'CNY'), '¥2.3w');
eq('千级缩写', fmtK(2345,'CNY'), '¥2.3k');

/* ── 服务库 ── */
eq('库规模', LIB.length > 250, true);
eq('分类齐全', Object.keys(CATS).every(k => LIB.some(x => x.cat === k)), true);
const badItem = LIB.filter(x => !x.name || !x.plans?.length || !CATS[x.cat]);
eq('条目结构完整', badItem.length, 0);
const badPlan = [];
LIB.forEach(x => x.plans.forEach(p => {
  if(p.length !== 4 || typeof p[1] !== 'number' || !isFinite(p[1]) ||
     !['CNY','USD'].includes(p[2]) || !CYCLES[p[3]]) badPlan.push(`${x.name}/${p[0]}`);
}));
eq('方案字段合法', badPlan.length, 0);
if(badPlan.length) console.log('   ', badPlan.slice(0,6));
const dup = Object.entries(LIB.reduce((m,x)=>(m[x.name]=(m[x.name]||0)+1,m),{}))
  .filter(([,c]) => c > 1);
eq('无重名', dup.length, 0);
if(dup.length) console.log('    重名:', dup.map(d=>d[0]));
eq('NSFW 已标记', LIB.filter(x=>x.cat==='nsfw').every(x=>x.nsfw), true);
eq('非 NSFW 未误标', LIB.filter(x=>x.cat!=='nsfw').every(x=>!x.nsfw), true);
const onlyfans = LIB.find(x => x.name === 'OnlyFans');
eq('OnlyFans 在库且标记', !!onlyfans && onlyfans.nsfw === true, true);

console.log(`\n${fail?'✗':'✓'} ${pass} passed, ${fail} failed`);
console.log(`  服务库 ${LIB.length} 项 / ${Object.keys(CATS).length} 分类 / ` +
  `${LIB.reduce((s,x)=>s+x.plans.length,0)} 方案`);
for(const k of Object.keys(CATS))
  console.log(`    ${CATS[k].name.padEnd(13,'　')} ${LIB.filter(x=>x.cat===k).length}`);
process.exit(fail ? 1 : 0);
