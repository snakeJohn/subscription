package com.substat.app

import com.substat.app.data.Billing
import com.substat.app.data.Cycle
import com.substat.app.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 与 test/billing.test.mjs 同款用例。
 * 两端实现各一份，靠这里锁住语义——尤其是月末锚点。
 */
class BillingTest {

    private val R = 7.15
    private fun sub(
        price: Double = 0.0, cur: String = "CNY", cycle: String = "month",
        qty: Int = 1, start: String = "2026-01-01", enabled: Int = 1,
    ) = Subscription(
        id = "t", name = "T", price = price, cur = cur, cycle = cycle, qty = qty,
        start = start, enabledFlag = enabled,
    )
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // ——— 周期归一 ———
    @Test fun cycleNormalization() {
        assertEquals(1200.0, Billing.yearly(sub(1200.0, cycle = "year"), "CNY", R), 0.01)
        assertEquals(1199.3, Billing.yearly(sub(100.0, cycle = "month"), "CNY", R), 1.0)
        assertEquals(365.0, Billing.yearly(sub(1.0, cycle = "day"), "CNY", R), 0.01)
        assertEquals(365.0, Billing.yearly(sub(7.0, cycle = "week"), "CNY", R), 0.01)
        assertEquals(1199.3, Billing.yearly(sub(300.0, cycle = "quarter"), "CNY", R), 1.0)
        assertEquals(1199.3, Billing.yearly(sub(600.0, cycle = "half"), "CNY", R), 1.0)
        assertEquals(0.0, Billing.yearly(sub(999.0, cycle = "once"), "CNY", R), 0.001)
        assertEquals(100.0, Billing.monthly(sub(1200.0, cycle = "year"), "CNY", R), 0.01)
        assertEquals(1.0, Billing.daily(sub(365.0, cycle = "year"), "CNY", R), 0.01)
        assertEquals(300.0, Billing.yearly(sub(100.0, cycle = "year", qty = 3), "CNY", R), 0.01)
    }

    // ——— 汇率 ———
    @Test fun conversion() {
        assertEquals(715.0, Billing.conv(100.0, "USD", "CNY", R), 0.01)
        assertEquals(100.0, Billing.conv(715.0, "CNY", "USD", R), 0.01)
        assertEquals(50.0, Billing.conv(50.0, "USD", "USD", R), 0.01)
        assertEquals(37.0, Billing.conv(Billing.conv(37.0, "CNY", "USD", R), "USD", "CNY", R), 0.01)
        assertEquals(7.15, Billing.conv(1.0, "USD", "CNY", 0.0), 0.01)
        assertEquals(0.0, Billing.conv(Double.NaN, "USD", "CNY", R), 0.01)
    }

    // ——— 汇总 ———
    @Test fun totals() {
        val list = listOf(
            sub(20.0, "USD", "month"), sub(1200.0, cycle = "year"),
            sub(500.0, cycle = "once"), sub(100.0, cycle = "month", enabled = 0),
        )
        val t = Billing.totals(list, "CNY", R)
        assertEquals(20 * R * (365 / 30.4375) + 1200, t.year, 2.0)
        assertEquals(500.0, t.once, 0.01)
        assertEquals(3, t.count)
        assertEquals(4, t.all)
        assertEquals(t.year / 12, t.month, 0.01)
    }

    // ——— 日期推进 ———
    @Test fun advanceBasics() {
        assertEquals(d(2026, 2, 15), Billing.advance(d(2026, 1, 15), Cycle.MONTH, 1))
        assertEquals(d(2026, 4, 15), Billing.advance(d(2026, 1, 15), Cycle.QUARTER, 1))
        assertEquals(d(2026, 7, 15), Billing.advance(d(2026, 1, 15), Cycle.HALF, 1))
        assertEquals(d(2027, 3, 1), Billing.advance(d(2026, 3, 1), Cycle.YEAR, 1))
        assertEquals(d(2026, 1, 8), Billing.advance(d(2026, 1, 1), Cycle.WEEK, 1))
        assertEquals(d(2026, 1, 2), Billing.advance(d(2026, 1, 1), Cycle.DAY, 1))
        assertEquals(d(2027, 1, 15), Billing.advance(d(2026, 12, 15), Cycle.MONTH, 1))
        assertEquals(d(2026, 5, 9), Billing.advance(d(2026, 5, 9), Cycle.MONTH, 0))
        assertNull(Billing.advance(d(2026, 1, 1), Cycle.ONCE, 1))
    }

    /** 核心回归：月末锚点不得漂移 */
    @Test fun monthEndAnchor() {
        assertEquals(d(2026, 2, 28), Billing.advance(d(2026, 1, 31), Cycle.MONTH, 1))
        assertEquals(d(2026, 3, 31), Billing.advance(d(2026, 1, 31), Cycle.MONTH, 2))
        assertEquals(d(2026, 4, 30), Billing.advance(d(2026, 1, 31), Cycle.MONTH, 3))
        assertEquals(d(2026, 5, 31), Billing.advance(d(2026, 1, 31), Cycle.MONTH, 4))
        assertEquals(d(2026, 3, 30), Billing.advance(d(2026, 1, 30), Cycle.MONTH, 2))
        assertEquals(d(2028, 2, 29), Billing.advance(d(2028, 1, 31), Cycle.MONTH, 1))
        assertEquals(d(2029, 2, 28), Billing.advance(d(2028, 2, 29), Cycle.YEAR, 1))
        assertEquals(d(2026, 6, 30), Billing.advance(d(2026, 3, 31), Cycle.QUARTER, 1))
        assertEquals(d(2027, 2, 28), Billing.advance(d(2026, 8, 31), Cycle.HALF, 1))
    }

    @Test fun nextDueLogic() {
        val today = d(2026, 7, 15)
        assertEquals(today, Billing.nextDue(sub(cycle = "month", start = "2026-07-15"), today))
        assertEquals(d(2026, 8, 1),
            Billing.nextDue(sub(cycle = "month", start = "2026-08-01"), today))
        assertEquals(today, Billing.nextDue(sub(cycle = "day", start = "2026-07-14"), today))
        assertNull(Billing.daysLeft(sub(cycle = "once", start = "2026-07-01"), today))
        assertNull(Billing.nextDue(sub(cycle = "month", start = ""), today))
        assertNull(Billing.nextDue(sub(cycle = "month", start = "不是日期"), today))

        /* 久远起订仍推进到未来，且落在一个周期内 */
        val nd = Billing.nextDue(sub(cycle = "month", start = "2020-01-15"), today)!!
        assertTrue(!nd.isBefore(today))
        assertTrue(java.time.temporal.ChronoUnit.DAYS.between(today, nd) <= 31)

        /* 31 号锚点：落点应为 31 或该月最后一天 */
        val a31 = Billing.nextDue(sub(cycle = "month", start = "2020-01-31"), today)!!
        assertEquals(minOf(31, a31.lengthOfMonth()), a31.dayOfMonth)
    }

    @Test fun progressBounds() {
        val today = d(2026, 7, 15)
        val v = Billing.progress(sub(cycle = "month", start = "2026-06-30"), today)
        assertTrue(v in 0f..1f)
    }

    // ——— 账单展开 ———
    @Test fun occurrencesExpansion() {
        val today = d(2026, 7, 15)
        assertEquals(12, Billing.occurrences(
            listOf(sub(30.0, cycle = "month", start = "2026-07-10")), 365, "CNY", R, today).size)
        assertEquals(31, Billing.occurrences(
            listOf(sub(9.0, cycle = "day", start = "2026-07-13")), 30, "CNY", R, today).size)
        assertEquals(1, Billing.occurrences(
            listOf(sub(99.0, cycle = "once", start = "2026-07-25")), 30, "CNY", R, today).size)
        assertEquals(0, Billing.occurrences(
            listOf(sub(99.0, cycle = "once", start = "2026-07-05")), 30, "CNY", R, today).size)
        assertEquals(0, Billing.occurrences(
            listOf(sub(5.0, cycle = "month", start = "2026-07-12", enabled = 0)),
            60, "CNY", R, today).size)

        /* 展开保持月末锚点，且无重复日期 */
        val o31 = Billing.occurrences(
            listOf(sub(31.0, cycle = "month", start = "2026-01-31")), 200, "CNY", R, today)
        assertTrue(o31.all { it.date.dayOfMonth >= 28 })
        assertEquals(o31.size, o31.map { it.date }.distinct().size)
        assertEquals(o31.sortedBy { it.date }.map { it.date }, o31.map { it.date })
    }

    @Test fun calendarExpansion() {
        val cal31 = listOf(sub(31.0, cycle = "month", start = "2026-01-31"))
        assertEquals(d(2026, 2, 28),
            Billing.monthOccurrences(cal31, 2026, 2, "CNY", R).first().date)
        /* 连续 6 个月每月恰一次 */
        for (i in 0 until 6) {
            val m = LocalDate.of(2026, 7, 1).plusMonths(i.toLong())
            assertEquals(
                "月份 ${m.monthValue} 应恰有一次",
                1, Billing.monthOccurrences(cal31, m.year, m.monthValue, "CNY", R).size,
            )
        }
        /* 起订日之前不展开 */
        val future = listOf(sub(10.0, cycle = "month", start = "2026-10-15"))
        assertEquals(0, Billing.monthOccurrences(future, 2026, 8, "CNY", R).size)
        assertEquals(1, Billing.monthOccurrences(future, 2026, 10, "CNY", R).size)
    }

    // ——— 格式化 ———
    @Test fun formatting() {
        assertEquals("¥1,235", Billing.fmt(1234.5, "CNY"))
        assertEquals("$9.99", Billing.fmt(9.99, "USD"))
        assertEquals("¥0", Billing.fmt(0.0, "CNY"))
        assertEquals("¥1,234.50", Billing.fmt2(1234.5, "CNY"))
        assertEquals("¥2.3w", Billing.fmtK(23456.0, "CNY"))
        assertEquals("¥2.3k", Billing.fmtK(2345.0, "CNY"))
    }
}
