package com.cupflow.glasses

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AgentConfig(val baseUrl: String)
data class AgentResult(val outcome: String, val reason: String, val traceId: String?)

/**
 * Thin device adapter. The glasses never receive the model API key: they only
 * submit a camera keyframe to the existing server-side CupFlow APIs.
 */
class AgentClient(private val config: AgentConfig) {
  fun inspect(bitmap: Bitmap): AgentResult {
    val bytes = ByteArrayOutputStream().use { output ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
      output.toByteArray()
    }
    val visionRequest = JSONObject().apply {
      put("image", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
      put("expectedStep", JSONObject().put("id", "cup").put("title", "取杯"))
      put("order", JSONObject().put("id", "A102").put("drink", "云朵乌龙奶茶").put("options", JSONArray(listOf("少糖", "去冰", "加珍珠"))))
      put("mode", "operation")
    }
    val vision = post("/api/vision", visionRequest)
    val agentRequest = JSONObject().apply {
      put("sessionId", "glasses-" + System.currentTimeMillis())
      put("order", visionRequest.getJSONObject("order"))
      put("expectedStep", visionRequest.getJSONObject("expectedStep"))
      put("stepIndex", 0)
      put("observation", vision)
      put("recentEvents", JSONArray())
    }
    val decision = post("/api/agent/decision", agentRequest)
    val detail = decision.getJSONObject("decision")
    return AgentResult(detail.getString("outcome"), detail.getString("reason"), decision.optJSONObject("trace")?.optString("traceId"))
  }

  private fun post(path: String, body: JSONObject): JSONObject {
    val connection = (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 8_000
      readTimeout = 15_000
      setRequestProperty("Content-Type", "application/json")
      doOutput = true
    }
    connection.outputStream.use { it.write(body.toString().toByteArray()) }
    val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
    if (connection.responseCode !in 200..299) throw IllegalStateException("CupFlow 服务错误：$text")
    return JSONObject(text)
  }
}
