package com.cupflow.companion

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CapturedFrame(val at: Long, val bytes: ByteArray)

class EvidenceStore(private val context: Context) {
    fun save(order: CupOrder, glassesName: String, event: String, confidence: Double, reason: String, annotation: String, frames: List<CapturedFrame>) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val folder = "CupFlow/异常_${timestamp}_${safe(order.id)}_${safe(order.drink)}_${safe(glassesName)}"
        val now = System.currentTimeMillis()
        frames.forEachIndexed { index, frame ->
            val offset = String.format(Locale.US, "%+.1fs", (frame.at - now) / 1000.0)
            write("$folder/frame_${index + 1}_$offset.jpg", "image/jpeg", frame.bytes)
        }
        val record = JSONObject().apply {
            put("orderId", order.id)
            put("drink", order.drink)
            put("options", JSONArray(order.options))
            put("glassesName", glassesName)
            put("event", event)
            put("confidence", confidence)
            put("reason", reason)
            put("annotation", annotation)
            put("occurredAt", now)
            put("frameCount", frames.size)
        }
        write("$folder/record.json", "application/json", record.toString(2).toByteArray())
    }

    private fun write(relativeFile: String, mimeType: String, bytes: ByteArray) {
        val slash = relativeFile.lastIndexOf('/')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, relativeFile.substring(slash + 1))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFile.substring(0, slash))
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建异常记录文件")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入异常记录文件")
    }

    private fun safe(value: String) = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
}
