package com.cupflow.glass

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

private data class CupFlowState(
    val step: Int = 0,
    val steps: List<String> = emptyList(),
    val status: String = "等待订单",
    val message: String = "等待店长下发订单。",
    val orderId: String? = null,
    val drink: String? = null,
    val alert: Boolean = false,
)

class MainActivity : Activity() {
    private var state = CupFlowState()
    private lateinit var status: TextView
    private lateinit var currentStep: TextView
    private lateinit var message: TextView
    private lateinit var action: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private lateinit var commandBridge: GlassCommandBridge
    private var voiceRequestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val languageResult = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                pendingSpeech?.let {
                    pendingSpeech = null
                    speak(it)
                }
            }
        }
        window.setBackgroundDrawableResource(R.color.glass_background)
        window.decorView.setBackgroundColor(Color.BLACK)
        commandBridge = GlassCommandBridge(::handlePhoneCommand)
        setContentView(buildView())
        render()
    }

    override fun onDestroy() {
        stopVoiceStart()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::commandBridge.isInitialized) commandBridge.activate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A)) {
            handleActionTap()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(28, 8, 28, 18)
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            requestFocus()
            setOnClickListener { handleActionTap() }
        }
        status = text("", 11f, R.color.glass_muted)
        currentStep = text("", 19f, R.color.glass_text)
        message = text("", 13f, R.color.glass_text)
        action = text("", 14f, R.color.glass_primary).apply {
            isClickable = true
            setPadding(0, 8, 0, 0)
            setOnClickListener { handleActionTap() }
        }
        root.addView(status)
        root.addView(currentStep, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5 })
        root.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4; bottomMargin = 6 })
        root.addView(action)
        return root
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        setLineSpacing(5f, 1f)
    }

    private fun startOrder() {
        if (!state.status.contains("待开始")) return
        stopVoiceStart()
        val firstStep = state.steps.getOrNull(state.step) ?: "等待制作步骤"
        state = state.copy(status = "订单 ${state.orderId} · 制作中", message = "第一步：$firstStep", alert = false)
        render()
        speak("开始制作。第一步，$firstStep。")
        commandBridge.sendEvent("cupflow_started")
    }

    private fun handleActionTap() {
        when {
            state.alert -> returnToErrorStep()
            state.status.contains("待开始") -> startOrder()
            state.status.contains("制作中") -> skipCurrentStep()
        }
    }

    private fun returnToErrorStep() {
        val stepName = state.steps.getOrNull(state.step) ?: "当前步骤"
        state = state.copy(
            status = "订单 ${state.orderId} · 制作中",
            message = "请重新执行：$stepName",
            alert = false,
        )
        render()
        speak("请重新执行，$stepName。")
        commandBridge.sendEvent("cupflow_retry")
    }

    private fun skipCurrentStep() {
        val stepName = state.steps.getOrNull(state.step) ?: return
        state = state.copy(message = "已跳过 $stepName，正在记录异常…", alert = false)
        render()
        speak("已跳过当前步骤，正在记录。")
        commandBridge.sendEvent("cupflow_skip", stepName)
    }

    private fun handlePhoneCommand(values: List<String>) = runOnUiThread {
        when (values.firstOrNull()) {
            "cupflow_order" -> {
                val orderId = values.getOrNull(1).orEmpty()
                val drink = values.getOrNull(2).orEmpty()
                state = CupFlowState(
                    steps = parseSteps(values.getOrNull(5)),
                    status = "订单 $orderId · 待开始",
                    message = "说“开始制作”或轻触开始。",
                    orderId = orderId,
                    drink = drink,
                )
                render()
                speak("订单 $orderId，$drink。请说开始制作，或轻触开始。")
                commandBridge.sendEvent("cupflow_order_received", orderId)
                scheduleVoiceStart()
            }
            "cupflow_start" -> startOrder()
            "cupflow_voice_result" -> {
                voiceRequestPending = false
                val outcome = values.getOrNull(1).orEmpty()
                val detail = values.getOrNull(2).orEmpty()
                if (outcome == "start") {
                    startOrder()
                } else if (state.status.contains("待开始")) {
                    state = state.copy(message = detail.ifBlank { "未听清，请说“开始制作”。" })
                    render()
                    if (outcome == "retry") scheduleVoiceStart(700)
                }
            }
            "cupflow_flow" -> {
                val nextStep = values.getOrNull(1)?.toIntOrNull() ?: state.step
                val stepName = values.getOrNull(2).orEmpty()
                state = state.copy(
                    step = nextStep.coerceIn(0, (state.steps.size - 1).coerceAtLeast(0)),
                    status = if (stepName == "订单完成") "订单 ${state.orderId} · 已完成" else "订单 ${state.orderId} · 制作中",
                    message = values.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "识别中…",
                    alert = false,
                )
                render()
                speak(if (stepName == "订单完成") "订单完成。" else "已完成上一项。下一步，$stepName。")
            }
            "cupflow_alert" -> {
                val reason = values.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "当前操作异常，请纠正后继续。"
                state = state.copy(status = "订单 ${state.orderId} · 步骤错误", message = "步骤错误：$reason", alert = true)
                render()
                speak("步骤错误，$reason。请纠正后继续。")
            }
            "cupflow_skip_saved" -> {
                state = state.copy(message = values.getOrNull(1) ?: "跳过已记为异常，已继续下一步。", alert = false)
                render()
            }
        }
    }

    private fun parseSteps(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList { for (index in 0 until array.length()) array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add) }
    }.getOrDefault(emptyList())

    private fun render() {
        status.text = state.orderId?.let { "$it · ${state.drink.orEmpty()}" } ?: "CupFlow · 等待订单"
        currentStep.text = when {
            state.status == "等待订单" -> "等待店长下发订单"
            state.status.contains("已完成") -> "订单完成"
            else -> "${state.step + 1}/${state.steps.size} ${state.steps.getOrNull(state.step) ?: "等待流程"}"
        }
        message.text = state.message
        val color = if (state.alert) Color.rgb(255, 92, 92) else getColor(R.color.glass_text)
        currentStep.setTextColor(color)
        message.setTextColor(color)
        action.text = when {
            state.alert -> "轻触返回当前步骤；纠正后将自动继续"
            state.status.contains("待开始") -> "说“开始制作”或轻触开始"
            state.status.contains("制作中") -> "轻触跳过当前步骤（将记录异常）"
            else -> ""
        }
        action.visibility = if (action.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun speak(value: String) {
        if (!ttsReady) {
            pendingSpeech = value
            return
        }
        tts?.speak(value, TextToSpeech.QUEUE_FLUSH, null, "cupflow")
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun scheduleVoiceStart(delayMs: Long = 1_100) {
        window.decorView.postDelayed({ requestVoiceStart() }, delayMs)
    }

    private fun requestVoiceStart() {
        if (!state.status.contains("待开始") || voiceRequestPending) return
        voiceRequestPending = true
        state = state.copy(message = "正在聆听，请说“开始制作”…")
        render()
        commandBridge.sendEvent("cupflow_voice_start")
    }

    private fun stopVoiceStart() {
        if (voiceRequestPending) commandBridge.sendEvent("cupflow_voice_stop")
        voiceRequestPending = false
    }
}
