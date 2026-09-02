package com.cupflow.companion

import android.content.Context
import android.net.Uri

class VisionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("cupflow_vision", Context.MODE_PRIVATE)

    fun endpoint(): String = preferences.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

    fun speechEndpoint(): String = endpoint().let { endpoint ->
        if (endpoint.endsWith("/api/vision")) endpoint.removeSuffix("vision") + "speech" else "$endpoint/api/speech"
    }

    fun label(): String = if (endpoint() == DEFAULT_ENDPOINT) "USB 本地调试" else "HTTPS 服务"

    /** Returns an error message when the address is invalid; otherwise persists it. */
    fun save(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        val uri = runCatching { Uri.parse(value) }.getOrNull()
            ?: return "地址格式无效"
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return "地址必须包含 http:// 或 https://"
        val isLocalUsb = scheme == "http" && host in setOf("127.0.0.1", "localhost")
        if (!isLocalUsb && scheme != "https") return "非本机服务必须使用 HTTPS"
        val endpoint = if (uri.path.isNullOrBlank() || uri.path == "/") "$value/api/vision" else value
        preferences.edit().putString(KEY_ENDPOINT, endpoint).apply()
        return null
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:3000/api/vision"
        private const val KEY_ENDPOINT = "endpoint"
    }
}
