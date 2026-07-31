# SubStat · 订阅计费统计

前后端分离的订阅支出统计应用。前端纯静态无构建，后端 Cloudflare Workers + D1 + KV，
一条 `wrangler deploy` 上线；另有原生 Android 客户端。

- **多计费周期** — 一次性 / 日 / 周 / 月 / 季 / 半年 / 年，统一折算日均·月均·年均
- **双币种** — 人民币 + 美元，实时汇率自动获取（多源回退），也可手工锁定
- **到期提醒** — Bark / Telegram Bot / 自定义 Webhook，每日 Cron 触发，同笔不重复推送
- **服务库** — 293 项国内外服务、559 个定价方案、14 个可折叠分类，点选即带方案预填
- **视图** — 总览看板、订阅明细表（多列排序）、账期日历、服务库、设置
- **数据** — JSON 导入导出（合并/覆盖）、CSV 导出（Excel 兼容）
- **Android 客户端** — 原生 Kotlin + Compose，含桌面小部件与本机通知提醒，离线可用
- 深浅主题、成人内容分类默认隐藏、响应式布局

## 部署

前置：已有 Cloudflare 账号，本机装好 Node 18+。

```bash
npm install
npx wrangler login
```

**1. 创建 D1 与 KV**

```bash
npx wrangler d1 create substat
npx wrangler kv namespace create SUBSTAT_KV
```

把两条命令输出的 ID 填进 `wrangler.toml` 对应的占位符：

| 占位符 | 来源 |
|---|---|
| `YOUR_ACCOUNT_ID` | `npx wrangler whoami` |
| `YOUR_D1_DATABASE_ID` | `d1 create` 输出的 `database_id` |
| `YOUR_KV_NAMESPACE_ID` | `kv namespace create` 输出的 `id` |
| `subs.example.com` | 你自己的域名（需已托管在同一账号） |

**2. 设置访问密码**

```bash
npx wrangler secret put AUTH_PASSWORD
```

**3.（可选）配置提醒渠道**

用 Secret 存更安全，也可以部署后在「设置」页面里填：

```bash
npx wrangler secret put BARK_URL       # https://api.day.app/YOUR_KEY
npx wrangler secret put TG_BOT_TOKEN
npx wrangler secret put TG_CHAT_ID
```

**4. 部署**

```bash
npx wrangler deploy
```

数据表在首次请求时自动创建，无需手动跑 migration。若想预先建表：
`npm run db:init`。

**关于自定义域名与 token 权限**

`wrangler deploy` 写 `routes` 时会调 `/zones/<id>/workers/routes`，需要
**Zone → Workers Routes → Edit** 权限。若 token 只有账号级权限，部署会在上传成功后
报 `Authentication error [code: 10000]` 并以非零码退出 —— 此时 Worker 其实**已经上传**，
只是域名和 Cron 没绑上。两种解法：

1. 给 token 补上 Zone Workers Routes 编辑权限，重跑 `wrangler deploy`；
2. 或用账号级 API 单独绑定（本项目即此法）：

```bash
# 绑定自定义域名
curl -X PUT "https://api.cloudflare.com/client/v4/accounts/$ACC/workers/domains" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"zone_id":"<ZONE_ID>","hostname":"subs.example.com","service":"substat","environment":"production"}'

# 注册 Cron（注意 body 是裸数组，不是 {"schedules":[...]}）
curl -X PUT "https://api.cloudflare.com/client/v4/accounts/$ACC/workers/scripts/substat/schedules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '[{"cron":"0 1 * * *"}]'
```

**本地开发**

```bash
cp .dev.vars.example .dev.vars   # 填一个开发密码
npm run dev                      # http://127.0.0.1:8787
```

## 架构

```
public/                前端静态资源（由 Workers Assets 托管）
  index.html
  css/{base,app,views}.css
  js/{main,views,forms,settings,api,ui-kit,catalog}.js
  shared/billing.js    ★ 计费引擎，Web 与 Worker 共用同一份
worker/
  index.js             API 路由 + Cron 入口
  auth.js              PBKDF2 密码 + KV 会话
  rate.js              汇率多源获取 + 缓存
  notify.js            Bark / Telegram / Webhook 推送
android/               ★ 原生 Kotlin + Compose 客户端（见 android/README.md）
schema.sql             D1 表结构
test/billing.test.mjs  计费引擎测试（77 条）
.github/workflows/     Android CI（产出 APK）
```

计费引擎 `public/shared/billing.js` 被浏览器和 Worker 同时 import，
保证前端显示的下次扣费日和后端推送提醒判断的是同一个日期，不会漂移。

安卓端 `android/app/src/main/java/com/substat/app/data/Billing.kt` 是该引擎的
Kotlin 移植——语言不同无法共用源码，因此用两套**同款测试用例**锁住语义
（`test/billing.test.mjs` 与 `BillingTest.kt`），改动任一端都要同步另一端。

## API

除 `/api/health`、`/api/auth/*` 外均需登录（HttpOnly Cookie 会话）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回会话 Cookie |
| POST | `/api/auth/logout` | 退出 |
| POST | `/api/auth/password` | 改密码 |
| GET | `/api/subscriptions` | 列表 |
| POST | `/api/subscriptions` | 新建 |
| PUT PATCH DELETE | `/api/subscriptions/:id` | 改 / 部分改 / 删 |
| POST | `/api/subscriptions/bulk` | 批量导入（`mode: merge\|replace`） |
| GET PUT | `/api/settings` | 读写设置（写入走白名单） |
| GET | `/api/rate?refresh=1` | 汇率，带 6 小时 KV 缓存 |
| POST | `/api/notify/test` | 测试推送（`channel: bark\|tg\|hook`） |
| POST | `/api/notify/run` | 立即执行提醒检查 |
| GET | `/api/notify/log` | 最近 50 条推送记录 |

## 计费口径

年度等效 = 单价 × (365 ÷ 周期天数)，周期天数取 日 1、周 7、月 30.4375、季 91.3125、
半年 182.625、年 365。月均 = 年度 ÷ 12，日均 = 年度 ÷ 365。

一次性付费不计入周期性支出，单列「一次性投入」，但仍出现在日历与现金流图中。

**月末锚点** — 1 月 31 日起订的月费，扣费日为 1/31 → 2/28 → 3/31 → 4/30：始终以首次
付费日为锚点取当月可用的最近日期。若逐期递推（`advance(上次结果)`），2 月夹取到 28 号后
会把 28 永久带下去，既错日期又会漏月，测试里对此有专门回归。

**份数** — 多设备或合租按份数乘算；若为分摊，直接把单价填成你实际承担的金额。

## 提醒机制

Cron `0 1 * * *`（UTC）即北京时间每天 09:00 触发：先按需刷新汇率，再扫描
`enabled=1 AND remind=1` 的订阅，命中「提前 N 天」或「当天」的账单各推一次。
`notify_log` 表以 `(sub_id, due_date, channel)` 唯一约束去重，同一笔同一渠道不会重复推。
日志保留 90 天。

## 安全说明

- 密码 PBKDF2-SHA256 10 万次迭代 + 随机盐，常数时间比较
  （Workers 运行时上限为 10 万次，超过会抛错；本地 Miniflare 不校验此限制）
- 会话 id 存 KV（TTL 30 天），Cookie `HttpOnly + Secure + SameSite=Lax`
- 登录失败同 IP 15 分钟内限 8 次
- 所有写入在服务端重新校验，前端校验仅为体验
- 设置写入走键白名单，`password_hash` 无法被前端接口覆盖
- Token / Webhook 地址读取时掩码返回，不回显明文

## 已知限制

- 汇率源为免费公开接口，欧洲央行数据仅工作日更新；三源全部不可用时回退到上次缓存值
- 服务库价格是公开定价参考值（约 2026 年），仅用于预填，请以官方页面为准；
  机场订阅等使用占位域名，添加后自行修改
- 单用户设计，无多账号与权限体系
