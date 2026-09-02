package com.cupflow.companion

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DecisionLogActivity : Activity() {
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
        body.addView(text("识别决策日志", 26f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("仅保存在本机，最多保留最近 100 条判断。", 13f, 0xff71817b.toInt()))
        val logs = DecisionLogStore(this).load()
        if (logs.isEmpty()) body.addView(text("暂未产生识别记录。", 16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        logs.forEach { body.addView(logCard(it)) }
        return ScrollView(this).apply { addView(body) }
    }

    private fun logCard(entry: JSONObject): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 18, 18, 18)
        background = GradientDrawable().apply { setColor(0xfffffdf8.toInt()); setStroke(2, 0xffd9e2d9.toInt()); cornerRadius = 18f }
        val outcome = entry.optString("outcome")
        addView(text("$outcome · ${entry.optString("orderId", "空闲识别")}", 17f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text("${format(entry.optLong("at"))}  ·  ${entry.optString("step", "杯贴识别")}", 12f, 0xff71817b.toInt()))
        val event = entry.optString("event")
        if (event.isNotBlank()) addView(text("事件：$event  ·  置信度：${(entry.optDouble("confidence") * 100).toInt()}%  ·  来源：${entry.optString("source", "direct")}", 13f))
        entry.optLong("durationMs").takeIf { it >= 0 }?.let { addView(text("视觉耗时：${it}ms", 12f, 0xff71817b.toInt())) }
        addView(text(entry.optString("detail"), 13f, 0xff5b6c65.toInt()))
    }

    private fun format(value: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))
    private fun text(value: String, size: Float, color: Int = 0xff10241f.toInt()) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setLineSpacing(4f, 1f) }
}
