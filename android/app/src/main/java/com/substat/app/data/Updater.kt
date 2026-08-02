package com.substat.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 应用自动更新：从 GitHub Release 拉取 latest.json 版本清单，比对后下载并拉起安装。
 * 走公开仓库 Release 资源直链，无需鉴权，与用户自建的 Worker 服务端无关。
 */
class Updater {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }

    /** 拉取版本清单；网络异常或格式错误返回 null（更新是可选功能，不打扰主流程） */
    suspend fun fetchLatest(): AppRelease? = runCatching {
        val text = client.get(MANIFEST_URL).bodyAsText()
        json.decodeFromString<AppRelease>(text)
    }.getOrNull()

    /**
     * 下载 APK 到 cacheDir/updates/substat-latest.apk，边下边回报进度（0f..1f）。
     * 内容长度未知时进度回报 -1f。返回落地文件，失败抛异常。
     */
    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "substat-latest.apk")
        if (out.exists()) out.delete()

        client.prepareGet(url).execute { resp ->
            val total = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val channel = resp.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var read = 0L
            out.outputStream().use { fos ->
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
        }
        return out
    }

    /** 拉起系统安装器安装下载好的 APK */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** 8.0+ 需用户为本应用授予「安装未知应用」权限 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    companion object {
        /** 滚动 Release 上的固定直链，CI 每次构建覆盖同名资源 */
        const val MANIFEST_URL =
            "https://github.com/snakeJohn/subscription/releases/download/android-latest/latest.json"
    }
}
