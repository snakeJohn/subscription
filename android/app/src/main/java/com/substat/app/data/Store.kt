package com.substat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("substat")

/**
 * 本地偏好与会话。
 * 注意 Cookie 也存在这里：Ktor 侧没有共享 CookieJar，登录态需自己持久化，
 * 否则每次冷启动都要重新输密码。
 */
class SettingsStore(private val ctx: Context) {

    private object K {
        val baseUrl = stringPreferencesKey("base_url")
        val cookie = stringPreferencesKey("cookie")
        val cur = stringPreferencesKey("cur")
        val rate = doublePreferencesKey("rate")
        val rateSource = stringPreferencesKey("rate_source")
        val rateAt = androidx.datastore.preferences.core.longPreferencesKey("rate_at")
        val theme = stringPreferencesKey("theme")
        val warnDays = intPreferencesKey("warn_days")
        val showNsfw = booleanPreferencesKey("show_nsfw")
        val localNotify = booleanPreferencesKey("local_notify")
        val notifyHour = intPreferencesKey("notify_hour")
        val cache = stringPreferencesKey("cache_subs")
    }

    val prefs: Flow<Prefs> = ctx.dataStore.data.map { p ->
        Prefs(
            baseUrl = p[K.baseUrl] ?: "",
            cur = p[K.cur] ?: "CNY",
            rate = p[K.rate] ?: 7.15,
            rateSource = p[K.rateSource] ?: "",
            rateAt = p[K.rateAt] ?: 0L,
            theme = p[K.theme] ?: "system",
            warnDays = p[K.warnDays] ?: 7,
            showNsfw = p[K.showNsfw] ?: false,
            localNotify = p[K.localNotify] ?: true,
            notifyHour = p[K.notifyHour] ?: 9,
        )
    }

    /** 同步读取，供 Api 的 cookieProvider 使用 */
    @Volatile var cookieCache: String? = null
        private set
    @Volatile var baseUrlCache: String = ""
        private set

    /** 冷启动时预热同步缓存，必须在首个 API 调用前 await */
    suspend fun prime() {
        val p = ctx.dataStore.data.first()
        cookieCache = p[K.cookie]
        baseUrlCache = p[K.baseUrl] ?: ""
    }

    suspend fun setBaseUrl(v: String) {
        baseUrlCache = v
        ctx.dataStore.edit { it[K.baseUrl] = v }
    }
    suspend fun setCookie(v: String?) {
        cookieCache = v
        ctx.dataStore.edit { p -> if (v == null) p.remove(K.cookie) else p[K.cookie] = v }
    }
    suspend fun setCur(v: String) = ctx.dataStore.edit { it[K.cur] = v }.let {}
    suspend fun setRate(rate: Double, source: String, at: Long) {
        ctx.dataStore.edit { it[K.rate] = rate; it[K.rateSource] = source; it[K.rateAt] = at }
    }
    suspend fun setTheme(v: String) = ctx.dataStore.edit { it[K.theme] = v }.let {}
    suspend fun setWarnDays(v: Int) = ctx.dataStore.edit { it[K.warnDays] = v }.let {}
    suspend fun setShowNsfw(v: Boolean) = ctx.dataStore.edit { it[K.showNsfw] = v }.let {}
    suspend fun setLocalNotify(v: Boolean) = ctx.dataStore.edit { it[K.localNotify] = v }.let {}
    suspend fun setNotifyHour(v: Int) = ctx.dataStore.edit { it[K.notifyHour] = v }.let {}

    /** 离线缓存：断网时看板仍可展示上次数据 */
    suspend fun cacheSubs(raw: String) = ctx.dataStore.edit { it[K.cache] = raw }.let {}
    val cachedSubs: Flow<String?> = ctx.dataStore.data.map { it[K.cache] }
}

data class Prefs(
    val baseUrl: String = "",
    val cur: String = "CNY",
    val rate: Double = 7.15,
    val rateSource: String = "",
    val rateAt: Long = 0L,
    val theme: String = "system",
    val warnDays: Int = 7,
    val showNsfw: Boolean = false,
    val localNotify: Boolean = true,
    val notifyHour: Int = 9,
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
}
