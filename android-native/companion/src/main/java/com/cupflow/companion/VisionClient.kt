package com.cupflow.companion

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VisionResult(
    val event: String,
    val confidence: Double,
    val reason: String,
    val source: String,
    val ticket: JSONObject?,
    val traceId: String = "",
    val durationMs: Long? = null,
)

/** Calls the configured CupFlow route. Provider credentials stay outside the APK. */
class VisionClient(private val endpointProvider: () -> String = { VisionSettingsStore.DEFAULT_ENDPOINT }) {
    fun analyze(image: ByteArray, mode: String, order: CupOrder?, expectedStep: String?, gridContext: GridVisionContext? = null): VisionResult {
        val body = JSONObject().apply {
            put("image", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP))
            put("mode", mode)
            if (order != null) {
                put("order", JSONObject().apply {
                    put("id", order.id)
                    put("drink", order.drink)
                    put("options", JSONArray(order.options))
                })
            }
            if (expectedStep != null) put("expectedStep", JSONObject().put("title", expectedStep))
            gridContext?.let { context ->
                put("gridContext", JSONObject().apply {
                    put("expectedIngredient", context.expectedIngredient)
                    put("grids", JSONArray(context.grids.map { grid ->
                        JSONObject().apply {
                            put("name", grid.name)
                            put("rows", grid.rows)
                            put("columns", grid.columns)
                            put("cells", JSONArray(grid.cells))
                        }
                    }))
                    context.referenceImage?.let { put("referenceImage", it) }
                })
            }
        }
        val connection = (URL(endpointProvider()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) throw IllegalStateException(JSONObject(response).optString("message", "视觉服务不可用"))
        val json = JSONObject(response)
        val meta = json.optJSONObject("meta")
        return VisionResult(
            event = json.optString("event", "unknown"),
            confidence = json.optDouble("confidence", 0.0),
            reason = json.optString("reason", ""),
            source = json.optString("source", "direct"),
            ticket = json.optJSONObject("ticket"),
            traceId = meta?.optString("traceId").orEmpty(),
            durationMs = meta?.takeIf { it.has("durationMs") }?.optLong("durationMs"),
        )
    }
}
