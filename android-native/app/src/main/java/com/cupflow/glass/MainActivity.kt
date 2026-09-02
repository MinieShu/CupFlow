package com.cupflow.glass

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private lateinit var commandBridge: GlassCommandBridge
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale.SIMPLIFIED_CHINESE
        }
        window.setBackgroundDrawableResource(R.color.glass_background)
        window.decorView.setBackgroundColor(Color.BLACK)
        commandBridge = GlassCommandBridge(::handlePhoneCommand)
        setContentView(buildView())
        render()
    }

    override fun onDestroy() {
        stopVoiceStart()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scheduleVoiceStart()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(28, 8, 28, 18)
            setBackgroundColor(Color.BLACK)
        }
        status = text("", 11f, R.color.glass_muted)
        currentStep = text("", 19f, R.color.glass_text)
        message = text("", 13f, R.color.glass_text)
        action = text("", 14f, R.color.glass_primary).apply {
            isClickable = true
            setPadding(0, 8, 0, 0)
            setOnClickListener { startOrder() }
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
        state = state.copy(status = "订单 ${state.orderId} · 制作中", message = "识别中…", alert = false)
        render()
        speak("开始制作。")
        commandBridge.sendEvent("cupflow_started")
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
                scheduleVoiceStart()
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
                speak(state.message)
            }
            "cupflow_alert" -> {
                val reason = values.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "当前操作异常，请纠正后继续。"
                state = state.copy(status = "订单 ${state.orderId} · 请纠正", message = "异常：$reason", alert = true)
                render()
                speak("异常，请纠正后继续。")
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
        action.text = if (state.status.contains("待开始")) "说“开始制作”或轻触开始" else ""
        action.visibility = if (action.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun speak(value: String) {
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

    private fun scheduleVoiceStart() {
        window.decorView.postDelayed({ startVoiceStart() }, 1_100)
    }

    private fun startVoiceStart() {
        if (!state.status.contains("待开始") || voiceListening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state = state.copy(message = "语音服务不可用，请轻触开始。")
            render()
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceListening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { voiceListening = false }
                override fun onError(error: Int) {
                    voiceListening = false
                    if (error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) scheduleVoiceStart()
                    else if (state.status.contains("待开始")) {
                        state = state.copy(message = "语音服务暂不可用，请轻触开始。")
                        render()
                    }
                }
                override fun onResults(results: Bundle?) { handleVoiceResults(results) }
                override fun onPartialResults(partialResults: Bundle?) { handleVoiceResults(partialResults) }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        voiceListening = true
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    private fun handleVoiceResults(results: Bundle?) {
        voiceListening = false
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (phrases.any(::isStartPhrase)) startOrder() else if (state.status.contains("待开始")) scheduleVoiceStart()
    }

    private fun isStartPhrase(value: String): Boolean {
        val normalized = value.replace(Regex("\\s"), "")
        return normalized == "开始" || normalized.contains("开始制作") || normalized.contains("开始做")
    }

    private fun stopVoiceStart() {
        voiceListening = false
        speechRecognizer?.cancel()
    }

    companion object {
        private const val MICROPHONE_REQUEST = 7201
    }
}
