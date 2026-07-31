package com.substat.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 计费引擎 —— 与 public/shared/billing.js 行为逐条对齐。
 * 两端各一份实现，靠 BillingTest 里的同款用例锁住语义，改动任一端都要同步。
 */
enum class Cycle(val key: String, val label: String, val short: String, val days: Double) {
    ONCE("once", "一次性", "一次", 0.0),
    DAY("day", "日费", "日", 1.0),
    WEEK("week", "周费", "周", 7.0),
    MONTH("month", "月费", "月", 30.4375),
    QUARTER("quarter", "季度", "季", 91.3125),
    HALF("half", "半年", "半年", 182.625),
    YEAR("year", "年费", "年", 365.0);

    /** 日步长（DAY/WEEK）；月步长（MONTH…YEAR）；ONCE 无步进 */
    val stepDays: Int? get() = when (this) { DAY -> 1; WEEK -> 7; else -> null }
    val stepMonths: Int? get() = when (this) {
        MONTH -> 1; QUARTER -> 3; HALF -> 6; YEAR -> 12; else -> null
    }
    val isRecurring: Boolean get() = this != ONCE

    companion object {
        fun from(key: String?): Cycle = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

object Billing {

    // ——— 金额 ———

    fun conv(amount: Double, from: String, to: String, rate: Double): Double {
        if (!amount.isFinite()) return 0.0
        if (from == to) return amount
        val r = if (rate > 0) rate else 7.15
        return if (from == "USD") amount * r else amount / r
    }

    /** 订阅折算到目标币种，含份数 */
    fun amountIn(sub: Subscription, cur: String, rate: Double): Double =
        conv(sub.price * sub.qty.coerceAtLeast(1), sub.cur, cur, rate)

    /** 年度等效；一次性返回 0（单列统计） */
    fun yearly(sub: Subscription, cur: String, rate: Double): Double {
        val c = Cycle.from(sub.cycle)
        if (c.days == 0.0) return 0.0
        return amountIn(sub, cur, rate) * (365.0 / c.days)
    }

    fun monthly(sub: Subscription, cur: String, rate: Double) = yearly(sub, cur, rate) / 12.0
    fun daily(sub: Subscription, cur: String, rate: Double) = yearly(sub, cur, rate) / 365.0

    fun totals(list: List<Subscription>, cur: String, rate: Double): Totals {
        val active = list.filter { it.enabled }
        var y = 0.0
        var once = 0.0
        for (s in active) {
            if (Cycle.from(s.cycle) == Cycle.ONCE) once += amountIn(s, cur, rate)
            else y += yearly(s, cur, rate)
        }
        return Totals(y, y / 12.0, y / 365.0, once, active.size, list.size)
    }

    // ——— 日期 ———

    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        return try { LocalDate.parse(s.take(10)) } catch (e: Exception) { null }
    }

    /**
     * 从锚点 [anchor] 步进 n 个周期。必须一次性从锚点算出：
     * 逐次 advance(上次结果) 会让 1/31 退化成 1/31→2/28→3/28…，正确应为 3/31。
     */
    fun advance(anchor: LocalDate, cycle: Cycle, n: Int): LocalDate? {
        if (!cycle.isRecurring) return null
        if (n == 0) return anchor
        cycle.stepDays?.let { return anchor.plusDays((it.toLong() * n)) }
        val months = cycle.stepMonths ?: return null
        val dom = anchor.dayOfMonth
        val base = anchor.withDayOfMonth(1).plusMonths(months.toLong() * n)
        return base.withDayOfMonth(minOf(dom, base.lengthOfMonth()))
    }

    /** 下次扣费日：以 start 为锚点找第一个 >= from 的周期点 */
    fun nextDue(sub: Subscription, from: LocalDate = LocalDate.now()): LocalDate? {
        val c = Cycle.from(sub.cycle)
        if (!c.isRecurring) return null
        val start = parseDate(sub.start) ?: return null
        if (!start.isBefore(from)) return start
        var k = (ChronoUnit.DAYS.between(start, from) / c.days).toInt().coerceAtLeast(0)
        var cur = advance(start, c, k) ?: return null
        var guard = 0
        while (cur.isBefore(from) && guard++ < 800) {
            k++
            cur = advance(start, c, k) ?: return cur
        }
        guard = 0
        while (k > 0 && guard++ < 800) {
            val prev = advance(start, c, k - 1) ?: break
            if (prev.isBefore(from)) break
            k--
            cur = prev
        }
        return cur
    }

    fun daysLeft(sub: Subscription, from: LocalDate = LocalDate.now()): Long? =
        nextDue(sub, from)?.let { ChronoUnit.DAYS.between(from, it) }

    /** 当前周期已过比例 0~1 */
    fun progress(sub: Subscription, from: LocalDate = LocalDate.now()): Float {
        val c = Cycle.from(sub.cycle)
        if (c.days == 0.0) return 0f
        val due = nextDue(sub, from) ?: return 0f
        val left = ChronoUnit.DAYS.between(from, due)
        val span = c.days.roundToLong().coerceAtLeast(1)
        return ((span - left).toFloat() / span).coerceIn(0f, 1f)
    }

    // ——— 账单展开 ———

    /** from 起 days 天内的全部扣费点 */
    fun occurrences(
        list: List<Subscription>,
        days: Long,
        cur: String,
        rate: Double,
        from: LocalDate = LocalDate.now(),
    ): List<Occurrence> {
        val end = from.plusDays(days)
        val out = ArrayList<Occurrence>()
        for (s in list) {
            if (!s.enabled) continue
            val c = Cycle.from(s.cycle)
            if (!c.isRecurring) {
                val d = parseDate(s.start) ?: continue
                if (!d.isBefore(from) && !d.isAfter(end))
                    out += Occurrence(s, d, amountIn(s, cur, rate))
                continue
            }
            val start = parseDate(s.start) ?: continue
            val first = nextDue(s, from) ?: continue
            val amt = amountIn(s, cur, rate)
            var k = (ChronoUnit.DAYS.between(start, first) / c.days).roundToLong().toInt()
            var d = advance(start, c, k) ?: continue
            var guard = 0
            while (d.isBefore(from) && guard++ < 800) { k++; d = advance(start, c, k) ?: break }
            guard = 0
            while (!d.isAfter(end) && guard++ < 800) {
                out += Occurrence(s, d, amt)
                k++
                d = advance(start, c, k) ?: break
            }
        }
        return out.sortedBy { it.date }
    }

    /** 指定自然月内的扣费点（含已过去的），用于日历 */
    fun monthOccurrences(
        list: List<Subscription>,
        year: Int,
        month: Int,
        cur: String,
        rate: Double,
    ): List<Occurrence> {
        val first = LocalDate.of(year, month, 1)
        val last = first.withDayOfMonth(first.lengthOfMonth())
        val out = ArrayList<Occurrence>()
        for (s in list) {
            if (!s.enabled) continue
            val start = parseDate(s.start) ?: continue
            val amt = amountIn(s, cur, rate)
            val c = Cycle.from(s.cycle)
            if (!c.isRecurring) {
                if (!start.isBefore(first) && !start.isAfter(last))
                    out += Occurrence(s, start, amt)
                continue
            }
            var k = (ChronoUnit.DAYS.between(start, first) / c.days).toInt().coerceAtLeast(0)
            var d = advance(start, c, k) ?: continue
            var guard = 0
            while (d.isBefore(first) && guard++ < 3000) { k++; d = advance(start, c, k) ?: break }
            guard = 0
            while (k > 0 && guard++ < 3000) {
                val prev = advance(start, c, k - 1) ?: break
                if (prev.isBefore(first)) break
                k--
                d = prev
            }
            guard = 0
            while (!d.isAfter(last) && guard++ < 500) {
                if (!d.isBefore(start)) out += Occurrence(s, d, amt)
                k++
                d = advance(start, c, k) ?: break
            }
        }
        return out.sortedBy { it.date }
    }

    // ——— 格式化 ———

    private fun group(v: Long): String {
        val s = abs(v).toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return (if (v < 0) "-" else "") + sb
    }
    private fun symbol(cur: String) = if (cur == "USD") "$" else "¥"

    fun fmt(v: Double, cur: String): String {
        if (abs(v) >= 1000) return symbol(cur) + group(v.roundToLong())
        val r = (v * 100).roundToLong() / 100.0
        val txt = if (r == r.toLong().toDouble()) r.toLong().toString()
                  else r.toString().trimEnd('0').trimEnd('.')
        return symbol(cur) + txt
    }
    /** 固定两位小数。先整体取整到分再拆分，避免 1234.999 被截成 1234.00 */
    fun fmt2(v: Double, cur: String): String {
        val cents = (abs(v) * 100).roundToLong()
        val sign = if (v < 0) "-" else ""
        return sign + symbol(cur) + group(cents / 100) + "." + "%02d".format(cents % 100)
    }
    fun fmtK(v: Double, cur: String): String = when {
        abs(v) >= 10000 -> symbol(cur) + "%.1fw".format(v / 10000)
        abs(v) >= 1000 -> symbol(cur) + "%.1fk".format(v / 1000)
        else -> symbol(cur) + v.roundToLong()
    }
}

data class Totals(
    val year: Double,
    val month: Double,
    val day: Double,
    val once: Double,
    val count: Int,
    val all: Int,
)

data class Occurrence(
    val sub: Subscription,
    val date: LocalDate,
    val amount: Double,
)
