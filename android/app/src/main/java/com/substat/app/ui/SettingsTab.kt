package com.substat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

@Composable
fun SettingsTab(vm: MainViewModel, ui: UiState) {
    val p = LocalPalette.current
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 80.dp),
    ) {
        item {
            Kicker("SETTINGS")
            Spacer(Modifier.height(4.dp))
            Text("设置", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 30.sp, color = p.ink)
            Spacer(Modifier.height(20.dp))
        }

        // ——— 汇率与显示 ———
        item {
            SectionHeader("汇率与显示")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("USD → CNY", fontSize = 12.sp, color = p.ink2)
                    Text(
                        "%.4f".format(ui.prefs.rate).trimEnd('0').trimEnd('.'),
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace,
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
                        Text(
                            listOfNotNull(src.ifBlank { null }, ago.ifBlank { null })
                                .joinToString(" · "),
                            fontSize = 10.sp, color = p.ink4, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                InkButton("获取实时汇率", { vm.refreshRate() }, enabled = !ui.busy)
            }
            Spacer(Modifier.height(16.dp))
            Text("展示币种", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, color = p.ink3)
            Spacer(Modifier.height(6.dp))
            SegmentedPick(
                options = listOf("CNY" to "¥ 人民币", "USD" to "$ 美元"),
                selected = ui.prefs.cur, columns = 2,
            ) { vm.setCur(it) }
            Spacer(Modifier.height(14.dp))
            Text("主题", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, color = p.ink3)
            Spacer(Modifier.height(6.dp))
            SegmentedPick(
                options = listOf("system" to "随系统", "light" to "浅色", "dark" to "深色"),
                selected = ui.prefs.theme, columns = 3,
            ) { vm.setTheme(it) }
            Spacer(Modifier.height(14.dp))
            Text("「近期扣费」窗口：${ui.prefs.warnDays} 天", fontSize = 12.sp, color = p.ink2)
            Spacer(Modifier.height(6.dp))
            SegmentedPick(
                options = listOf(3 to "3 天", 7 to "7 天", 14 to "14 天", 30 to "30 天"),
                selected = ui.prefs.warnDays, columns = 4,
            ) { vm.setWarnDays(it) }
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                "显示成人内容分类", "关闭后 NSFW 订阅在明细中隐藏",
                ui.prefs.showNsfw,
            ) { vm.setShowNsfw(it) }
            Spacer(Modifier.height(22.dp))
        }

        // ——— 提醒 ———
        item {
            SectionHeader("到期提醒")
            ToggleRow(
                "本机通知提醒", "每天在设定时间检查，命中窗口时发系统通知",
                ui.prefs.localNotify,
            ) { vm.setLocalNotify(it) }
            if (ui.prefs.localNotify) {
                Spacer(Modifier.height(12.dp))
                Text("提醒时间：每天 ${"%02d".format(ui.prefs.notifyHour)}:05",
                    fontSize = 12.sp, color = p.ink2)
                Spacer(Modifier.height(6.dp))
                SegmentedPick(
                    options = listOf(8 to "08:05", 9 to "09:05", 12 to "12:05",
                                     20 to "20:05"),
                    selected = ui.prefs.notifyHour, columns = 4,
                ) { vm.setNotifyHour(it) }
            }
            Spacer(Modifier.height(14.dp))
            InkButton("在服务端执行一次提醒检查", { vm.runNotifyNow() },
                enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "服务端提醒（Bark / Telegram / Webhook）在网页版「设置」中配置，" +
                    "每天由 Cron 自动触发；此处仅为手动触发一次。",
                fontSize = 11.sp, color = p.ink4, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(22.dp))
        }

        // ——— 数据 ———
        item {
            SectionHeader("数据")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("订阅记录", fontSize = 12.sp, color = p.ink2)
                    Text("${ui.subs.size} 条" + if (ui.fromCache) "（离线缓存）" else "",
                        fontSize = 11.sp, color = p.ink4, fontFamily = FontFamily.Monospace)
                }
                InkButton("同步", { vm.refresh() }, enabled = !ui.refreshing)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "数据存储在你的 Cloudflare D1，与网页版实时同步。" +
                    "导入导出、清空等批量操作请在网页版进行。",
                fontSize = 11.sp, color = p.ink4, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(22.dp))
        }

        // ——— 账户 ———
        item {
            SectionHeader("账户")
            Text(ui.prefs.baseUrl.ifBlank { "未配置" }, fontSize = 11.5.sp, color = p.ink3,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("退出登录", { confirmLogout = true })
                InkButton("更换服务器", { confirmReset = true }, danger = true)
            }
            Spacer(Modifier.height(22.dp))
        }

        // ——— 口径说明 ———
        item {
            SectionHeader("计费口径")
            listOf(
                "年度等效 = 单价 × (365 ÷ 周期天数)。周期天数：日 1、周 7、月 30.4375、" +
                    "季 91.3125、半年 182.625、年 365。",
                "一次性付费不计入周期性支出，单列「一次性投入」，但仍出现在日历与现金流中。",
                "月末锚点：1 月 31 日起订的月费，扣费日为 1/31 → 2/28 → 3/31 → 4/30，" +
                    "始终以首次付费日为锚点取当月可用的最近日期。",
                "份数：多设备或合租按份数乘算；若为分摊，直接把单价填成你实际承担的金额。",
            ).forEach {
                Text(it, fontSize = 11.5.sp, color = p.ink3, lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("SubStat for Android · 1.0.0", fontSize = 10.sp, color = p.ink4,
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

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = p.ink)
            Text(hint, fontSize = 10.5.sp, color = p.ink4, lineHeight = 15.sp)
        }
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
