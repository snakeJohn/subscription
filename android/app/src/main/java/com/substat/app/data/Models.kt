package com.substat.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与 Worker 的 JSON 契约。
 * D1 用 0/1 存布尔，故用 Int 承接后再暴露 Boolean 属性。
 */
@Serializable
data class Subscription(
    val id: String = "",
    val name: String = "",
    val domain: String = "",
    val cat: String = "ai",
    val plan: String = "",
    val price: Double = 0.0,
    val cur: String = "CNY",
    val cycle: String = "month",
    val qty: Int = 1,
    val start: String = "",
    val note: String = "",
    @SerialName("nsfw") val nsfwFlag: Int = 0,
    @SerialName("enabled") val enabledFlag: Int = 1,
    @SerialName("remind") val remindFlag: Int = 1,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
) {
    val nsfw: Boolean get() = nsfwFlag != 0
    val enabled: Boolean get() = enabledFlag != 0
    val remind: Boolean get() = remindFlag != 0

    fun toPayload() = SubscriptionPayload(
        name = name, domain = domain, cat = cat, plan = plan, price = price, cur = cur,
        cycle = cycle, qty = qty, start = start, note = note,
        nsfw = nsfwFlag, enabled = enabledFlag, remind = remindFlag,
    )
}

/** 写入用负载：布尔以 0/1 提交，与服务端校验器一致 */
@Serializable
data class SubscriptionPayload(
    val name: String,
    val domain: String = "",
    val cat: String = "ai",
    val plan: String = "",
    val price: Double,
    val cur: String = "CNY",
    val cycle: String = "month",
    val qty: Int = 1,
    val start: String,
    val note: String = "",
    val nsfw: Int = 0,
    val enabled: Int = 1,
    val remind: Int = 1,
)

@Serializable data class SubsResponse(val items: List<Subscription> = emptyList())
@Serializable data class CreateResponse(val ok: Boolean = false, val id: String = "")
@Serializable data class OkResponse(val ok: Boolean = false)
@Serializable data class ErrorResponse(val error: String = "")
@Serializable data class AuthStatus(val configured: Boolean = false, val authed: Boolean = false)
@Serializable data class LoginRequest(val password: String)
@Serializable
data class RateResponse(
    val rate: Double? = null,
    val source: String? = null,
    val at: Long = 0,
    val cached: Boolean = false,
    val stale: Boolean = false,
    val failed: Boolean = false,
)
@Serializable
data class SettingsResponse(
    val settings: Map<String, String> = emptyMap(),
    val channels: Map<String, Boolean> = emptyMap(),
)
@Serializable data class BulkRequest(val items: List<SubscriptionPayload>, val mode: String = "merge")
@Serializable
data class BulkResponse(val ok: Boolean = false, val imported: Int = 0, val skipped: Int = 0)
@Serializable
data class NotifyRunResponse(
    val sent: Int = 0,
    val skipped: Int = 0,
    val msg: String? = null,
)

/** 分类，与 public/js/catalog.js 的 CATS 对齐 */
enum class Category(val key: String, val label: String, val en: String, val nsfw: Boolean = false) {
    AI("ai", "AI 会员", "AI"),
    VIDEO("video", "视频流媒体", "Video"),
    MUSIC("music", "音乐音频", "Audio"),
    VPS("vps", "VPS / 云服务", "Cloud"),
    HOST("host", "建站 / 域名", "Hosting"),
    DEV("dev", "开发工具", "Dev"),
    CLOUD("cloud", "网盘 / 办公", "Office"),
    DESIGN("design", "设计创意", "Design"),
    VPN("vpn", "网络 / 安全", "Network"),
    GAME("game", "游戏", "Games"),
    SHOP("shop", "电商会员", "Retail"),
    READ("read", "阅读 / 新闻", "Reading"),
    EDU("edu", "教育学习", "Learning"),
    NSFW("nsfw", "成人内容", "Adult", true);

    companion object {
        fun from(key: String?): Category = entries.firstOrNull { it.key == key } ?: AI
        fun label(key: String?): String = from(key).label
    }
}
