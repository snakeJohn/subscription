/* settings.js — 设置面板 */

import { $, $$, esc, toast, confirmDlg, dialog, closeDialog } from './ui-kit.js';
import { api } from './api.js';
import { CYCLES, isoD, today, nextDue, monthly, yearly, amtIn } from '/shared/billing.js';
import { CATS } from './catalog.js';

export function renderSettings(S, ctl){
  const st = S.raw || {};
  const ch = S.channels || {};
  /* 上次 WebDAV 备份时间（服务端在成功备份后写入 webdav_last） */
  const wdLast = (() => {
    try{
      const o = JSON.parse(st.webdav_last || '');
      return `上次备份：${new Date(o.at).toLocaleString('zh-CN', { hour12:false })} · ${o.n} 条`;
    }catch(e){ return '尚未备份过'; }
  })();
  $('#set-grid').innerHTML = `
    <div class="set-b"><h3>汇率与显示</h3><div class="set-r">
      <div class="row">
        <label class="fld"><span>USD → CNY</span>
          <input class="in" id="s-rate" type="number" step="0.0001" min="0.01" value="${S.rate}"></label>
        <button class="btn" id="s-rate-get">获取实时汇率</button>
      </div>
      <label class="chk"><input type="checkbox" id="s-auto" ${st.rate_mode!=='manual'?'checked':''}>
        <span>每日自动刷新汇率（随定时任务）</span></label>
      <div class="doc" id="s-rate-info"></div>
      <div class="row">
        <label class="fld"><span>展示币种</span><select class="sel" id="s-cur">
          <option value="CNY"${S.cur==='CNY'?' selected':''}>人民币 ¥</option>
          <option value="USD"${S.cur==='USD'?' selected':''}>美元 $</option></select></label>
        <label class="fld"><span>每周起始</span><select class="sel" id="s-week">
          <option value="1"${S.weekStart===1?' selected':''}>周一</option>
          <option value="0"${S.weekStart===0?' selected':''}>周日</option></select></label>
      </div>
      <label class="fld"><span>看板「近期扣费」窗口（天）</span>
        <input class="in" id="s-warn" type="number" min="1" max="90" value="${S.warnDays}"></label>
    </div></div>

    <div class="set-b"><h3>到期提醒</h3><div class="set-r">
      <label class="fld"><span>提前几天提醒</span>
        <input class="in" id="s-nday" type="number" min="0" max="60"
          value="${parseInt(st.notify_days||'3',10)}"></label>
      <div class="doc">每天 09:00（北京时间）由 Cron 触发，命中「提前 N 天」或「当天」的账单各推一次，
        同一笔同一渠道不会重复推送。</div>
      <hr class="hair">
      <label class="chk"><input type="checkbox" id="s-nbark" ${st.notify_bark!=='0'?'checked':''}>
        <span>Bark（iOS 推送）${ch.bark?'<b style="color:var(--ok)"> 已配置</b>':''}</span></label>
      <label class="fld"><span>Bark 地址${ch.bark_from_env?'（已由 Secret 提供）':''}</span>
        <input class="in" id="s-bark" placeholder="https://api.day.app/YOUR_KEY"
          value="${esc(st.bark_url||'')}" ${ch.bark_from_env?'disabled':''}></label>
      <label class="chk"><input type="checkbox" id="s-ntg" ${st.notify_tg!=='0'?'checked':''}>
        <span>Telegram Bot${ch.tg?'<b style="color:var(--ok)"> 已配置</b>':''}</span></label>
      <div class="row">
        <label class="fld"><span>Bot Token${ch.tg_from_env?'（Secret）':''}</span>
          <input class="in" id="s-tgt" placeholder="123456:ABC-DEF…"
            value="${esc(st.tg_token||'')}" ${ch.tg_from_env?'disabled':''}></label>
        <label class="fld"><span>Chat ID</span>
          <input class="in" id="s-tgc" placeholder="123456789"
            value="${esc(st.tg_chat||'')}" ${ch.tg_from_env?'disabled':''}></label>
      </div>
      <label class="chk"><input type="checkbox" id="s-nhook" ${st.notify_hook==='1'?'checked':''}>
        <span>自定义 Webhook</span></label>
      <label class="fld"><span>Webhook 地址</span>
        <input class="in" id="s-hook" placeholder="https://…" value="${esc(st.webhook_url||'')}"></label>
      <div class="row">
        <button class="btn" data-test="bark">测试 Bark</button>
        <button class="btn" data-test="tg">测试 Telegram</button>
        <button class="btn" data-test="hook">测试 Webhook</button>
      </div>
      <button class="btn gh" id="s-run">立即执行一次提醒检查</button>
      <div id="s-log"></div>
    </div></div>

    <div class="set-b"><h3>数据管理</h3><div class="set-r">
      <div class="row">
        <button class="btn" id="s-exp">导出 JSON</button>
        <button class="btn" id="s-imp">导入 JSON</button>
        <button class="btn" id="s-csv">导出 CSV</button>
      </div>
      <button class="btn gh" id="s-demo">载入示例数据（18 条）</button>
      <button class="btn dgr" id="s-clr">清空全部订阅</button>
      <input type="file" id="s-file" accept=".json,application/json" hidden>
      <div class="doc">数据存储在 Cloudflare D1，跨设备同步。导出的 JSON 可用于备份或迁移。</div>
    </div></div>

    <div class="set-b"><h3>WebDAV 云备份</h3><div class="set-r">
      <label class="fld"><span>服务器地址（目录）</span>
        <input class="in" id="s-wd-url" placeholder="https://dav.jianguoyun.com/dav/substat/"
          value="${esc(st.webdav_url||'')}"></label>
      <div class="row">
        <label class="fld"><span>账号</span>
          <input class="in" id="s-wd-user" autocomplete="off"
            value="${esc(st.webdav_user||'')}"></label>
        <label class="fld"><span>应用密码</span>
          <input class="in" id="s-wd-pass" type="password" autocomplete="new-password"
            value="${esc(st.webdav_pass||'')}"></label>
      </div>
      <label class="chk"><input type="checkbox" id="s-wd-auto" ${st.webdav_auto==='1'?'checked':''}>
        <span>每天自动备份（随定时任务）</span></label>
      <div class="row">
        <button class="btn" id="s-wd-test">测试连接</button>
        <button class="btn" id="s-wd-bak">立即备份</button>
        <button class="btn gh" id="s-wd-res">从云端恢复</button>
      </div>
      <div class="doc">${esc(wdLast)}。备份为目录下的 <code>substat-backup.json</code>，
        由服务端代理访问，无需网盘开放 CORS。坚果云请在「安全选项」里生成应用密码。</div>
    </div></div>

    <div class="set-b"><h3>账户与安全</h3><div class="set-r">
      <label class="fld"><span>当前账号</span>
        <div class="num" style="font-size:15px;color:var(--ink)">${esc(S.username || '—')}</div></label>
      <div class="row">
        <button class="btn" id="s-pw">修改密码</button>
        <button class="btn gh" id="s-out">退出登录</button>
      </div>
      <div class="doc">
        <p>密码以 PBKDF2-SHA256（10 万次迭代）哈希存储，会话 Cookie 为
          <code>HttpOnly + Secure + SameSite=Lax</code>，有效期 30 天。</p>
        <p>登录失败同 IP 15 分钟内限 8 次。各账号数据互相隔离。</p>
      </div>
    </div></div>

    <div class="set-b full"><h3>计费口径</h3>
      <div class="doc">
        <p><b>年度等效</b> = 单价 × (365 ÷ 周期天数)。周期天数：日 1、周 7、月 30.4375、
          季 91.3125、半年 182.625、年 365。月均 = 年度 ÷ 12，日均 = 年度 ÷ 365。</p>
        <p><b>一次性付费</b>不计入周期性支出，单列「一次性投入」，但仍出现在日历与现金流图中。</p>
        <p><b>月末锚点</b>：1 月 31 日起订的月费，扣费日为 1/31 → 2/28 → 3/31 → 4/30，
          即始终以首次付费日为锚点取当月可用的最近日期，不会因 2 月夹取而永久前移。</p>
        <p><b>份数</b>：多设备或合租填写份数按倍数计算；若为分摊，可直接把单价填为你实际承担的金额。</p>
      </div></div>`;

  /* —— 汇率 —— */
  const rateInfo = () => {
    const el = $('#s-rate-info');
    if(!S.rateMeta){ el.textContent = ''; return; }
    const m = S.rateMeta;
    if(m.failed){ el.innerHTML = '<b style="color:var(--bad)">实时获取失败，使用手工汇率</b>'; return; }
    const ago = m.at ? Math.round((Date.now() - m.at) / 60000) : null;
    el.innerHTML = `数据源 <code>${esc(m.source||'手工')}</code>` +
      (ago !== null ? ` · ${ago < 60 ? ago + ' 分钟前' : Math.round(ago/60) + ' 小时前'}更新` : '') +
      (m.stale ? ' · <b style="color:var(--warn)">源不可用，为缓存值</b>' : '');
  };
  rateInfo();
  $('#s-rate').onchange = e => {
    const v = parseFloat(e.target.value);
    if(v > 0) ctl.save({ rate:String(v), rate_mode:'manual' }, () => {
      S.rate = v; S.raw.rate_mode = 'manual'; $('#s-auto').checked = false; ctl.refresh();
    });
  };
  $('#s-rate-get').onclick = async e => {
    e.target.disabled = true;
    try{ await ctl.fetchRate(true); renderSettings(S, ctl); toast('汇率已更新'); }
    catch(err){ toast('获取失败：' + err.message, true); }
    finally{ e.target.disabled = false; }
  };
  $('#s-auto').onchange = e =>
    ctl.save({ rate_mode: e.target.checked ? 'auto' : 'manual' }, () => {
      S.raw.rate_mode = e.target.checked ? 'auto' : 'manual';
    });
  $('#s-cur').onchange  = e => ctl.setCur(e.target.value);
  $('#s-week').onchange = e => ctl.save({ week:e.target.value }, () => {
    S.weekStart = +e.target.value; ctl.refresh();
  });
  $('#s-warn').onchange = e => {
    const v = Math.max(1, Math.min(90, +e.target.value||7));
    ctl.save({ warn:String(v) }, () => { S.warnDays = v; ctl.refresh(); });
  };

  /* —— 提醒 —— */
  $('#s-nday').onchange  = e => ctl.save({ notify_days:String(Math.max(0, Math.min(60, +e.target.value||3))) });
  $('#s-nbark').onchange = e => ctl.save({ notify_bark: e.target.checked?'1':'0' });
  $('#s-ntg').onchange   = e => ctl.save({ notify_tg:   e.target.checked?'1':'0' });
  $('#s-nhook').onchange = e => ctl.save({ notify_hook: e.target.checked?'1':'0' });
  const saveIf = (id, key) => {
    const el = $(id);
    if(el && !el.disabled) el.onchange = () => ctl.save({ [key]: el.value.trim() });
  };
  saveIf('#s-bark','bark_url'); saveIf('#s-tgt','tg_token');
  saveIf('#s-tgc','tg_chat');   saveIf('#s-hook','webhook_url');

  $$('[data-test]').forEach(b => b.onclick = async () => {
    b.disabled = true;
    const label = b.textContent;
    b.textContent = '发送中…';
    try{
      const r = await api.notifyTest(b.dataset.test);
      toast(r.ok ? '测试消息已发送' : '发送失败：' + (r.detail || r.status), !r.ok);
    }catch(e){ toast('发送失败：' + e.message, true); }
    finally{ b.disabled = false; b.textContent = label; }
  });
  $('#s-run').onclick = async b => {
    const btn = $('#s-run');
    btn.disabled = true;
    try{
      const r = await api.notifyRun({});
      toast(r.msg || `已推送 ${r.sent} 条，跳过 ${r.skipped} 条`);
      showLog();
    }catch(e){ toast('执行失败：' + e.message, true); }
    finally{ btn.disabled = false; }
  };
  async function showLog(){
    try{
      const r = await api.notifyLog();
      if(!r.items.length){ $('#s-log').innerHTML = '<div class="doc">暂无推送记录</div>'; return; }
      $('#s-log').innerHTML = `<table class="log-t"><tbody>` + r.items.slice(0,12).map(x =>
        `<tr><td>${esc(x.due_date)}</td><td>${esc(x.channel)}</td>
          <td class="${x.ok?'ok':'no'}">${x.ok?'成功':'失败'}</td>
          <td class="trunc" style="max-width:150px">${esc(x.detail||'')}</td></tr>`).join('')
        + `</tbody></table>`;
    }catch(e){}
  }
  showLog();

  /* —— 数据 —— */
  $('#s-exp').onclick = () => {
    if(!S.subs.length) return toast('没有数据可导出');
    dl(new Blob([JSON.stringify({ v:2, at:new Date().toISOString(),
      settings:{ rate:S.rate, cur:S.cur }, items:S.subs }, null, 2)],
      { type:'application/json' }), `substat-${isoD(today())}.json`);
    toast('已导出 JSON');
  };
  $('#s-csv').onclick = () => {
    if(!S.subs.length) return toast('没有数据可导出');
    const cur = S.cur, rate = S.rate;
    const head = ['名称','分类','方案','周期','单价','币种','份数','首次付费日','下次扣费',
                  `月均(${cur})`,`年度(${cur})`,'状态','提醒','NSFW','备注'];
    const rows = S.subs.map(s => [
      s.name, (CATS[s.cat]||{}).name || s.cat, s.plan||'', CYCLES[s.cycle].name,
      s.price, s.cur, s.qty||1, s.start, isoD(nextDue(s))||'',
      s.cycle==='once' ? '' : monthly(s,cur,rate).toFixed(2),
      s.cycle==='once' ? amtIn(s,cur,rate).toFixed(2) : yearly(s,cur,rate).toFixed(2),
      s.enabled?'生效中':'已停用', s.remind?'是':'否', s.nsfw?'是':'否', s.note||'',
    ]);
    const csv = [head, ...rows].map(r =>
      r.map(c => `"${String(c==null?'':c).replace(/"/g,'""')}"`).join(',')).join('\r\n');
    dl(new Blob(['﻿'+csv], { type:'text/csv;charset=utf-8' }),
       `substat-${isoD(today())}.csv`);
    toast('已导出 CSV');
  };
  $('#s-imp').onclick = () => $('#s-file').click();
  $('#s-file').onchange = e => {
    const f = e.target.files[0];
    if(!f) return;
    const r = new FileReader();
    r.onload = async () => {
      try{
        const d = JSON.parse(r.result);
        const items = Array.isArray(d.items) ? d.items : Array.isArray(d.subs) ? d.subs : null;
        if(!items) throw new Error('格式不正确：缺少 items 数组');
        let mode = 'merge';
        if(S.subs.length){
          const rep = await confirmDlg(
            `当前已有 ${S.subs.length} 条记录，导入 ${items.length} 条。\n` +
            `点「覆盖替换」将删除现有全部记录；点取消可改为合并追加。`, '覆盖替换');
          mode = rep ? 'replace' : 'merge';
        }
        const res = await api.bulk(items, mode);
        toast(`已导入 ${res.imported} 条${res.skipped?`，跳过 ${res.skipped} 条无效`:''}`);
        await ctl.reload();
      }catch(err){ toast('导入失败：' + err.message, true); }
      e.target.value = '';
    };
    r.readAsText(f);
  };
  $('#s-demo').onclick = async () => {
    const btn = $('#s-demo');
    btn.disabled = true;
    try{
      const res = await api.bulk(demoData(), 'merge');
      toast(`已载入 ${res.imported} 条示例数据`);
      await ctl.reload();
      ctl.go('dash');
    }catch(e){ toast('载入失败：' + e.message, true); }
    finally{ btn.disabled = false; }
  };
  $('#s-clr').onclick = async () => {
    if(!S.subs.length) return toast('当前没有数据');
    if(!await confirmDlg(`确认清空全部 ${S.subs.length} 条订阅记录？此操作不可撤销。`, '清空全部')) return;
    await api.clearAll();
    toast('已清空');
    await ctl.reload();
  };

  /* —— WebDAV —— */
  const wdSave = (id, key) => {
    const el = $(id);
    el.onchange = () => {
      const v = el.value.trim();
      if(key === 'webdav_pass' && v.startsWith('••')) return;   /* 掩码值不回写 */
      ctl.save({ [key]: v });
    };
  };
  wdSave('#s-wd-url','webdav_url');
  wdSave('#s-wd-user','webdav_user');
  wdSave('#s-wd-pass','webdav_pass');
  $('#s-wd-auto').onchange = e => ctl.save({ webdav_auto: e.target.checked ? '1' : '0' });
  $('#s-wd-test').onclick = async () => {
    const b = $('#s-wd-test');
    b.disabled = true;
    try{
      const r = await api.webdavTest({
        url:  $('#s-wd-url').value.trim(),
        user: $('#s-wd-user').value.trim(),
        pass: $('#s-wd-pass').value,
      });
      toast(r.ok ? '连接成功' : '连接失败：' + (r.detail || r.status), !r.ok);
    }catch(e){ toast('连接失败：' + e.message, true); }
    finally{ b.disabled = false; }
  };
  $('#s-wd-bak').onclick = async () => {
    const b = $('#s-wd-bak');
    b.disabled = true;
    try{
      const r = await api.webdavBackup();
      if(r.ok){
        toast(`已备份 ${r.count} 条到云端`);
        S.raw.webdav_last = JSON.stringify({ at: Date.now(), n: r.count });
        renderSettings(S, ctl);
      }else toast('备份失败：' + (r.detail || ''), true);
    }catch(e){ toast('备份失败：' + e.message, true); }
    finally{ b.disabled = false; }
  };
  $('#s-wd-res').onclick = async () => {
    const b = $('#s-wd-res');
    b.disabled = true;
    try{
      const r = await api.webdavRestore();
      if(!r.ok) return toast('拉取失败：' + (r.detail || ''), true);
      const items = Array.isArray(r.data && r.data.items) ? r.data.items : null;
      if(!items || !items.length) return toast('云端备份为空或格式不正确', true);
      let mode = 'merge';
      if(S.subs.length){
        const at = r.data.at ? String(r.data.at).slice(0,19).replace('T',' ') : '时间未知';
        const rep = await confirmDlg(
          `云端备份 ${items.length} 条（${at}），当前已有 ${S.subs.length} 条。\n` +
          `点「覆盖替换」将删除现有全部记录后导入；点取消可改为合并追加。`, '覆盖替换');
        mode = rep ? 'replace' : 'merge';
      }
      const res = await api.bulk(items, mode);
      toast(`已恢复 ${res.imported} 条${res.skipped?`，跳过 ${res.skipped} 条无效`:''}`);
      await ctl.reload();
    }catch(e){ toast('恢复失败：' + e.message, true); }
    finally{ b.disabled = false; }
  };

  /* —— 账户 —— */
  $('#s-pw').onclick = () => {
    dialog(`<div class="dlg-h"><h3>修改访问密码</h3><button class="x" data-close>×</button></div>
      <div class="dlg-b"><div class="fgrid">
        <label class="fld full"><span>当前密码</span>
          <input class="in" type="password" id="pw-old" autofocus></label>
        <label class="fld full"><span>新密码（至少 6 位）</span>
          <input class="in" type="password" id="pw-new"></label>
        <label class="fld full"><span>确认新密码</span>
          <input class="in" type="password" id="pw-re"></label>
      </div></div>
      <div class="dlg-f"><button class="btn" data-close>取消</button>
        <button class="btn pri" id="pw-ok">保存</button></div>`);
    $('#pw-ok').onclick = async () => {
      const a = $('#pw-new').value, b = $('#pw-re').value;
      if(a.length < 6) return toast('新密码至少 6 位', true);
      if(a !== b) return toast('两次输入不一致', true);
      try{
        await api.setPassword($('#pw-old').value, a);
        closeDialog();
        toast('密码已修改');
      }catch(e){ toast(e.message, true); }
    };
  };
  $('#s-out').onclick = async () => {
    if(!await confirmDlg('确认退出登录？', '退出')) return;
    await api.logout();
    location.reload();
  };
}

function dl(blob, name){
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/* 示例数据：取自服务库真实定价 */
function demoData(){
  const t = today();
  const sh = n => { const d = new Date(t); d.setDate(d.getDate()+n); return isoD(d); };
  const P = (name, domain, cat, plan, price, cur, cycle, off, note) =>
    ({ name, domain, cat, plan, price, cur, cycle, qty:1, start:sh(off),
       note:note||'', nsfw:0, enabled:1, remind:1 });
  return [
    P('ChatGPT','openai.com','ai','Plus',20,'USD','month',-12),
    P('Claude','claude.ai','ai','Max 5×',100,'USD','month',-3),
    P('GitHub Copilot','github.com','ai','Pro',10,'USD','month',-18),
    P('Netflix','netflix.com','video','高级版 4K',24.99,'USD','month',-25,'与家人共享'),
    P('哔哩哔哩','bilibili.com','video','大会员年',233,'CNY','year',-200),
    P('Spotify','spotify.com','music','个人',11.99,'USD','month',-8),
    P('网易云音乐','music.163.com','music','黑胶年',168,'CNY','year',-140),
    P('搬瓦工 BandwagonHost','bandwagonhost.com','vps','CN2 GIA 年付',89.99,'USD','year',-150),
    P('阿里云','aliyun.com','vps','轻量 2C2G 年',108,'CNY','year',-90),
    P('Cloudflare Registrar','cloudflare.com','host','.com 域名',10.44,'USD','year',-300),
    P('JetBrains','jetbrains.com','dev','全家桶年',289,'USD','year',-260),
    P('Sublime Text','sublimetext.com','dev','个人许可',99,'USD','once',-400),
    P('百度网盘','pan.baidu.com','cloud','超级会员年',298,'CNY','year',-120),
    P('Adobe Creative Cloud','adobe.com','design','摄影计划',19.99,'USD','month',-6),
    P('机场 / 代理订阅','example.com','vpn','年付',150,'CNY','year',-100),
    P('Steam','steampowered.com','game','单游戏',60,'USD','once',-30,'夏促入库'),
    P('京东 PLUS','jd.com','shop','年卡',149,'CNY','year',-60),
    P('微信读书','weread.qq.com','read','年卡',218,'CNY','year',-45),
  ];
}
