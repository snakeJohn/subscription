package com.substat.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.substat.app.MainActivity
import com.substat.app.SubStatApp
import com.substat.app.data.Billing
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 桌面小部件：月度支出 + 最近三笔扣费。
 * 数据取自离线缓存，不发网络请求——小部件刷新频繁，避免流量与耗电。
 */
class SubStatWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as? SubStatApp
        var monthly = "—"
        var items = emptyList<Triple<String, String, Long>>()
        var cur = "CNY"

        if (app != null) {
            runCatching {
                app.repo.prime()
                app.repo.loadCache()
                val prefs = app.store.prefs.first()
                cur = prefs.cur
                val subs = app.repo.subs.value
                val t = Billing.totals(subs, prefs.cur, prefs.rate)
                monthly = Billing.fmt(t.month, prefs.cur)
                val today = LocalDate.now()
                items = Billing.occurrences(subs, 45, prefs.cur, prefs.rate, today)
                    .take(3)
                    .map { occ ->
                        Triple(
                            occ.sub.name,
                            Billing.fmt(occ.amount, prefs.cur),
                            ChronoUnit.DAYS.between(today, occ.date),
                        )
                    }
            }
        }

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(Color(0xFFF2EFE9))
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Text(
                        "每月固定支出",
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFFD8412F)),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        monthly,
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFF14110F)),
                            fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    if (items.isEmpty()) {
                        Text(
                            "近期无账单",
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(Color(0xFF6E665E)),
                                fontSize = 11.sp,
                            ),
                        )
                    } else {
                        items.forEach { (name, amt, days) ->
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    name.take(10),
                                    style = TextStyle(
                                        color = androidx.glance.unit.ColorProvider(Color(0xFF3D3833)),
                                        fontSize = 11.sp,
                                    ),
                                    maxLines = 1,
                                )
                                Spacer(GlanceModifier.defaultWeight())
                                Text(
                                    "$amt · " + when (days) {
                                        0L -> "今天"
                                        1L -> "明天"
                                        else -> "${days}天"
                                    },
                                    style = TextStyle(
                                        color = androidx.glance.unit.ColorProvider(Color(0xFF6E665E)),
                                        fontSize = 10.sp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class SubStatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SubStatWidget()
}
