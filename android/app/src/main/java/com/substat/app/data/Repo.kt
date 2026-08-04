package com.substat.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 数据仓库：网络 + 离线缓存。
 * 列表拉取成功即写缓存，失败时回退缓存，保证断网仍能看板。
 */
class Repo(
    private val api: SubStatApi,
    private val store: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs

    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache

    /** 服务库内存缓存：首次拉取后驻留，重开选择器不再走网络 */
    private var catalogCache: List<CatalogItem>? = null

    suspend fun prime() = store.prime()

    suspend fun loadCache() {
        val raw = store.cachedSubs.first() ?: return
        runCatching {
            json.decodeFromString(ListSerializer(Subscription.serializer()), raw)
        }.getOrNull()?.let { _subs.value = it; _fromCache.value = true }
    }

    /** 拉取列表；网络失败时若已有缓存则不抛，标记 fromCache 供 UI 提示 */
    suspend fun refresh(allowCacheFallback: Boolean = true): Result<Unit> = runCatching {
        val r = api.list()
        _subs.value = r.items
        _fromCache.value = false
        store.cacheSubs(json.encodeToString(ListSerializer(Subscription.serializer()), r.items))
    }.recoverCatching { e ->
        if (e is AuthException) throw e
        if (allowCacheFallback && _subs.value.isNotEmpty()) {
            _fromCache.value = true
        } else throw e
    }

    suspend fun status() = api.status()
    suspend fun login(username: String, pw: String) = api.login(username, pw)
    suspend fun register(username: String, pw: String, code: String) = api.register(username, pw, code)
    suspend fun logout() = api.logout()
    suspend fun probe(base: String) = api.probe(base)

    suspend fun create(p: SubscriptionPayload) = api.create(p).also { refresh() }
    suspend fun update(id: String, p: SubscriptionPayload) = api.update(id, p).also { refresh() }
    suspend fun setEnabled(id: String, on: Boolean) = api.setEnabled(id, on).also { refresh() }
    suspend fun delete(id: String) = api.delete(id).also { refresh() }
    suspend fun bulk(items: List<SubscriptionPayload>, mode: String) =
        api.bulk(items, mode).also { refresh() }

    suspend fun settings() = api.settings()
    suspend fun saveSettings(patch: Map<String, String>) = api.saveSettings(patch)
    suspend fun notifyRun() = api.notifyRun()
    suspend fun webdavBackup() = api.webdavBackup()
    suspend fun webdavTest(url: String, user: String, pass: String) =
        api.webdavTest(url, user, pass)

    /** 服务库目录：优先返回内存缓存，未命中才请求网络 */
    suspend fun catalog(): List<CatalogItem> =
        catalogCache ?: api.catalog().items.also { catalogCache = it }

    /** 取汇率并落本地，供离线时继续折算 */
    suspend fun rate(refresh: Boolean): RateResponse {
        val r = api.rate(refresh)
        r.rate?.let { store.setRate(it, r.source ?: "", r.at) }
        return r
    }
}
