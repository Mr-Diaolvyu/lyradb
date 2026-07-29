package io.github.lexaquila.lyradb.mobile

import java.net.URI
import java.util.Locale

/**
 * 移动外壳服务端地址策略。
 *
 * 用户可以输入 origin 或 origin/api，持久化与 WebView 加载值统一为
 * origin 根路径 /；同源比较仍由调用方基于 scheme/host/effective-port 完成。
 */
object ServerUrlPolicy {
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun canonicalAppUrl(raw: String, allowHttp: Boolean): String? {
        var candidate = raw.trim()
        if (candidate.isEmpty()) return null
        if (!schemePattern.containsMatchIn(candidate)) {
            candidate = "https://$candidate"
        }

        val parsed = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val host = parsed.host?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank() || parsed.rawUserInfo != null ||
                parsed.rawQuery != null || parsed.rawFragment != null) {
            return null
        }
        if (parsed.port == 0 || parsed.port > 65535) return null

        val path = parsed.rawPath.orEmpty()
        if (path != "" && path != "/" && path != "/api" && path != "/api/") {
            return null
        }
        return try {
            URI(scheme, null, host, parsed.port, "/", null, null).toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun appInfoUrl(canonicalAppUrl: String): String? {
        val parsed = try {
            URI(canonicalAppUrl)
        } catch (_: Exception) {
            return null
        }
        return try {
            URI(
                    parsed.scheme,
                    null,
                    parsed.host,
                    parsed.port,
                    "/api/app/info",
                    null,
                    null
            ).toASCIIString()
        } catch (_: Exception) {
            null
        }
    }
}
