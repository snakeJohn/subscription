package com.substat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.substat.app.data.Billing
import com.substat.app.data.Cycle
import com.substat.app.data.Occurrence
import com.substat.app.data.Subscription
import java.time.LocalDate

@Composable
fun CalendarTab(vm: MainViewModel, ui: UiState, onOpen: (Subscription) -> Unit) {
    val p = LocalPalette.current
    val cur = ui.prefs.cur
    val occ = Billing.monthOccurrences(ui.subs, ui.calYear, ui.calMonth, cur, ui.prefs.rate)
    val byDay = occ.groupBy { it.date.dayOfMonth }
    val first = LocalDate.of(ui.calYear, ui.calMonth, 1)
    val dim = first.lengthOfMonth()
    /* 周一为一周起始：ISO dayOfWeek 1=周一 */
    val lead = first.dayOfWeek.value - 1
    val today = LocalDate.now()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 80.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shiftMonth(-1) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一月", tint = p.ink)
                }
                Text(
                    "${ui.calYear} 年 ${ui.calMonth} 月",
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp, color = p.ink,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = { vm.shiftMonth(1) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一月", tint = p.ink)
                }
                Spacer(Modifier.weight(1f))
                InkButton("本月", { vm.thisMonth() })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${occ.size} 笔 · ${Billing.fmt(occ.sumOf { it.amount }, cur)}",
                fontSize = 11.5.sp, color = p.ink3, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(10.dp))
            /* 星期表头 */
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                    Text(d, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = p.ink3,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink))
        }

        /* 逐周渲染，每格显示当日账单 */
        val cells = lead + dim
        val weeks = (cells + 6) / 7
        items(weeks) { w ->
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
                for (i in 0 until 7) {
                    val idx = w * 7 + i
                    val day = idx - lead + 1
                    if (day in 1..dim) {
                        val items = byDay[day].orEmpty()
                        val isToday = today.year == ui.calYear &&
                            today.monthValue == ui.calMonth && today.dayOfMonth == day
                        DayCell(day, items, cur, isToday, Modifier.weight(1f), onOpen)
                    } else {
                        Box(Modifier.weight(1f).background(p.paper2.copy(alpha = 0.4f)))
                    }
                }
            }
            Hairline()
        }

        /* 当月账单明细，弥补格子太小看不清 */
        if (occ.isNotEmpty()) item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("本月账单", "${occ.size} 笔")
            occ.forEachIndexed { i, o ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(o.sub) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(o.date.dayOfMonth), fontSize = 12.sp, color = p.ink3,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp))
                    BrandMark(o.sub.name, 22)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.sub.name, fontSize = 13.sp, color = p.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(Cycle.from(o.sub.cycle).label, fontSize = 10.sp, color = p.ink4,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text(Billing.fmt(o.amount, cur), fontSize = 12.5.sp, color = p.ink,
                        fontFamily = FontFamily.Monospace)
                }
                if (i < occ.lastIndex) Hairline()
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    items: List<Occurrence>,
    cur: String,
    isToday: Boolean,
    modifier: Modifier,
    onOpen: (Subscription) -> Unit,
) {
    val p = LocalPalette.current
    val sum = items.sumOf { it.amount }
    Column(
        modifier
            .heightIn(min = 62.dp)
            .then(if (isToday) Modifier.border(2.dp, p.red) else Modifier)
            .padding(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$day", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) p.red else p.ink3)
        }
        if (sum > 0) {
            Text(Billing.fmtK(sum, cur), fontSize = 9.sp, color = p.ink2,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        items.take(2).forEach { o ->
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.paper2)
                    .clickable { onOpen(o.sub) }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(9.dp).background(brandColor(o.sub.name)))
                Spacer(Modifier.width(2.dp))
                Text(o.sub.name, fontSize = 8.sp, color = p.ink2, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (items.size > 2) {
            Text("+${items.size - 2}", fontSize = 8.sp, color = p.ink4,
                fontFamily = FontFamily.Monospace)
        }
    }
}
