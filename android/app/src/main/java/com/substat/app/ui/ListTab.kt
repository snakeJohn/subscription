package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.Billing
import com.substat.app.data.Category
import com.substat.app.data.Cycle
import com.substat.app.data.Subscription

@Composable
fun ListTab(
    vm: MainViewModel,
    ui: UiState,
    onEdit: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
) {
    val p = LocalPalette.current
    val list = vm.visibleSubs(ui)
    val cur = ui.prefs.cur
    val rate = ui.prefs.rate
    val t = Billing.totals(list, cur, rate)

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 90.dp),
    ) {
        item {
            OutlinedTextField(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("搜索名称 / 方案 / 备注…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = p.ink4,
                    modifier = Modifier.width(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            /* 筛选条：分类 / 周期 / 开关，横向滚动 */
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip("全部分类", ui.catFilter == null) { vm.setCatFilter(null) }
                Category.entries.filter { !it.nsfw || ui.prefs.showNsfw }.forEach { c ->
                    FilterChip(c.label, ui.catFilter == c.key) {
                        vm.setCatFilter(if (ui.catFilter == c.key) null else c.key)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip("全部周期", ui.cycleFilter == null) { vm.setCycleFilter(null) }
                Cycle.entries.forEach { c ->
                    FilterChip(c.label, ui.cycleFilter == c.key) {
                        vm.setCycleFilter(if (ui.cycleFilter == c.key) null else c.key)
                    }
                }
                FilterChip("含停用", ui.showDisabled) { vm.setShowDisabled(!ui.showDisabled) }
            }
            Spacer(Modifier.height(10.dp))
            /* 排序 + 计数 */
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    SortKey.Due to "到期",
                    SortKey.Year to "年度",
                    SortKey.Price to "单价",
                    SortKey.Name to "名称",
                ).forEach { (k, label) ->
                    val on = ui.sortKey == k
                    Row(
                        Modifier.clickable { vm.sortBy(k) }.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 11.5.sp,
                            color = if (on) p.ink else p.ink4,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        if (on) Text(if (ui.sortDesc) " ▼" else " ▲",
                            fontSize = 8.sp, color = p.red)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${list.size} / ${ui.subs.size}", fontSize = 10.5.sp, color = p.ink4,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
        }

        if (list.isEmpty()) {
            item {
                EmptyState(
                    title = if (ui.subs.isEmpty()) "还没有订阅记录" else "没有匹配的订阅",
                    hint = if (ui.subs.isEmpty()) "点右下角按钮添加第一条" else "试试调整筛选条件",
                )
            }
        } else {
            items(list, key = { it.id }) { sub ->
                SubRow(sub, cur, rate, onEdit = { onEdit(sub) },
                    onToggle = { vm.toggle(sub) }, onDelete = { onDelete(sub) })
                Hairline()
            }
            item {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("合计 ${list.count { it.enabled }} 项生效", fontSize = 12.sp,
                        color = p.ink3, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${Billing.fmt(t.month, cur)}/月", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, color = p.ink,
                            fontFamily = FontFamily.Monospace)
                        Text("${Billing.fmt(t.year, cur)}/年", fontSize = 11.sp,
                            color = p.ink3, fontFamily = FontFamily.Monospace)
                        if (t.once > 0) Text("一次性 ${Billing.fmt(t.once, cur)}",
                            fontSize = 10.5.sp, color = p.ink4,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .background(if (on) p.ink else Color.Transparent)
            .border(1.dp, if (on) p.ink else p.hair)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, color = if (on) p.paper else p.ink3,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun SubRow(
    sub: Subscription,
    cur: String,
    rate: Double,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    val cyc = Cycle.from(sub.cycle)
    val once = cyc == Cycle.ONCE
    val days = Billing.daysLeft(sub)
    val due = Billing.nextDue(sub)
    val alpha = if (sub.enabled) 1f else 0.45f

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(alpha)) { BrandMark(sub.name, 30) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = p.ink.copy(alpha = alpha), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(cyc.label, if (once) p.chart[4] else p.ink3)
                    if (sub.plan.isNotBlank()) Text(sub.plan, fontSize = 10.5.sp,
                        color = p.ink4, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (sub.qty > 1) Tag("×${sub.qty}")
                    if (sub.nsfw) Tag("NSFW", p.chart[7])
                    if (!sub.enabled) Tag("已停用", p.ink4)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Billing.fmt2(Billing.amountIn(sub, cur, rate), cur), fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, color = p.ink.copy(alpha = alpha),
                    fontFamily = FontFamily.Monospace)
                Text("${sub.cur} ${trimNum(sub.price)}", fontSize = 10.sp, color = p.ink4,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (once) {
            Text("付费日 ${sub.start}", fontSize = 11.sp, color = p.ink4,
                fontFamily = FontFamily.Monospace)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(70.dp)) {
                    Track(
                        Billing.progress(sub),
                        when { days == null -> p.ink4
                               days <= 2 -> p.bad
                               days <= 7 -> p.warn
                               else -> p.ok },
                        4,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    (due?.toString() ?: "—") + " · " + when {
                        days == null -> "未设置"
                        days == 0L -> "今天扣费"
                        days == 1L -> "明天扣费"
                        days < 0 -> "已过期"
                        else -> "$days 天后"
                    },
                    fontSize = 11.sp, color = p.ink3, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${Billing.fmt(Billing.monthly(sub, cur, rate), cur)}/月",
                    fontSize = 11.sp, color = p.ink3, fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (sub.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row {
                Box(Modifier.width(2.dp).height(14.dp).background(p.hair))
                Spacer(Modifier.width(7.dp))
                Text(sub.note, fontSize = 11.sp, color = p.ink4, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            InkButton("编辑", onEdit)
            InkButton(if (sub.enabled) "停用" else "启用", onToggle)
            InkButton("删除", onDelete, danger = true)
        }
    }
}

/* 数字去掉多余的 .0 */
private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
