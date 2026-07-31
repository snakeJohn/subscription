/* rate.js — 实时 USD→CNY 汇率，多源回退 + KV 缓存
 * 所有源均为免费公开接口、无需 API Key。 */

const CACHE_KEY = 'rate:usdcny';
const TTL = 60 * 60 * 6;        // 缓存 6 小时
const TIMEOUT = 6000;

const SOURCES = {
  /* 欧洲央行数据，稳定但仅工作日更新 */
  frankfurter: {
    url: 'https://api.frankfurter.app/latest?from=USD&to=CNY',
    pick: j => j?.rates?.CNY,
  },
  exchangerate: {
    url: 'https://open.er-api.com/v6/latest/USD',
    pick: j => j?.rates?.CNY,
  },
  /* CDN 托管的静态汇率表，可用性最高 */
  fawazahmed: {
    url: 'https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json',
    pick: j => j?.usd?.cny,
  },
};

async function fetchOne(name){
  const s = SOURCES[name];
  if(!s) return null;
  const ac = new AbortController();
  const tm = setTimeout(() => ac.abort(), TIMEOUT);
  try{
    const r = await fetch(s.url, { signal: ac.signal, cf:{ cacheTtl: 300 } });
    if(!r.ok) return null;
    const v = s.pick(await r.json());
    /* 合理区间校验，防止拿到明显错误的数值 */
    return (typeof v === 'number' && v > 3 && v < 15) ? v : null;
  }catch(e){
    return null;
  }finally{
    clearTimeout(tm);
  }
}

/* 返回 {rate, source, at, cached, stale} */
export async function getRate(env, force){
  if(!force){
    const hit = await env.KV.get(CACHE_KEY, 'json');
    if(hit && hit.rate) return { ...hit, cached:true };
  }
  const order = String(env.RATE_SOURCES || 'frankfurter,exchangerate,fawazahmed')
    .split(',').map(s => s.trim()).filter(Boolean);
  for(const name of order){
    const v = await fetchOne(name);
    if(v){
      const data = { rate: Math.round(v*10000)/10000, source:name, at: Date.now() };
      await env.KV.put(CACHE_KEY, JSON.stringify(data), { expirationTtl: TTL });
      return { ...data, cached:false };
    }
  }
  /* 全部源失败：回退到上次缓存（可能已过期），再回退到手工值 */
  const stale = await env.KV.get(CACHE_KEY, 'json');
  if(stale && stale.rate) return { ...stale, cached:true, stale:true };
  return { rate:null, source:null, at:Date.now(), failed:true };
}
