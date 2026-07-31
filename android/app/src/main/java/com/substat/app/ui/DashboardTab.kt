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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.Billing
import com.substat.app.data.Category
import com.substat.app.data.Cycle
import com.substat.app.data.Subscription
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun DashboardTab(vm: MainViewModel, ui: UiState, onOpen: (Subscription) -> Unit) {
    val p = LocalPalette.current
    val cur = ui.prefs.cur
    val rate = ui.prefs.rate
    val other = if (cur == "CNY") "USD" else "CNY"
    val t = Billing.totals(ui.subs, cur, rate)
    val tOther = Billing.totals(ui.subs, other, rate)
    val today = LocalDate.now()
    val soon = Billing.occurrences(ui.subs, ui.prefs.warnDays.toLong(), cur, rate, today)

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = 90.dp,
        ),
    ) {
        // ——— 主数字 ———
        item {
            Kicker("每月固定支出")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Billing.fmt(t.month, cur),
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 52.sp, letterSpacing = (-2).sp, color = p.ink,
                )
                Text("/月", fontFamily = FontFamily.Serif, fontSize = 20.sp,
                    color = p.ink3, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "约 ${Billing.fmt(tOther.month, other)}    生效 ${t.count} 项" +
                    if (t.all > t.count) "（共 ${t.all}）" else "",
                fontSize = 12.sp, color = p.ink3, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
        }

        // ——— 四项统计 ———
        item {
            StatRow("年度支出", Billing.fmt(t.year, cur))
            StatRow("日均成本", Billing.fmt(t.day, cur))
            StatRow("一次性投入", Billing.fmt(t.once, cur))
            StatRow(
                "近期扣费", Billing.fmt(soon.sumOf { it.amount }, cur),
                sub = "${ui.prefs.warnDays} 天内 ${soon.size} 笔",
                valueColor = if (soon.isNotEmpty()) p.red else null,
                last = true,
            )
            Spacer(Modifier.height(22.dp))
        }

        // ——— 分类结构 ———
        item {
            SectionHeader("分类支出结构", "年度等效")
            val byCat = ui.subs.filter { it.enabled }
                .groupBy { it.cat }
                .mapValues { (_, list) ->
                    list.sumOf {
                        if (Cycle.from(it.cycle) == Cycle.ONCE) Billing.amountIn(it, cur, rate)
                        else Billing.yearly(it, cur, rate)
                    }
                }
                .filter { it.value > 0 }
                .toList().sortedByDescending { it.second }
            val sum = byCat.sumOf { it.second }
            if (byCat.isEmpty()) {
                Text("暂无支出数据", fontSize = 12.5.sp, color = p.ink4,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else {
                byCat.forEachIndexed { i, (cat, v) ->
                    val color = p.chart[i % p.chart.size]
                    val pct = if (sum > 0) (v / sum * 100) else 0.0
                    Column(Modifier.padding(bottom = 11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(color))
                            Spacer(Modifier.width(7.dp))
                            Text(Category.label(cat), fontSize = 13.sp, color = p.ink,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(Billing.fmt(v, cur), fontSize = 12.5.sp, color = p.ink,
                                fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(5.dp))
                            Text("%.1f%%".format(pct), fontSize = 10.5.sp, color = p.ink4,
                                fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(3.dp))
                        Track((pct / 100).toFloat(), color, 6)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // ——— 现金流 ———
        item {
            SectionHeader("未来十二个月现金流", "含一次性")
            CashFlow(ui, cur, rate)
            Spacer(Modifier.height(18.dp))
        }

        // ——— 排行 ———
        item {
            SectionHeader("支出排行", "年度前十")
            val rank = ui.subs.filter { it.enabled }
                .map { s ->
                    s to if (Cycle.from(s.cycle) == Cycle.ONCE) Billing.amountIn(s, cur, rate)
                         else Billing.yearly(s, cur, rate)
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(10)
            val max = rank.firstOrNull()?.second ?: 1.0
            if (rank.isEmpty()) {
                Text("暂无支出数据", fontSize = 12.5.sp, color = p.ink4,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else rank.forEachIndexed { i, (sub, v) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(sub) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", fontSize = 11.sp, color = p.ink4,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.width(18.dp))
                    BrandMark(sub.name, 24)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, fontSize = 13.sp, color = p.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Track((v / max).toFloat(), p.ink, 4)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Billing.fmt(v, cur), fontSize = 12.5.sp, color = p.ink,
                            fontFamily = FontFamily.Monospace)
                        Text("${Billing.fmt(v / 12, cur)}/月", fontSize = 10.sp,
                            color = p.ink4, fontFamily = FontFamily.Monospace)
                    }
                }
                if (i < rank.lastIndex) Hairline()
            }
            Spacer(Modifier.height(18.dp))
        }

        // ——— 即将扣费 ———
        item {
            SectionHeader("即将扣费", "最近十二笔")
            val up = Billing.occurrences(ui.subs, 366, cur, rate, today).take(12)
            if (up.isEmpty()) {
                Text("近期没有账单", fontSize = 12.5.sp, color = p.ink4,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else up.forEachIndexed { i, occ ->
                val days = ChronoUnit.DAYS.between(today, occ.date)
                val c = when { days <= 2 -> p.bad; days <= 7 -> p.warn; else -> p.ok }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(occ.sub) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(occ.sub.name, 24)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(occ.sub.name, fontSize = 13.sp, color = p.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            "${occ.date} · ${Cycle.from(occ.sub.cycle).label}" +
                                if (occ.sub.plan.isNotBlank()) " · ${occ.sub.plan}" else "",
                            fontSize = 10.5.sp, color = p.ink4,
                            fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Billing.fmt(occ.amount, cur), fontSize = 12.5.sp, color = p.ink,
                            fontFamily = FontFamily.Monospace)
                        Text(
                            when (days) { 0L -> "今天"; 1L -> "明天"; else -> "$days 天后" },
                            fontSize = 10.sp, color = c, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (i < up.lastIndex) Hairline()
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    last: Boolean = false,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                color = p.ink3, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(value, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp, color = valueColor ?: p.ink)
                if (sub != null) Text(sub, fontSize = 10.sp, color = p.ink4,
                    fontFamily = FontFamily.Monospace)
            }
        }
        if (!last) Hairline()
    }
}

/** 12 个月柱状图，当月标红 */
@Composable
private fun CashFlow(ui: UiState, cur: String, rate: Double) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val occ = Billing.occurrences(ui.subs, 366, cur, rate, today)
    val buckets = (0 until 12).map { i ->
        val d = today.withDayOfMonth(1).plusMonths(i.toLong())
        Triple(d, occ.filter { it.date.year == d.year && it.date.monthValue == d.monthValue }
            .sumOf { it.amount }, i == 0)
    }
    val max = buckets.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column {
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEach { (_, v, isNow) ->
                Box(
                    Modifier
                        .weight(1f)
                        .height((150 * (v / max)).dp.coerceAtLeast(2.dp))
                        .background(if (isNow) p.red else p.ink)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            buckets.forEach { (d, _, isNow) ->
                Text(
                    "${d.monthValue}",
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = if (isNow) p.red else p.ink4,
                    fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
