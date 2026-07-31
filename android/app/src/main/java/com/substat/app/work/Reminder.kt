package com.substat.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import com.substat.app.MainActivity
import com.substat.app.SubStatApp
import com.substat.app.data.Billing
import com.substat.app.data.Cycle
import com.substat.app.widget.SubStatWidget
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 本地到期提醒。
 * 与服务端 Cron 提醒互补：服务端负责 Bark/TG 等外部渠道，
 * 这里负责系统通知栏，即使没配置任何推送渠道也能收到提醒。
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SubStatApp ?: return Result.success()
        val prefs = app.store.prefs.first()
        if (!prefs.localNotify) return Result.success()

        /* 先尝试同步最新数据；失败则用缓存，宁可提醒基于旧数据也不静默 */
        app.repo.prime()
        runCatching { app.repo.refresh() }.onFailure { app.repo.loadCache() }

        val subs = app.repo.subs.value.filter { it.enabled && it.remind }
        if (subs.isEmpty()) return Result.success()

        val today = LocalDate.now()
        val window = prefs.warnDays.toLong().coerceIn(0, 60)
        val hits = subs.mapNotNull { sub ->
            val due = Billing.nextDue(sub, today) ?: return@mapNotNull null
            val left = ChronoUnit.DAYS.between(today, due)
            /* 只在窗口边界与当天各提醒一次，避免天天重复 */
            if (left == window || left == 0L) Triple(sub, due, left) else null
        }
        if (hits.isEmpty()) {
            SubStatWidget().updateAll(applicationContext)
            return Result.success()
        }

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return Result.success()

        val nm = NotificationManagerCompat.from(applicationContext)
        val cur = prefs.cur
        val rate = prefs.rate
        val pi = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        if (hits.size == 1) {
            val (sub, due, left) = hits[0]
            val amt = Billing.amountIn(sub, cur, rate)
            val when_ = if (left == 0L) "今天" else "$left 天后"
            nm.notify(
                sub.id.hashCode(),
                NotificationCompat.Builder(applicationContext, SubStatApp.CHANNEL_DUE)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("${sub.name} 即将扣费 ${Billing.fmt(amt, cur)}")
                    .setContentText("$when_ $due · ${Cycle.from(sub.cycle).label}")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        } else {
            val sum = hits.sumOf { Billing.amountIn(it.first, cur, rate) }
            val lines = hits.joinToString("\n") { (sub, due, left) ->
                val w = if (left == 0L) "今天" else "$left 天后"
                "· ${sub.name} — ${Billing.fmt(Billing.amountIn(sub, cur, rate), cur)}（$w $due）"
            }
            nm.notify(
                NOTIFY_GROUP_ID,
                NotificationCompat.Builder(applicationContext, SubStatApp.CHANNEL_DUE)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("${hits.size} 笔订阅即将扣费 合计 ${Billing.fmt(sum, cur)}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        SubStatWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "substat-reminder"
        private const val NOTIFY_GROUP_ID = 424242
    }
}

/** 调度器：把 Worker 排到每天指定小时执行 */
class ReminderScheduler(private val app: SubStatApp) {

    suspend fun reschedule(enabled: Boolean? = null) {
        val prefs = app.store.prefs.first()
        val on = enabled ?: prefs.localNotify
        val wm = WorkManager.getInstance(app)
        if (!on) {
            wm.cancelUniqueWork(ReminderWorker.NAME)
            return
        }
        val delay = initialDelay(prefs.notifyHour)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofDays(1))
            .setInitialDelay(delay)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            ReminderWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    /** 距下一个「今天/明天 hour:05」的间隔；错开整点避免与系统任务挤在一起 */
    private fun initialDelay(hour: Int): Duration {
        val now = LocalDateTime.now()
        var target = now.with(LocalTime.of(hour.coerceIn(0, 23), 5))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }

    suspend fun refreshWidget() {
        runCatching { SubStatWidget().updateAll(app) }
    }
}
