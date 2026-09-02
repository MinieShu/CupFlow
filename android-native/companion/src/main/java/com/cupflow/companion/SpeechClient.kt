package com.cupflow.companion

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpeechResult(val transcript: String, val isStart: Boolean)

/** Sends a short WAV sample to CupFlow's server route; credentials remain outside the APK. */
class SpeechClient(private val endpointProvider: () -> String) {
    fun recognize(wav: ByteArray): SpeechResult {
        val body = JSONObject().apply {
            put("audio", "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP))
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
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(JSONObject(response).optString("message", "语音服务不可用"))
        }
        val json = JSONObject(response)
        return SpeechResult(json.optString("transcript", ""), json.optBoolean("isStart", false))
    }
}
