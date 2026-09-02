package com.cupflow.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class ExceptionManagementActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    private fun buildView(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 26, 24, 36)
            setBackgroundColor(0xfff8f6ef.toInt())
        }
        body.addView(text("异常管理", 26f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("异常会保存到 Download/CupFlow；每条记录包含前后 2 秒关键帧。", 13f, 0xff71817b.toInt()))
        val records = loadRecords()
        if (records.isEmpty()) body.addView(text("暂未发现异常记录。", 16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        records.forEach { body.addView(recordCard(it)) }
        return ScrollView(this).apply { addView(body) }
    }

    private fun loadRecords(): List<JSONObject> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        return contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, "date_modified DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) != "record.json") continue
                    val path = cursor.getString(pathColumn).orEmpty()
                    if (!path.startsWith("Download/CupFlow/异常_")) continue
                    val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(cursor.getLong(idColumn).toString()).build()
                    runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) } }.getOrNull()?.let(::add)
                }
            }
        }.orEmpty()
    }

    private fun recordCard(record: JSONObject): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 18, 18, 18)
        background = GradientDrawable().apply { setColor(0xfffff7f0.toInt()); setStroke(2, 0xffefbdad.toInt()); cornerRadius = 18f }
        addView(text("${record.optString("event", "异常")} · ${record.optString("orderId")}", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text(record.optString("drink"), 15f))
        addView(text("操作员：${record.optString("glassesName")}  ·  置信度：${(record.optDouble("confidence") * 100).toInt()}%", 12f, 0xff8c6e5d.toInt()))
        addView(text(record.optString("reason"), 13f, 0xff8c6e5d.toInt()))
        addView(text("关键帧：${record.optInt("frameCount")} 张", 12f, 0xff8c6e5d.toInt()))
    }

    private fun text(value: String, size: Float, color: Int = 0xff10241f.toInt()) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setLineSpacing(4f, 1f) }
}
