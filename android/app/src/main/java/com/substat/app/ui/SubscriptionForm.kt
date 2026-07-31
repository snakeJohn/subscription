package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.Billing
import com.substat.app.data.Category
import com.substat.app.data.Cycle
import com.substat.app.data.Subscription
import com.substat.app.data.SubscriptionPayload
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionForm(
    vm: MainViewModel,
    ui: UiState,
    existing: Subscription?,
    onDismiss: () -> Unit,
    onDelete: (Subscription) -> Unit,
) {
    val p = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var plan by remember { mutableStateOf(existing?.plan ?: "") }
    var priceText by remember {
        mutableStateOf(existing?.price?.let { trim(it) } ?: "")
    }
    var qtyText by remember { mutableStateOf((existing?.qty ?: 1).toString()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var cat by remember { mutableStateOf(existing?.cat ?: "ai") }
    var cycle by remember { mutableStateOf(Cycle.from(existing?.cycle)) }
    var cur by remember { mutableStateOf(existing?.cur ?: ui.prefs.cur) }
    var start by remember { mutableStateOf(existing?.start ?: LocalDate.now().toString()) }
    var nsfw by remember { mutableStateOf(existing?.nsfw ?: false) }
    var remind by remember { mutableStateOf(existing?.remind ?: true) }
    var err by remember { mutableStateOf<String?>(null) }

    val price = priceText.toDoubleOrNull() ?: 0.0
    val qty = qtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    /* 实时折算预览：构造临时对象复用同一套引擎 */
    val preview = Subscription(
        name = name, price = price, cur = cur, cycle = cycle.key, qty = qty, start = start,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = RectangleShape,
        containerColor = p.paper,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(name.ifBlank { "?" }, 34)
                Spacer(Modifier.height(0.dp))
                Text(
                    if (existing != null) "编辑订阅" else "添加订阅",
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, color = p.ink,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it; err = null },
                label = { Text("服务名称 *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("官网域名") },
                placeholder = { Text("netflix.com", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            FieldLabel("分类")
            SegmentedPick(
                options = Category.entries.map { it.key to it.label },
                selected = cat, columns = 3,
            ) { cat = it }
            Spacer(Modifier.height(14.dp))

            FieldLabel("计费周期")
            SegmentedPick(
                options = Cycle.entries.map { it to it.label },
                selected = cycle, columns = 4,
            ) { cycle = it }
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' }; err = null },
                    label = { Text("单价 *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("份数") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))

            FieldLabel("币种")
            SegmentedPick(
                options = listOf("CNY" to "¥ 人民币", "USD" to "$ 美元"),
                selected = cur, columns = 2,
            ) { cur = it }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = start,
                onValueChange = { start = it.take(10); err = null },
                label = { Text(if (cycle == Cycle.ONCE) "付费日期 *" else "首次付费日 *") },
                placeholder = { Text("YYYY-MM-DD", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = plan, onValueChange = { plan = it },
                label = { Text("方案 / 档位") },
                placeholder = { Text("例如 高级版 4K", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("备注") },
                placeholder = { Text("账号、合租人、续费方式…", fontSize = 13.sp) },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            CheckRow("标记为 NSFW（明细默认隐藏）", nsfw) { nsfw = it }
            CheckRow("参与到期提醒", remind) { remind = it }
            Spacer(Modifier.height(14.dp))

            /* 折算预览 */
            Column(Modifier.fillMaxWidth().background(p.card).padding(12.dp)) {
                if (cycle == Cycle.ONCE) {
                    val other = if (ui.prefs.cur == "CNY") "USD" else "CNY"
                    PreviewRow("一次性支出",
                        Billing.fmt(Billing.amountIn(preview, ui.prefs.cur, ui.prefs.rate), ui.prefs.cur))
                    PreviewRow("另一币种",
                        Billing.fmt(Billing.amountIn(preview, other, ui.prefs.rate), other))
                    Text("不计入周期性支出", fontSize = 10.5.sp, color = p.ink4)
                } else {
                    PreviewRow("折算月均",
                        Billing.fmt(Billing.monthly(preview, ui.prefs.cur, ui.prefs.rate), ui.prefs.cur))
                    PreviewRow("折算年均",
                        Billing.fmt(Billing.yearly(preview, ui.prefs.cur, ui.prefs.rate), ui.prefs.cur))
                    PreviewRow("折算日均",
                        Billing.fmt(Billing.daily(preview, ui.prefs.cur, ui.prefs.rate), ui.prefs.cur))
                    Billing.nextDue(preview)?.let {
                        PreviewRow("下次扣费", it.toString())
                    }
                }
            }

            if (err != null) {
                Spacer(Modifier.height(10.dp))
                Text(err!!, color = p.bad, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    InkButton("删除", { onDelete(existing) }, danger = true)
                }
                InkButton("取消", onDismiss, modifier = Modifier.weight(1f))
                InkButton(
                    if (existing != null) "保存修改" else "添加",
                    {
                        val e = validate(name, priceText, start)
                        if (e != null) {
                            err = e
                        } else {
                            vm.save(
                                SubscriptionPayload(
                                    name = name.trim(),
                                    domain = domain.trim().removePrefix("https://")
                                        .removePrefix("http://").substringBefore('/'),
                                    cat = cat, plan = plan.trim(), price = price, cur = cur,
                                    cycle = cycle.key, qty = qty, start = start.trim(),
                                    note = note.trim(),
                                    nsfw = if (nsfw) 1 else 0,
                                    enabled = if (existing?.enabled == false) 0 else 1,
                                    remind = if (remind) 1 else 0,
                                ),
                                existing?.id,
                            )
                            onDismiss()
                        }
                    },
                    primary = true, enabled = !ui.busy, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 与服务端 validate() 同口径的前置校验，避免多一次往返 */
private fun validate(name: String, price: String, start: String): String? {
    if (name.isBlank()) return "请填写服务名称"
    val p = price.toDoubleOrNull()
    if (p == null || p < 0) return "请填写有效的单价"
    if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(start.trim())) return "日期格式应为 YYYY-MM-DD"
    return try { LocalDate.parse(start.trim()); null } catch (e: Exception) { "日期无效" }
}

private fun trim(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
        color = LocalPalette.current.ink3, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun PreviewRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = p.ink3, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, color = p.ink)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = p.red, uncheckedColor = p.ink4),
        )
        Text(label, fontSize = 12.5.sp, color = p.ink2)
    }
}
