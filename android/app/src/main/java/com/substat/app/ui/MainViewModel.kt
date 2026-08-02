package com.substat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.substat.app.SubStatApp
import com.substat.app.data.AuthException
import com.substat.app.data.Billing
import com.substat.app.data.Cycle
import com.substat.app.data.Prefs
import com.substat.app.data.Repo
import com.substat.app.data.SettingsStore
import com.substat.app.data.Subscription
import com.substat.app.data.SubscriptionPayload
import com.substat.app.work.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Phase { Booting, Setup, Login, Ready }
enum class Tab { Dash, List, Calendar, Settings }
enum class SortKey { Due, Year, Name, Price }

data class UiState(
    val phase: Phase = Phase.Booting,
    val prefs: Prefs = Prefs(),
    val subs: List<Subscription> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val fromCache: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    val tab: Tab = Tab.Dash,
    val query: String = "",
    val catFilter: String? = null,
    val cycleFilter: String? = null,
    val showDisabled: Boolean = false,
    val sortKey: SortKey = SortKey.Due,
    val sortDesc: Boolean = false,
    val calYear: Int = LocalDate.now().year,
    val calMonth: Int = LocalDate.now().monthValue,
    val setupError: String? = null,
    val loginError: String? = null,
    val busy: Boolean = false,
    /** 服务端设置原文（含 webdav_* 等，敏感值为掩码），进设置页时拉取 */
    val serverSt: Map<String, String> = emptyMap(),
)

class MainViewModel(
    private val repo: Repo,
    private val store: SettingsStore,
    private val scheduler: ReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            repo.prime()
            repo.loadCache()
            /* 偏好与订阅列表合流，任一变化都刷新 UI */
            launch {
                combine(store.prefs, repo.subs, repo.fromCache) { p, s, c -> Triple(p, s, c) }
                    .collect { (p, s, c) ->
                        _state.value = _state.value.copy(prefs = p, subs = s, fromCache = c)
                    }
            }
            bootstrap()
        }
    }

    /** 冷启动流程：无地址 → 配置页；有地址则校验会话 */
    private suspend fun bootstrap() {
        val base = store.baseUrlCache
        if (base.isBlank()) {
            _state.value = _state.value.copy(phase = Phase.Setup)
            return
        }
        _state.value = _state.value.copy(loading = true)
        try {
            val st = repo.status()
            if (!st.authed) {
                _state.value = _state.value.copy(phase = Phase.Login, loading = false)
                return
            }
            enterApp()
        } catch (e: Exception) {
            /* 网络不可达但有缓存时，仍进入应用并提示离线 */
            if (repo.subs.value.isNotEmpty()) {
                _state.value = _state.value.copy(
                    phase = Phase.Ready, loading = false, fromCache = true,
                    toast = "离线模式：显示上次同步的数据",
                )
            } else {
                _state.value = _state.value.copy(
                    phase = Phase.Login, loading = false,
                    loginError = "无法连接服务器：${e.message}",
                )
            }
        }
    }

    private suspend fun enterApp() {
        _state.value = _state.value.copy(phase = Phase.Ready, loading = false)
        repo.refresh().onFailure { report(it) }
        runCatching { repo.rate(false) }
        scheduler.reschedule()
    }

    // ——— 配置 / 登录 ———

    fun saveBaseUrl(raw: String) = viewModelScope.launch {
        var url = raw.trim().removeSuffix("/")
        if (url.isEmpty()) {
            _state.value = _state.value.copy(setupError = "请填写服务器地址")
            return@launch
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        _state.value = _state.value.copy(busy = true, setupError = null)
        val ok = repo.probe(url)
        if (!ok) {
            _state.value = _state.value.copy(
                busy = false,
                setupError = "无法访问该地址的 /api/health，请检查是否为 SubStat 服务端",
            )
            return@launch
        }
        store.setBaseUrl(url)
        _state.value = _state.value.copy(busy = false)
        bootstrap()
    }

    fun login(pw: String) = viewModelScope.launch {
        if (pw.isBlank()) {
            _state.value = _state.value.copy(loginError = "请输入密码")
            return@launch
        }
        _state.value = _state.value.copy(busy = true, loginError = null)
        try {
            repo.login(pw)
            _state.value = _state.value.copy(busy = false)
            enterApp()
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, loginError = e.message ?: "登录失败")
        }
    }

    fun logout() = viewModelScope.launch {
        runCatching { repo.logout() }
        _state.value = _state.value.copy(phase = Phase.Login, subs = emptyList())
    }

    fun resetServer() = viewModelScope.launch {
        runCatching { repo.logout() }
        store.setBaseUrl("")
        _state.value = _state.value.copy(phase = Phase.Setup)
    }

    // ——— 数据操作 ———

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(refreshing = true)
        repo.refresh().onFailure { report(it) }
        runCatching { repo.rate(false) }
        _state.value = _state.value.copy(refreshing = false)
    }

    fun save(payload: SubscriptionPayload, id: String?) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        try {
            if (id == null) repo.create(payload) else repo.update(id, payload)
            _state.value = _state.value.copy(
                busy = false, toast = if (id == null) "已添加「${payload.name}」" else "已保存修改",
            )
            scheduler.reschedule()
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            report(e)
        }
    }

    fun toggle(sub: Subscription) = viewModelScope.launch {
        try {
            repo.setEnabled(sub.id, !sub.enabled)
            _state.value = _state.value.copy(toast = if (sub.enabled) "已停用" else "已启用")
        } catch (e: Exception) { report(e) }
    }

    fun delete(sub: Subscription) = viewModelScope.launch {
        try {
            repo.delete(sub.id)
            _state.value = _state.value.copy(toast = "已删除「${sub.name}」")
        } catch (e: Exception) { report(e) }
    }

    fun refreshRate() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        try {
            val r = repo.rate(true)
            _state.value = _state.value.copy(
                busy = false,
                toast = r.rate?.let { "汇率已更新 $it（${r.source ?: "缓存"}）" } ?: "获取失败，仍用当前汇率",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            report(e)
        }
    }

    fun runNotifyNow() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        try {
            val r = repo.notifyRun()
            _state.value = _state.value.copy(
                busy = false, toast = r.msg ?: "已推送 ${r.sent} 条，跳过 ${r.skipped} 条",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            report(e)
        }
    }

    /** 触发服务端 WebDAV 备份；地址与凭据在网页版设置中配置 */
    fun backupWebdav() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        try {
            val r = repo.webdavBackup()
            _state.value = _state.value.copy(
                busy = false,
                toast = if (r.ok) "已备份 ${r.count} 条到 WebDAV"
                        else "备份失败：${r.detail ?: "未知错误"}",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            report(e)
        }
    }

    /** 拉取服务端设置原文（webdav_* 等），供设置页展示 */
    fun loadServerSettings() = viewModelScope.launch {
        runCatching { repo.settings() }.onSuccess {
            _state.value = _state.value.copy(serverSt = it.settings)
        }
    }

    /** 保存服务端设置；掩码值（••开头）由服务端忽略不覆盖 */
    fun saveServerSettings(patch: Map<String, String>, msg: String? = null) =
        viewModelScope.launch {
            try {
                repo.saveSettings(patch)
                _state.value = _state.value.copy(
                    serverSt = _state.value.serverSt + patch, toast = msg,
                )
            } catch (e: Exception) { report(e) }
        }

    fun testWebdav(url: String, user: String, pass: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        try {
            val r = repo.webdavTest(url, user, pass)
            _state.value = _state.value.copy(
                busy = false,
                toast = if (r.ok) "WebDAV 连接成功" else "连接失败：${r.detail ?: "未知错误"}",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            report(e)
        }
    }

    // ——— 偏好 ———

    fun setCur(c: String) = viewModelScope.launch {
        store.setCur(c)
        runCatching { repo.saveSettings(mapOf("cur" to c)) }
        scheduler.refreshWidget()
    }
    fun setTheme(t: String) = viewModelScope.launch { store.setTheme(t) }
    fun setWarnDays(d: Int) = viewModelScope.launch { store.setWarnDays(d.coerceIn(1, 90)) }
    fun setShowNsfw(v: Boolean) = viewModelScope.launch { store.setShowNsfw(v) }
    fun setLocalNotify(v: Boolean) = viewModelScope.launch {
        store.setLocalNotify(v)
        scheduler.reschedule(enabled = v)
    }
    fun setNotifyHour(h: Int) = viewModelScope.launch {
        store.setNotifyHour(h.coerceIn(0, 23))
        scheduler.reschedule()
    }

    // ——— UI 状态 ———

    fun selectTab(t: Tab) { _state.value = _state.value.copy(tab = t) }
    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }
    fun setCatFilter(c: String?) { _state.value = _state.value.copy(catFilter = c) }
    fun setCycleFilter(c: String?) { _state.value = _state.value.copy(cycleFilter = c) }
    fun setShowDisabled(v: Boolean) { _state.value = _state.value.copy(showDisabled = v) }
    fun sortBy(k: SortKey) {
        val s = _state.value
        _state.value = if (s.sortKey == k) s.copy(sortDesc = !s.sortDesc)
        else s.copy(sortKey = k, sortDesc = k != SortKey.Due && k != SortKey.Name)
    }
    fun shiftMonth(delta: Int) {
        val s = _state.value
        var y = s.calYear
        var m = s.calMonth + delta
        while (m > 12) { m -= 12; y++ }
        while (m < 1) { m += 12; y-- }
        _state.value = s.copy(calYear = y, calMonth = m)
    }
    fun thisMonth() {
        val now = LocalDate.now()
        _state.value = _state.value.copy(calYear = now.year, calMonth = now.monthValue)
    }
    fun clearToast() { _state.value = _state.value.copy(toast = null, error = null) }

    private fun report(e: Throwable) {
        if (e is AuthException) {
            _state.value = _state.value.copy(phase = Phase.Login, loginError = "会话已过期，请重新登录")
        } else {
            _state.value = _state.value.copy(error = e.message ?: "操作失败")
        }
    }

    /** 供列表页使用的筛选 + 排序结果 */
    fun visibleSubs(s: UiState): List<Subscription> {
        val q = s.query.trim().lowercase()
        val list = s.subs.filter { sub ->
            if (!s.prefs.showNsfw && sub.nsfw) return@filter false
            if (!s.showDisabled && !sub.enabled) return@filter false
            s.catFilter?.let { if (sub.cat != it) return@filter false }
            s.cycleFilter?.let { if (sub.cycle != it) return@filter false }
            if (q.isNotEmpty()) {
                val hay = "${sub.name} ${sub.plan} ${sub.note} ${sub.domain}".lowercase()
                if (!hay.contains(q)) return@filter false
            }
            true
        }
        val cur = s.prefs.cur
        val rate = s.prefs.rate
        val cmp = when (s.sortKey) {
            SortKey.Name -> compareBy<Subscription> { it.name }
            SortKey.Price -> compareBy { Billing.amountIn(it, cur, rate) }
            SortKey.Year -> compareBy {
                if (Cycle.from(it.cycle) == Cycle.ONCE) Billing.amountIn(it, cur, rate)
                else Billing.yearly(it, cur, rate)
            }
            SortKey.Due -> compareBy { Billing.daysLeft(it) ?: Long.MAX_VALUE }
        }
        val sorted = list.sortedWith(cmp)
        return if (s.sortDesc) sorted.reversed() else sorted
    }

    companion object {
        fun factory(app: SubStatApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app.repo, app.store, ReminderScheduler(app)) as T
        }
    }
}
