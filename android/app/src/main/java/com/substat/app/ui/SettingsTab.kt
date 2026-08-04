package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

/* 版式节奏：相关元素 8 / 标签到控件 10 / 控件之间 18 / 小节之间 36 */
private val SpTight = 8.dp
private val SpLabel = 10.dp
private val SpCtrl = 18.dp
private val SpSection = 36.dp

@Composable
fun SettingsTab(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    /* 进入设置页时拉一次服务端设置（webdav_* 等） */
    LaunchedEffect(Unit) { vm.loadServerSettings() }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
    ) {
        item {
            Kicker("SETTINGS")
            Spacer(Modifier.height(SpTight))
            Text("设置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 30.sp, color = p.ink)
            Spacer(Modifier.height(28.dp))
        }

        // ——— 汇率与显示 ———
        item {
            SettingsSection("汇率与显示") {
                // 汇率数值与取数按钮各占一行，长中文按钮不会被挤窄
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SpLabel),
                ) {
                    Column {
                        FieldLabel("USD → CNY")
                        Text(
                            "%.4f".format(ui.prefs.rate).trimEnd('0').trimEnd('.'),
                            fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, color = p.ink,
                        )
                        val src = ui.prefs.rateSource
                        val at = ui.prefs.rateAt
                        if (src.isNotBlank() || at > 0) {
                            val ago = if (at > 0) {
                                val mins = (System.currentTimeMillis() - at) / 60000
                                when {
                                    mins < 60 -> "$mins 分钟前"
                                    mins < 1440 -> "${mins / 60} 小时前"
                                    else -> Instant.ofEpochMilli(at)
                                        .atZone(ZoneId.systemDefault()).toLocalDate().toString()
                                }
                            } else ""
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(src.ifBlank { null }, ago.ifBlank { null })
                                    .joinToString(" · "),
                                fontSize = 11.5.sp, color = p.ink4,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    InkButton("获取实时汇率", { vm.refreshRate() }, enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(SpCtrl))
                Hairline()
                Spacer(Modifier.height(SpCtrl))

                FieldLabel("展示币种")
                SegmentedPick(
                    options = listOf("CNY" to "¥ 人民币", "USD" to "$ 美元"),
                    selected = ui.prefs.cur, columns = 2,
                ) { vm.setCur(it) }

                Spacer(Modifier.height(SpCtrl))
                FieldLabel("主题")
                SegmentedPick(
                    options = listOf("system" to "随系统", "light" to "浅色", "dark" to "深色"),
                    selected = ui.prefs.theme, columns = 3,
                ) { vm.setTheme(it) }

                Spacer(Modifier.height(SpCtrl))
                FieldLabel("「近期扣费」窗口：${ui.prefs.warnDays} 天")
                SegmentedPick(
                    options = listOf(3 to "3 天", 7 to "7 天", 14 to "14 天", 30 to "30 天"),
                    selected = ui.prefs.warnDays, columns = 4,
                ) { vm.setWarnDays(it) }

                Spacer(Modifier.height(SpCtrl))
                Hairline()
                ToggleRow(
                    "显示成人内容分类", "关闭后 NSFW 订阅在明细中隐藏",
                    ui.prefs.showNsfw,
                ) { vm.setShowNsfw(it) }
            }
        }

        // ——— 提醒 ———
        item {
            SettingsSection("到期提醒") {
                ToggleRow(
                    "本机通知提醒", "每天在设定时间检查，命中窗口时发系统通知",
                    ui.prefs.localNotify,
                ) { vm.setLocalNotify(it) }
                if (ui.prefs.localNotify) {
                    Hairline()
                    Spacer(Modifier.height(SpCtrl))
                    FieldLabel("提醒时间：每天 ${"%02d".format(ui.prefs.notifyHour)}:05")
                    SegmentedPick(
                        options = listOf(8 to "08:05", 9 to "09:05", 12 to "12:05",
                                         20 to "20:05"),
                        selected = ui.prefs.notifyHour, columns = 4,
                    ) { vm.setNotifyHour(it) }
                }
                Spacer(Modifier.height(SpCtrl))
                InkButton("在服务端执行一次提醒检查", { vm.runNotifyNow() },
                    enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(SpTight))
                NoteText(
                    "服务端提醒（Bark / Telegram / Webhook）在网页版「设置」中配置，" +
                        "每天由 Cron 自动触发；此处仅为手动触发一次。",
                )
            }
        }

        // ——— 数据 ———
        item {
            SettingsSection("数据") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("订阅记录", fontSize = 13.5.sp, color = p.ink2)
                        Spacer(Modifier.height(4.dp))
                        Text("${ui.subs.size} 条" + if (ui.fromCache) "（离线缓存）" else "",
                            fontSize = 12.sp, color = p.ink4,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(SpCtrl))
                    InkButton("同步", { vm.refresh() }, enabled = !ui.refreshing)
                }
                Spacer(Modifier.height(SpCtrl))
                NoteText(
                    "数据存储在你的 Cloudflare D1，与网页版实时同步。" +
                        "导入导出、清空等批量操作请在网页版进行。",
                )
            }
        }

        // ——— WebDAV 云备份 ———
        item {
            SettingsSection("WebDAV 云备份") {
                val st = ui.serverSt
                var wdUrl by remember(st["webdav_url"]) {
                    mutableStateOf(st["webdav_url"] ?: "")
                }
                var wdUser by remember(st["webdav_user"]) {
                    mutableStateOf(st["webdav_user"] ?: "")
                }
                var wdPass by remember(st["webdav_pass"]) {
                    mutableStateOf(st["webdav_pass"] ?: "")
                }
                OutlinedTextField(
                    value = wdUrl, onValueChange = { wdUrl = it },
                    label = { Text("服务器地址（目录）") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/substat/", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpLabel))
                OutlinedTextField(
                    value = wdUser, onValueChange = { wdUser = it },
                    label = { Text("账号") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpLabel))
                OutlinedTextField(
                    value = wdPass, onValueChange = { wdPass = it },
                    label = { Text("应用密码") },
                    visualTransformation = if (wdPass.startsWith("••"))
                        VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpCtrl))
                ToggleRow(
                    "每天自动备份", "随服务端定时任务执行，失败不影响到期提醒",
                    st["webdav_auto"] == "1",
                ) { vm.saveServerSettings(mapOf("webdav_auto" to if (it) "1" else "0")) }
                Spacer(Modifier.height(SpCtrl))
                Row(horizontalArrangement = Arrangement.spacedBy(SpLabel)) {
                    InkButton("保存配置", {
                        /* 掩码密码不回传，服务端保持原值 */
                        val patch = buildMap {
                            put("webdav_url", wdUrl.trim())
                            put("webdav_user", wdUser.trim())
                            if (!wdPass.startsWith("••")) put("webdav_pass", wdPass)
                        }
                        vm.saveServerSettings(patch, "WebDAV 配置已保存")
                    }, enabled = !ui.busy, modifier = Modifier.weight(1f))
                    InkButton("测试连接", {
                        vm.testWebdav(wdUrl.trim(), wdUser.trim(), wdPass)
                    }, enabled = !ui.busy, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(SpLabel))
                InkButton("立即备份到云端", { vm.backupWebdav() },
                    enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(SpTight))
                NoteText(
                    "备份为目录下的 substat-backup.json，由服务端代理访问。" +
                        "坚果云请在「安全选项」里生成应用密码。从云端恢复请在网页版操作。",
                )
            }
        }

        // ——— 账户 ———
        item {
            SettingsSection("账户") {
                FieldLabel("当前账号")
                Text(ui.username.ifBlank { "—" }, fontSize = 16.sp, color = p.ink,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(SpCtrl))
                FieldLabel("服务器")
                Text(ui.prefs.baseUrl.ifBlank { "未配置" }, fontSize = 13.sp, color = p.ink3,
                    fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
                Spacer(Modifier.height(SpCtrl))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpLabel),
                ) {
                    InkButton("退出登录", { confirmLogout = true },
                        modifier = Modifier.weight(1f))
                    InkButton("更换服务器", { confirmReset = true }, danger = true,
                        modifier = Modifier.weight(1f))
                }
            }
        }

        // ——— 口径说明 ———
        item {
            SectionHeader("计费口径")
            // 说明文字收进浅底方块，与上方可操作项区分
            Column(
                Modifier.fillMaxWidth().background(p.paper2).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(SpLabel),
            ) {
                listOf(
                    "年度等效 = 单价 × (365 ÷ 周期天数)。周期天数：日 1、周 7、月 30.4375、" +
                        "季 91.3125、半年 182.625、年 365。",
                    "一次性付费不计入周期性支出，单列「一次性投入」，但仍出现在日历与现金流中。",
                    "月末锚点：1 月 31 日起订的月费，扣费日为 1/31 → 2/28 → 3/31 → 4/30，" +
                        "始终以首次付费日为锚点取当月可用的最近日期。",
                    "份数：多设备或合租按份数乘算；若为分摊，直接把单价填成你实际承担的金额。",
                ).forEach {
                    Text(it, fontSize = 12.5.sp, color = p.ink2, lineHeight = 21.sp)
                }
            }
            Spacer(Modifier.height(SpCtrl))
            InkButton(
                if (ui.updateChecking) "检查中…" else "检查更新",
                { vm.checkUpdate(manual = true) },
                enabled = !ui.updateChecking, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(SpTight))
            Text("SubStat for Android · 1.4.0", fontSize = 11.5.sp, color = p.ink4,
                fontFamily = FontFamily.Monospace)
        }
    }

    if (confirmLogout) ConfirmDialog(
        "退出登录", "退出后需要重新输入访问密码。", "退出",
        onConfirm = { confirmLogout = false; vm.logout() },
        onDismiss = { confirmLogout = false },
    )
    if (confirmReset) ConfirmDialog(
        "更换服务器", "将清除已保存的服务器地址与登录状态，需要重新配置。", "更换",
        onConfirm = { confirmReset = false; vm.resetServer() },
        onDismiss = { confirmReset = false },
    )
}

/** 小节容器：标题 + 内容 + 统一的小节间距 */
@Composable
private fun SettingsSection(
    title: String,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, note)
        content()
        Spacer(Modifier.height(SpSection))
    }
}

/** 字段小标签：小号加粗字距，与表单页一致 */
@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
        color = LocalPalette.current.ink3, lineHeight = 18.sp,
        modifier = Modifier.padding(bottom = SpLabel))
}

/** 辅助说明文字：允许换行，行距放宽 */
@Composable
private fun NoteText(text: String) {
    Text(text, fontSize = 12.sp, color = LocalPalette.current.ink4, lineHeight = 20.sp)
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = p.ink, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.5.sp, color = p.ink4, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(SpCtrl))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.paper,
                checkedTrackColor = p.red,
                uncheckedThumbColor = p.ink4,
                uncheckedTrackColor = p.paper2,
                uncheckedBorderColor = p.hair,
            ),
        )
    }
}
