package com.substat.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AuthException : Exception("未登录或会话已过期")
class ApiException(message: String, val status: Int = 0) : Exception(message)

/**
 * Worker API 客户端。
 * 会话是服务端下发的 HttpOnly Cookie，这里手动持久化 Cookie 值——
 * Android 上 WebView 之外没有共享 CookieJar，交给 [SettingsStore] 存。
 */
class SubStatApi(
    private val baseUrlProvider: () -> String,
    private val cookieProvider: () -> String?,
    private val cookieSetter: suspend (String?) -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    private fun url(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isEmpty()) throw ApiException("尚未配置服务器地址")
        return "$base/api$path"
    }

    /** 统一处理状态码与错误体；401 抛 AuthException 供上层跳登录 */
    private suspend inline fun <reified T> handle(resp: HttpResponse): T {
        captureCookie(resp)
        if (resp.status == HttpStatusCode.Unauthorized) throw AuthException()
        if (!resp.status.isSuccess()) {
            val msg = runCatching {
                json.decodeFromString<ErrorResponse>(resp.bodyAsText()).error
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "请求失败 (${resp.status.value})"
            throw ApiException(msg, resp.status.value)
        }
        return resp.body()
    }

    private suspend fun captureCookie(resp: HttpResponse) {
        val raw = resp.headers.getAll("Set-Cookie") ?: return
        val sid = raw.firstOrNull { it.startsWith("substat_sid=") } ?: return
        val value = sid.substringBefore(';')
        /* Max-Age=0 代表服务端要求清除 */
        if (sid.contains("Max-Age=0")) cookieSetter(null)
        else cookieSetter(value)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        cookieProvider()?.let { header("Cookie", it) }
    }

    // ——— 鉴权 ———
    suspend fun status(): AuthStatus = handle(client.get(url("/auth/status")) { auth() })

    suspend fun login(password: String): OkResponse = handle(
        client.post(url("/auth/login")) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password))
        }
    )

    suspend fun logout(): OkResponse {
        /* 显式声明返回类型，避免 .also 链让 handle<T> 的 T 无法推断 */
        val r: OkResponse = handle(client.post(url("/auth/logout")) { auth() })
        cookieSetter(null)
        return r
    }

    // ——— 订阅 ———
    suspend fun list(): SubsResponse = handle(client.get(url("/subscriptions")) { auth() })

    suspend fun create(payload: SubscriptionPayload): CreateResponse = handle(
        client.post(url("/subscriptions")) {
            auth(); contentType(ContentType.Application.Json); setBody(payload)
        }
    )

    suspend fun update(id: String, payload: SubscriptionPayload): OkResponse = handle(
        client.put(url("/subscriptions/$id")) {
            auth(); contentType(ContentType.Application.Json); setBody(payload)
        }
    )

    /** 只改启用状态，走 PATCH 让服务端合并其余字段 */
    suspend fun setEnabled(id: String, enabled: Boolean): OkResponse = handle(
        client.patch(url("/subscriptions/$id")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(mapOf("enabled" to if (enabled) 1 else 0))
        }
    )

    suspend fun delete(id: String): OkResponse = handle(
        client.delete(url("/subscriptions/$id")) { auth() }
    )

    suspend fun bulk(items: List<SubscriptionPayload>, mode: String): BulkResponse = handle(
        client.post(url("/subscriptions/bulk")) {
            auth(); contentType(ContentType.Application.Json); setBody(BulkRequest(items, mode))
        }
    )

    // ——— 设置 / 汇率 / 提醒 ———
    suspend fun settings(): SettingsResponse = handle(client.get(url("/settings")) { auth() })

    suspend fun saveSettings(patch: Map<String, String>): OkResponse = handle(
        client.put(url("/settings")) {
            auth(); contentType(ContentType.Application.Json); setBody(patch)
        }
    )

    suspend fun rate(refresh: Boolean = false): RateResponse = handle(
        client.get(url("/rate" + if (refresh) "?refresh=1" else "")) { auth() }
    )

    suspend fun notifyRun(): NotifyRunResponse = handle(
        client.post(url("/notify/run")) {
            auth(); contentType(ContentType.Application.Json); setBody(emptyMap<String, String>())
        }
    )

    /** 探测地址是否为 SubStat 服务端，用于首次配置校验 */
    suspend fun probe(base: String): Boolean = runCatching {
        val resp = client.get("${base.trimEnd('/')}/api/health")
        resp.status.isSuccess() && resp.bodyAsText().contains("\"ok\"")
    }.getOrDefault(false)
}

private fun HttpStatusCode.isSuccess() = value in 200..299
