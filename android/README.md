# SubStat for Android

原生 Kotlin + Jetpack Compose 客户端，连接你自己部署的 SubStat Worker。
沿用网页版的杂志排版风格（纸白／墨黑／朱红、衬线大标题、等宽数字）。

- **总览** — 超大主数字月度支出、年度／日均／一次性、分类结构横条、12 个月现金流、支出排行、即将扣费
- **明细** — 搜索 + 分类／周期／停用筛选，四键排序（到期／年度／单价／名称），行内编辑停用删除
- **日历** — 月视图网格 + 当月账单明细，当天描红
- **设置** — 汇率手动刷新、币种、主题、提醒窗口、NSFW 开关、本机通知
- **本机提醒** — WorkManager 每天定时检查，命中「提前 N 天」或「当天」发系统通知
- **桌面小部件** — Glance 实现，显示月度支出与最近三笔扣费，读离线缓存不发网络请求
- **离线可用** — 列表写入 DataStore 缓存，断网时看板与小部件仍可展示

## ⚠️ 单元测试必须在纯 ASCII 路径下运行

本仓库位于 `J:\AI项目\subscription`，其中 `AI项目` 含中文。**应用编译（assembleDebug）
不受影响**，但 Gradle 的测试 worker 是独立 JVM，其 classpath 处理非 ASCII 路径会失败：

```
BillingTest > initializationError FAILED
  java.lang.ClassNotFoundException: com.substat.app.BillingTest
```

`.class` 文件确实已生成在 `app/build/tmp/kotlin-classes/debugUnitTest/`，
只是测试 JVM 加载不到。复制到纯 ASCII 路径即通过（10 tests, 0 failures）：

```powershell
robocopy "J:\AI项目\subscription\android" "C:\sbtest" /E /XD .gradle build .idea
cd C:\sbtest
.\gradlew.bat test
```

GitHub Actions 上不存在此问题（Linux 路径为 ASCII）。若要长期在本机跑测试，
建议把仓库放到 `C:\projects\substat` 这类路径。

## 构建

前置：JDK 17、Android SDK（platform 35、build-tools 35）。

`local.properties` 里的 `sdk.dir` 请用**正斜杠**：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

写成 `C\:\Users\...` 这种半转义形式会让 AGP 在 `SdkLocator.validateSdkPath`
抛 `IOException: 文件或目录名语法不正确`，报错信息指向 `compileDebugKotlin`
依赖解析失败，与真实原因（SDK 路径无效）相距较远，容易误判。

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat assembleDebug     # → app/build/outputs/apk/debug/
.\gradlew.bat assembleRelease   # → app/build/outputs/apk/release/
.\gradlew.bat installDebug      # 装到已连接设备
.\gradlew.bat test              # 需在 ASCII 路径下，见上节
```

首次构建需从 `dl.google.com` 下载约 600MB 依赖，国内易超时。
`gradle.properties` 里已放宽 socket 超时与重试次数；若仍失败，重跑即可续传。

Release 未配置签名时复用 debug 签名，可直接安装但不能上架。
正式发布请在 `app/build.gradle.kts` 中配置 `signingConfigs`。

CI：push 到 `main` 会自动跑测试并产出 debug／release APK，见
`.github/workflows/android.yml`。

## 首次使用

1. 打开应用，填入 Worker 地址（如 `https://substat.你的子域.workers.dev`）
   —— 会先校验 `/api/health` 再保存
2. 输入访问密码（即 `AUTH_PASSWORD`）
3. 会话 Cookie 持久化 30 天，之后冷启动直接进入

## 计费引擎的两份实现

`data/Billing.kt` 是 `public/shared/billing.js` 的 Kotlin 移植。两端各一份，
靠 `app/src/test/.../BillingTest.kt` 与 `test/billing.test.mjs` 里的**同款用例**
锁住语义——改动任一端都必须同步另一端并跑两边测试。

最容易出错的是**月末锚点**：1 月 31 日起订的月费，扣费日必须是
1/31 → 2/28 → 3/31 → 4/30，即每期都从首次付费日重新计算，而不是拿上一期结果
递推。递推会让日期夹到 2/28 后把 28 永久带下去，既错日期又会漏月。
两边测试都对此有专门断言。

## 提醒的分工

| 渠道 | 由谁触发 | 说明 |
|---|---|---|
| Bark / Telegram / Webhook | 服务端 Cron | 每天北京时间 09:00，`notify_log` 表去重 |
| 系统通知栏 | 本机 WorkManager | 每天设定时间，不依赖任何外部推送服务 |

两者互补：没配置任何推送渠道也能在手机上收到提醒。本机提醒会先尝试同步
最新数据，失败则用缓存——宁可基于旧数据提醒，也不静默。

## 结构

```
app/src/main/java/com/substat/app/
  MainActivity.kt          入口
  SubStatApp.kt            Application：依赖装配、通知渠道
  data/
    Billing.kt             ★ 计费引擎（Kotlin 移植）
    Models.kt              JSON 契约、分类枚举
    Api.kt                 Ktor 客户端，Cookie 会话
    Store.kt               DataStore 偏好 + 离线缓存
    Repo.kt                网络 + 缓存回退
  ui/
    Theme.kt               设计令牌（对齐 base.css）
    Components.kt          共用组件
    MainViewModel.kt       全部 UI 状态
    Onboard.kt             配置页 / 登录页
    Home.kt                报头 + 底部导航
    DashboardTab.kt        总览
    ListTab.kt             明细
    CalendarTab.kt         日历
    SettingsTab.kt         设置
    SubscriptionForm.kt    新增 / 编辑表单
  work/Reminder.kt         WorkManager 本机提醒
  widget/SubStatWidget.kt  Glance 桌面小部件
```

## 已知限制

- 服务库（293 项预置服务）未内置到客户端，添加订阅需手填名称与价格；
  从服务库快速预填请用网页版
- 导入导出、清空全部等批量操作仅网页版提供
- 单用户设计，无多账号切换
