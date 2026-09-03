package com.cupflow.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class CupOrder(val id: String, val drink: String, val options: List<String>)

class MainActivity : Activity() {
    private val authRequest = 4101
    private val phoneScanRequest = 4102
    private val recipeManagementRequest = 4103
    private val executor = Executors.newSingleThreadExecutor()
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val speechExecutor = Executors.newSingleThreadExecutor()
    private val visionSettings by lazy { VisionSettingsStore(this) }
    private val vision by lazy { VisionClient { visionSettings.endpoint() } }
    private val speech by lazy { SpeechClient { visionSettings.speechEndpoint() } }
    private val frames = ArrayDeque<CapturedFrame>()
    private val voiceBuffer = ByteArrayOutputStream()
    private val recipeStore by lazy { RecipeStore(this) }
    private val ingredientGridStore by lazy { IngredientGridStore(this) }
    private val decisionLogStore by lazy { DecisionLogStore(this) }

    private lateinit var status: TextView
    private lateinit var healthText: TextView
    private lateinit var healthTitle: TextView
    private lateinit var cameraPreview: ImageView
    private lateinit var cameraPreviewText: TextView
    private lateinit var orderText: TextView
    private lateinit var procedureText: TextView
    private lateinit var glassesNameInput: EditText
    private var glassesName = "1号操作员"
    private var currentOrder: CupOrder? = null
    private var currentRecipe: DrinkRecipe? = null
    private var stepIndex = 0
    private var productionStarted = false
    private var linkReady = false
    private var glassAppReady = false
    private var cxrConnected = false
    private var bluetoothConnected = false
    private var captureBusy = false
    private var captureStartedAt = 0L
    private var lastCameraFailureAt = 0L
    private var analysisBusy = false
    private var exceptionSaving = false
    private var lastVisionAt = 0L
    private var autoLoop = false
    private var previewLoop = false
    private var latestFrame: CapturedFrame? = null
    private var latestFingerprint: IntArray? = null
    private var lastVisionFingerprint: IntArray? = null
    private var lastIdleLabelFingerprint: IntArray? = null
    private var lastIdleLabelScanAt = 0L
    private var lastFrameAt = 0L
    private var visionHealth = "等待视觉服务检查"
    private var authorizationPending = false
    private var authorizationRequestedAt = 0L
    private var voiceCaptureActive = false
    private var voiceCaptureSession = 0
    private var connectionPending = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var deliveryPending = false
    private var deliveryAttempts = 0
    private var deliveryOrderId: String? = null
    private var deliveryToken: String? = null
    private var deliverySequence = 0L
    private var startAfterDelivery = false
    private var pendingPhoneScanUri: Uri? = null
    private var beijingClockActive = false
    private val beijingClockFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glassesName = getPreferences(MODE_PRIVATE).getString("glassesName", glassesName) ?: glassesName
        setContentView(buildView())
        startBeijingClock()
        val app = application as CupFlowCompanionApplication
        if (app.token.isBlank()) render("请先授权并连接 Rokid AI App。")
        else if (hasRequiredGlassesPermissions()) window.decorView.post { connect(app.token) }
        else window.decorView.post { requestAuthorization() }
    }

    override fun onDestroy() {
        beijingClockActive = false
        autoLoop = false
        previewLoop = false
        (application as CupFlowCompanionApplication).cxrLink?.disconnect()
        executor.shutdownNow()
        frameExecutor.shutdownNow()
        speechExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 26, 24, 36)
            setBackgroundColor(0xfff8f6ef.toInt())
        }
        body.addView(text("杯序  CupFlow", 27f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("店长控制台 · 第一视角流程 Agent", 13f, 0xff71817b.toInt()))
        status = text("", 13f, 0xff164f3f.toInt()).apply {
            setPadding(14, 10, 14, 10)
            background = rounded(0xffe6f3e8.toInt(), 0xffe6f3e8.toInt())
        }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18; bottomMargin = 14 })

        val healthCard = card("运行状态", "连接异常时不会推进当前步骤，可重新连接后继续")
        healthTitle = healthCard.getChildAt(0) as TextView
        healthText = text("", 13f, 0xff39554b.toInt())
        healthCard.addView(healthText)
        healthCard.addView(button("重新连接眼镜") { reconnectGlasses() })
        healthCard.addView(button("恢复当前订单识别") { resumeRecognition() })
        healthCard.addView(button("配置视觉服务地址") { configureVisionEndpoint() })
        healthCard.addView(button("查看识别决策日志") { startActivity(Intent(this, DecisionLogActivity::class.java)) })
        healthCard.addView(text("眼镜实时画面", 15f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        cameraPreview = ImageView(this).apply {
            setBackgroundColor(0xffdce5df.toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "眼镜实时画面"
        }
        healthCard.addView(cameraPreview, LinearLayout.LayoutParams(-1, 360).apply { topMargin = 8 })
        cameraPreviewText = text("等待眼镜相机画面…", 12f, 0xff71817b.toInt())
        healthCard.addView(cameraPreviewText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        body.addView(healthCard)

        val glassesCard = card("眼镜管理", "连接状态与操作员名称")
        glassesNameInput = EditText(this).apply { hint = "眼镜名称"; setText(glassesName) }
        glassesCard.addView(glassesNameInput)
        glassesCard.addView(button("保存眼镜名称") { saveGlassesName() })
        glassesCard.addView(button("连接已授权眼镜", true) { connectAuthorizedGlasses() })
        glassesCard.addView(button("重新授权眼镜") { requestAuthorization() })
        body.addView(glassesCard)

        val orderCard = card("扫描下发订单", "仅由店长手机扫描杯贴、核对并下发眼镜")
        orderText = text("当前无订单", 18f)
        procedureText = text("扫描杯贴后，这里会显示该订单的制作步骤。", 15f)
        orderCard.addView(orderText)
        orderCard.addView(procedureText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        orderCard.addView(button("扫描杯贴并下发订单", true) { scanCupLabelFromPhone() })
        orderCard.addView(button("导入 POS / JSON 订单") { importOrderFromJson() })
        orderCard.addView(button("重新下发当前订单") { currentOrder?.let(::dispatchOrder) ?: render("请先扫描杯贴。") })
        orderCard.addView(button("开始当前订单", true) { startCurrentOrderFromManager() })
        orderCard.addView(button("结束当前订单") { clearOrder() })
        body.addView(orderCard)

        val recipeCard = card("配方管理", "饮品卡片、图片与文本导入")
        recipeCard.addView(button("进入配方管理", true) { startActivityForResult(Intent(this, RecipeManagementActivity::class.java), recipeManagementRequest) })
        body.addView(recipeCard)

        val ingredientCard = card("配料管理", "为难识别的小料配置格架；带标签物料由视觉直接识别")
        ingredientCard.addView(button("进入配料管理", true) { startActivity(Intent(this, IngredientManagementActivity::class.java)) })
        body.addView(ingredientCard)

        val exceptionCard = card("异常管理", "查看本地关键帧证据与异常记录")
        exceptionCard.addView(button("查看异常记录", true) { startActivity(Intent(this, ExceptionManagementActivity::class.java)) })
        body.addView(exceptionCard)
        return ScrollView(this).apply { addView(body) }
    }

    private fun startBeijingClock() {
        beijingClockActive = true
        fun tick() {
            if (!beijingClockActive || isFinishing || isDestroyed) return
            healthTitle.text = "运行状态  ·  北京时间 ${beijingClockFormat.format(Date())}"
            window.decorView.postDelayed({ tick() }, 1_000)
        }
        tick()
    }

    private fun card(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 20, 20, 20)
        background = rounded(0xfffffdf8.toInt(), 0xffe1e5da.toInt())
        addView(text(title, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text(subtitle, 12f, 0xff71817b.toInt()), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(2, stroke); cornerRadius = 22f
    }

    private fun text(value: String, size: Float, color: Int = 0xff10241f.toInt()) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(5f, 1f)
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(if (primary) Color.WHITE else 0xff164f3f.toInt())
        background = rounded(if (primary) 0xff164f3f.toInt() else 0xffeff6e9.toInt(), if (primary) 0xff164f3f.toInt() else 0xffbfc8bd.toInt())
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestAuthorization() {
        if (authorizationPending) {
            render("正在等待 Rokid AI App 的授权响应，请不要重复点击。")
            return
        }
        if (!AuthorizationHelper.isRequiredRokidAppInstalled(this)) {
            render("未找到 Rokid AI App，请先安装并连接眼镜。")
            return
        }
        authorizationPending = true
        authorizationRequestedAt = System.currentTimeMillis()
        render("正在打开 Rokid AI App 授权…请在其中允许 CupFlow。")
        val immediateResult = runCatching {
            AuthorizationHelper.requestAuthorization(
                this,
                arrayOf(GlassPermission.CAMERA, GlassPermission.MEDIA, GlassPermission.MICROPHONE),
                authRequest,
            )
        }
            .onFailure {
                authorizationPending = false
                render("无法发起 Rokid 授权：${it.message.orEmpty().take(48)}")
                return
            }
            .getOrNull()
        // The Rokid SDK returns an immediate Pair when it can reuse an existing grant,
        // rather than invoking onActivityResult. Parse it so its in-process permission
        // state is restored before CXRLink starts a camera request.
        immediateResult?.let { result ->
            authorizationPending = false
            handleAuthorization(result.first, result.second)
            return
        }
        window.decorView.postDelayed({
            if (authorizationPending && System.currentTimeMillis() - authorizationRequestedAt >= AUTHORIZATION_TIMEOUT_MS) {
                authorizationPending = false
                render("未收到 Rokid 授权响应。请确认 Rokid AI App 已打开、眼镜已连接，再点击授权。")
            }
        }, AUTHORIZATION_TIMEOUT_MS)
    }

    @Deprecated("CXR-L currently returns authorization through Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            authRequest -> handleAuthorization(resultCode, data)
            phoneScanRequest -> handlePhonePhoto(resultCode, data)
            recipeManagementRequest -> data?.getStringExtra(RecipeManagementActivity.EXTRA_DRINK)?.let { drink ->
                recipeStore.load(drink)?.let { recipe ->
                    if (currentOrder?.drink == drink) currentRecipe = recipe
                    render("已从配方库加载 $drink。")
                }
            }
        }
    }

    private fun handleAuthorization(resultCode: Int, data: Intent?) {
        authorizationPending = false
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                if (!hasRequiredGlassesPermissions()) {
                    render("Rokid 返回了授权令牌，但相机、媒体或麦克风权限未完整授予。请在 Rokid AI App 中重新允许 CupFlow。")
                    return
                }
                (application as CupFlowCompanionApplication).token = result.token
                AuthTokenStore(this).save(result.token)
                connect(result.token)
            }
            is AuthResult.AuthFail -> render("Rokid 授权失败，请在 Rokid AI App 中允许 CupFlow。")
            else -> render("授权已取消。")
        }
    }

    private fun connect(token: String, forceFresh: Boolean = false) {
        val app = application as CupFlowCompanionApplication
        if (linkReady && !forceFresh) {
            render("● 眼镜已连接。")
            return
        }
        if (connectionPending) {
            render("正在连接眼镜，请稍候。")
            return
        }
        connectionPending = true
        autoLoop = false
        linkReady = false
        glassAppReady = false
        cxrConnected = false
        bluetoothConnected = false
        app.token = token
        if (forceFresh) {
            app.cxrLink?.disconnect()
            app.cxrLink = null
        }
        app.cxrLink?.let { existing ->
            startCxrConnection(app, existing, token)
            return
        }

        val link = CXRLink(this)
        link.apply {
            setCXRLinkCbk(object : ICXRLinkCbk {
                override fun onCXRLConnected(connected: Boolean) {
                    if (app.cxrLink !== link) return
                    cxrConnected = connected
                    if (connected) bluetoothConnected = runCatching { link.isGlassBtConnected() }.getOrDefault(false)
                    updateLinkReady()
                    if (!connected && !connectionPending) window.decorView.post { scheduleReconnect("媒体服务已断开") }
                }
                override fun onGlassBtConnected(connected: Boolean) {
                    if (app.cxrLink !== link) return
                    bluetoothConnected = connected
                    updateLinkReady()
                    if (!connected && !connectionPending) window.decorView.post { scheduleReconnect("眼镜数据通道已断开") }
                }
                override fun onGlassAiAssistStart() {}
                override fun onGlassAiAssistStop() {}
                override fun onGlassAiInterrupt(interrupt: Boolean) {}
                override fun onGlassDeviceInfo(info: com.rokid.cxr.link.utils.GlassInfo) {}
                override fun onGlassWearingStatus(wearing: Boolean) {}
            })
            setCXRCustomCmdCbk(object : ICustomCmdCbk {
                override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
                    if (key != "cupflow_to_phone") return
                    val values = payload?.let { caps -> Caps.fromBytes(caps) }?.let { caps ->
                        (0 until caps.size()).map { index -> caps.at(index).string.orEmpty() }
                    }.orEmpty()
                    val event = values.firstOrNull().orEmpty()
                    runOnUiThread {
                        when (event) {
                            "cupflow_glass_opened", "cupflow_glass_ready" -> {
                                val becameReady = !glassAppReady
                                glassAppReady = true
                                if (becameReady) {
                                    currentOrder?.takeIf { !productionStarted }?.let(::dispatchOrder)
                                    render("● 眼镜端 CupFlow 已启动，等待店长端下发订单。")
                                }
                            }
                            "cupflow_started" -> {
                                if (currentOrder != null) {
                                    startAfterDelivery = false
                                    productionStarted = true
                                    render("已开始制作：等待${currentRecipe?.steps?.getOrNull(stepIndex)?.title.orEmpty()}。")
                                    startAutoRecognition()
                                }
                            }
                            "cupflow_order_received" -> confirmOrderDelivery(values.getOrNull(1), values.getOrNull(2))
                            "cupflow_skip" -> recordManualSkip(values.getOrNull(1))
                            "cupflow_retry" -> if (!exceptionSaving) resumeRecognition()
                            "cupflow_voice_start" -> startGlassesVoiceCapture()
                            "cupflow_voice_stop" -> stopGlassesVoiceCapture()
                        }
                    }
                }
            })
        }
        app.cxrLink = link
        startCxrConnection(app, link, token)
    }

    private fun startCxrConnection(app: CupFlowCompanionApplication, link: CXRLink, token: String) {
        if (!link.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, "com.cupflow.glass"))) {
            connectionPending = false
            render("CupFlow 暂无法创建 Rokid 会话，正在准备重连。")
            scheduleReconnect("Rokid 会话未就绪")
            return
        }
        registerMediaCallbacks(link)
        if (!link.connect(token)) {
            connectionPending = false
            render("Rokid 媒体服务暂未接受连接，正在准备重连。")
            scheduleReconnect("媒体服务未响应")
            return
        }
        render("正在连接 Rokid 媒体服务…")
        window.decorView.postDelayed({
            if (app.cxrLink !== link) return@postDelayed
            when {
                !cxrConnected -> {
                    connectionPending = false
                    render("Rokid 媒体服务 10 秒未响应，正在准备重连。")
                    scheduleReconnect("媒体服务超时")
                }
                !bluetoothConnected -> {
                    connectionPending = false
                    render("Rokid 服务已连接，但未检测到眼镜数据通道，正在准备重连。")
                    scheduleReconnect("眼镜数据通道未就绪")
                }
            }
        }, 10_000)
    }

    private fun registerMediaCallbacks(link: CXRLink) {
        link.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray?) {
                captureBusy = false
                data?.let(::handleGlassesFrame) ?: render("眼镜未返回图片。")
            }
            override fun onImageError(code: Int, msg: String?) {
                captureBusy = false
                render("眼镜拍照失败：$code ${msg.orEmpty()}")
            }
        })
        link.setCXRAudioCbk(object : IAudioStreamCbk {
            override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
                if (!voiceCaptureActive || length <= 0 || offset !in 0..data.size) return
                val safeLength = length.coerceAtMost(data.size - offset)
                if (safeLength <= 0) return
                synchronized(voiceBuffer) {
                    if (voiceBuffer.size() < MAX_VOICE_PCM_BYTES) {
                        val accepted = safeLength.coerceAtMost(MAX_VOICE_PCM_BYTES - voiceBuffer.size())
                        voiceBuffer.write(data, offset, accepted)
                    }
                }
            }

            override fun onAudioError(code: Int, msg: String?) {
                runOnUiThread {
                    if (voiceCaptureActive) {
                        stopGlassesVoiceCapture()
                        sendVoiceResult("error", "眼镜麦克风不可用，请轻触开始。")
                    }
                }
            }

            override fun onAudioStreamStateChanged(started: Boolean) {}
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (linkReady || reconnectScheduled || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        val token = (application as CupFlowCompanionApplication).token
        if (token.isBlank()) return
        reconnectScheduled = true
        reconnectAttempts += 1
        val delay = reconnectAttempts * 1_500L
        window.decorView.postDelayed({
            reconnectScheduled = false
            if (!linkReady) connect(token, forceFresh = true)
        }, delay)
    }

    private fun updateLinkReady() = runOnUiThread {
        linkReady = cxrConnected && bluetoothConnected
        if (!linkReady) {
            glassAppReady = false
            autoLoop = false
            previewLoop = false
            cameraPreviewText.text = "眼镜未连接，无法显示实时画面。"
        }
        if (linkReady) {
            connectionPending = false
            reconnectAttempts = 0
        }
        val message = when {
            linkReady -> "● 眼镜数据通道已连接，等待眼镜端 CupFlow 启动。"
            cxrConnected -> "Rokid 服务已连接，正在等待眼镜数据通道。"
            else -> "○ 眼镜连接中断，当前步骤已保持。"
        }
        render(message)
        if (linkReady) startCameraPreview()
        if (linkReady) currentOrder?.takeIf { !productionStarted }?.let(::dispatchOrder)
    }

    private fun startCameraPreview() {
        if (!linkReady || previewLoop) return
        if (!AuthorizationHelper.hasGlassPermission(GlassPermission.CAMERA)) {
            cameraPreviewText.text = "眼镜相机未授权：请点击“重新授权眼镜”。"
            return
        }
        previewLoop = true
        cameraPreviewText.text = "正在接收眼镜实时画面（仅预览，不保存）"
        tickCameraPreview()
    }

    private fun tickCameraPreview() {
        if (!previewLoop) return
        if (captureBusy && System.currentTimeMillis() - captureStartedAt > CAMERA_CAPTURE_TIMEOUT_MS) {
            captureBusy = false
            cameraPreviewText.text = "画面更新延迟，正在重新获取…"
        }
        takeGlassesPhoto()
        window.decorView.postDelayed({ tickCameraPreview() }, PREVIEW_CAPTURE_INTERVAL_MS)
    }

    private fun scanCupLabelFromPhone() {
        pendingPhoneScanUri?.let { contentResolver.delete(it, null, null) }
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "cupflow-label-${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            },
        ) ?: run {
            render("无法创建高清杯贴照片，请稍后重试。")
            return
        }
        pendingPhoneScanUri = uri
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, phoneScanRequest)
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            pendingPhoneScanUri = null
            render("无法打开手机相机：${error.message.orEmpty()}")
        }
    }

    private fun importOrderFromJson() {
        val input = EditText(this).apply {
            hint = "{\"id\":\"A102\",\"drink\":\"云朵乌龙奶茶\",\"options\":[\"少糖\",\"去冰\",\"加珍珠\"]}"
            minLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("导入订单")
            .setMessage("用于 POS、Webhook 或导出文件适配。导入后由店长确认下发。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("核对并下发") { _, _ -> parseImportedOrder(input.text.toString()) }
            .show()
    }

    private fun parseImportedOrder(raw: String) {
        val order = runCatching {
            val json = JSONObject(raw)
            val id = json.optString("id").trim().take(32)
            val drink = json.optString("drink").trim().take(64)
            val options = json.optJSONArray("options")?.let { array ->
                buildList { for (index in 0 until array.length()) array.optString(index).trim().take(24).takeIf { it.isNotBlank() }?.let(::add) }
            }.orEmpty()
            require(id.isNotBlank() && drink.isNotBlank()) { "订单必须包含 id 和 drink" }
            CupOrder(id, drink, options)
        }.getOrElse {
            render("订单导入失败：${it.message ?: "JSON 格式无效"}。当前订单未改变。")
            return
        }
        currentOrder = order
        stepIndex = 0
        productionStarted = false
        useRecipeFor(order)
        dispatchOrder(order)
        decisionLogStore.append(order, "订单导入", null, "订单已下发", "已通过 POS / JSON 适配入口由店长确认下发")
        render("已导入订单：${order.id} · ${order.drink}，请在眼镜开始制作。")
    }

    private fun reconnectGlasses() {
        val token = (application as CupFlowCompanionApplication).token
        if (token.isBlank()) requestAuthorization() else connect(token, forceFresh = true)
    }

    private fun connectAuthorizedGlasses() {
        val token = (application as CupFlowCompanionApplication).token
        if (token.isBlank()) {
            render("尚无已保存授权，请先重新授权眼镜。")
            return
        }
        connect(token)
    }

    private fun hasRequiredGlassesPermissions(): Boolean = arrayOf(
        GlassPermission.CAMERA,
        GlassPermission.MEDIA,
        GlassPermission.MICROPHONE,
    ).all(AuthorizationHelper::hasGlassPermission)

    private fun configureVisionEndpoint() {
        val input = EditText(this).apply {
            setText(visionSettings.endpoint())
            hint = VisionSettingsStore.DEFAULT_ENDPOINT
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("视觉服务地址")
            .setMessage("默认使用 USB 反向端口。非本机地址必须使用 HTTPS。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val error = visionSettings.save(input.text.toString())
                if (error != null) render("地址未保存：$error")
                else {
                    visionHealth = "等待下一帧检查"
                    render("已切换到${visionSettings.label()}视觉服务。")
                }
            }
            .show()
    }

    private fun resumeRecognition() {
        if (!linkReady) {
            render("眼镜未连接，无法恢复识别。")
            return
        }
        if (currentOrder != null && !productionStarted) {
            dispatchOrder(currentOrder!!)
            render("订单已重新下发，请在眼镜开始制作。")
            return
        }
        if (currentOrder == null) {
            render("当前无订单，请由店长手机扫描杯贴后下发。")
            return
        }
        visionHealth = "等待下一帧检查"
        startAutoRecognition()
        render("已恢复识别，当前步骤保持不变。")
    }

    private fun handlePhonePhoto(resultCode: Int, data: Intent?) {
        val uri = pendingPhoneScanUri
        pendingPhoneScanUri = null
        if (resultCode != RESULT_OK) {
            uri?.let { contentResolver.delete(it, null, null) }
            render("已取消杯贴扫描。")
            return
        }
        val bytes = uri?.let(::readHighQualityLabelPhoto) ?: run {
            val bitmap = data?.extras?.get("data") as? Bitmap ?: return@run null
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                output.toByteArray()
            }
        }
        uri?.let { contentResolver.delete(it, null, null) }
        if (bytes == null) {
            render("未取得手机扫描照片。")
            return
        }
        analyzeLabel(bytes)
    }

    private fun readHighQualityLabelPhoto(uri: Uri): ByteArray? {
        val original = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (original.size <= MAX_LABEL_IMAGE_BYTES) return original
        val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return original
        val longestEdge = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = (MAX_LABEL_IMAGE_EDGE.toFloat() / longestEdge).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else bitmap
        val compressed = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)
            output.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return compressed
    }

    private fun takeGlassesPhoto() {
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (!linkReady || link == null || captureBusy) {
            if (!linkReady) render("眼镜尚未连接，识别已暂停。")
            return
        }
        captureBusy = true
        captureStartedAt = System.currentTimeMillis()
        if (!link.takePhoto(1024, 768, 80)) {
            // The SDK does not invoke the image callback when the session or camera permission
            // is unavailable.  Always release the gate so recognition can recover after reconnect.
            captureBusy = false
            captureStartedAt = 0L
            if (!AuthorizationHelper.hasGlassPermission(GlassPermission.CAMERA)) {
                previewLoop = false
                autoLoop = false
                cameraPreviewText.text = "眼镜相机未授权：请重新授权后再试。"
            }
            val now = System.currentTimeMillis()
            if (now - lastCameraFailureAt >= 3_000) {
                lastCameraFailureAt = now
                render("眼镜相机未就绪：请重新授权后等待眼镜连接。")
                scheduleReconnect("眼镜相机未就绪")
            }
        }
    }

    private fun handleGlassesFrame(bytes: ByteArray) {
        captureBusy = false
        captureStartedAt = 0L
        val now = System.currentTimeMillis()
        lastFrameAt = now
        val frame = CapturedFrame(now, bytes)
        synchronized(frames) {
            frames.addLast(frame)
            while (frames.isNotEmpty() && now - frames.first.at > 5_000) frames.removeFirst()
        }
        frameExecutor.execute {
            val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.RGB_565
            })
            val fingerprint = fingerprint(bytes)
            runOnUiThread {
                preview?.let {
                    cameraPreview.setImageBitmap(it)
                    cameraPreviewText.text = "眼镜实时画面 · 刚刚更新（仅预览，不保存）"
                }
                if (fingerprint == null) return@runOnUiThread
                latestFrame = frame
                latestFingerprint = fingerprint
                evaluateLatestFrame()
            }
        }
    }

    /** Frames arrive at 2 FPS. A materially changed scene is checked with one earlier frame. */
    private fun evaluateLatestFrame() {
        val frame = latestFrame ?: return
        val fingerprint = latestFingerprint ?: return
        if (analysisBusy) return
        val now = System.currentTimeMillis()
        if (isIdleForGlassesLabelScan()) {
            val sceneChanged = sceneDifference(lastIdleLabelFingerprint, fingerprint) >= 8
            val dueForRescan = now - lastIdleLabelScanAt >= IDLE_LABEL_RESCAN_MS
            if (!sceneChanged && !dueForRescan) return
            lastIdleLabelFingerprint = fingerprint
            lastIdleLabelScanAt = now
            analyzeLabel(frame.bytes, fromGlassesIdleScan = true)
            return
        }
        if (!autoLoop) return
        val sceneChanged = sceneDifference(lastVisionFingerprint, fingerprint) >= 8
        val dueForSafetyCheck = now - lastVisionAt >= 7_000
        if (!sceneChanged && !dueForSafetyCheck) return
        if (now - lastVisionAt < OPERATION_VISION_INTERVAL_MS) return
        lastVisionFingerprint = fingerprint
        val order = currentOrder
        if (order != null && productionStarted) analyzeOperation(frame, order)
    }

    private fun finishVisionCycle() {
        analysisBusy = false
        window.decorView.post { evaluateLatestFrame() }
    }

    private fun fingerprint(bytes: ByteArray): IntArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = 16
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        val columns = 16
        val rows = 12
        return IntArray(columns * rows) { index ->
            val x = ((index % columns) * (bitmap.width - 1) / (columns - 1)).coerceAtLeast(0)
            val y = ((index / columns) * (bitmap.height - 1) / (rows - 1)).coerceAtLeast(0)
            val color = bitmap.getPixel(x, y)
            (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
        }.also { bitmap.recycle() }
    }

    private fun sceneDifference(previous: IntArray?, current: IntArray): Int {
        if (previous == null || previous.size != current.size) return Int.MAX_VALUE
        return previous.indices.sumOf { index -> kotlin.math.abs(previous[index] - current[index]) } / current.size
    }

    private fun isIdleForGlassesLabelScan(): Boolean = currentOrder == null ||
        (productionStarted && currentRecipe?.steps?.getOrNull(stepIndex) == null)

    private fun analyzeLabel(bytes: ByteArray, fromGlassesIdleScan: Boolean = false) {
        analysisBusy = true
        lastVisionAt = System.currentTimeMillis()
        if (fromGlassesIdleScan) render("眼镜发现新画面，正在识别杯贴…") else render("店长手机正在识别杯贴…")
        executor.execute {
            try {
                val result = vision.analyze(bytes, "label", null, null)
                visionHealth = "正常"
                val ticket = result.ticket
                val id = ticket?.optString("orderId").orEmpty()
                val drink = ticket?.optString("drink").orEmpty()
                val options = listOfNotNull(ticket?.optString("sugar")?.takeIf { it.isNotBlank() }, ticket?.optString("ice")?.takeIf { it.isNotBlank() }, ticket?.optString("topping")?.takeIf { it.isNotBlank() })
                runOnUiThread {
                    finishVisionCycle()
                    val minimumConfidence = if (fromGlassesIdleScan) 0.90 else 0.85
                    if (!isUsableOrderField(id) || !isUsableOrderField(drink) || result.confidence < minimumConfidence) {
                        if (!fromGlassesIdleScan) {
                            decisionLogStore.append(null, "杯贴识别", result, "等待人工核对", "订单字段不完整或置信度不足")
                            render("杯贴识别不完整或置信度不足，请重新扫描后手动核对。")
                        } else {
                            render("未发现可下发的有效杯贴订单，继续等待。")
                        }
                    } else {
                        currentOrder = CupOrder(id, drink, options)
                        stepIndex = 0
                        productionStarted = false
                        useRecipeFor(currentOrder!!)
                        dispatchOrder(currentOrder!!)
                        val source = if (fromGlassesIdleScan) "眼镜空闲杯贴" else "杯贴识别"
                        decisionLogStore.append(currentOrder, source, result, "订单已下发", result.reason.ifBlank { "高置信度订单识别" })
                        render("已高置信度识别并自动下发：$id · $drink")
                    }
                }
            } catch (error: Exception) {
                visionHealth = "不可用：${error.message.orEmpty().take(48)}"
                runOnUiThread {
                    decisionLogStore.append(null, "杯贴识别", null, "服务错误", error.message.orEmpty())
                    finishVisionCycle(); render("视觉服务错误：${error.message}")
                }
            }
        }
    }

    private fun isUsableOrderField(value: String): Boolean = value.trim().lowercase() !in setOf(
        "", "null", "unknown", "n/a", "na", "none", "未识别", "无",
    )

    private fun analyzeOperation(frame: CapturedFrame, order: CupOrder) {
        analysisBusy = true
        lastVisionAt = System.currentTimeMillis()
        val expected = currentRecipe?.steps?.getOrNull(stepIndex) ?: run {
            analysisBusy = false
            render("当前订单没有可识别的流程配置。")
            return
        }
        executor.execute {
            try {
                val gridContext = ingredientGridStore.visionContextFor(expected.title)
                val result = vision.analyze(
                    frame.bytes,
                    "operation",
                    order,
                    expected.title,
                    gridContext,
                    earlierFramesFor(frame),
                )
                visionHealth = "正常"
                runOnUiThread {
                    finishVisionCycle()
                    val ingredientMismatch = result.confidence >= 0.75 &&
                        result.event in setOf("pearls", "topping") && result.event != expected.event
                    val flaggedResult = if (ingredientMismatch) result.copy(
                        event = "wrong",
                        reason = result.reason.ifBlank { "当前应执行${expected.title}，检测到不匹配小料。" },
                    ) else result
                    when {
                        result.event == expected.event && result.confidence >= 0.75 -> {
                            decisionLogStore.append(order, expected.title, result, "推进步骤", result.reason.ifBlank { "事件与当前步骤匹配" })
                            advance(result)
                        }
                        flaggedResult.event in setOf("wrong", "wrongLabel", "overfill") -> {
                            decisionLogStore.append(order, expected.title, flaggedResult, "异常提醒", flaggedResult.reason.ifBlank { "检测到不匹配操作" })
                            saveException(flaggedResult)
                        }
                        else -> {
                            decisionLogStore.append(order, expected.title, result, "保持当前步骤", result.reason.ifBlank { "画面暂不确定" })
                            render("等待${expected.title}：${result.reason.ifBlank { "画面暂不确定" }}")
                        }
                    }
                }
            } catch (error: Exception) {
                visionHealth = "不可用：${error.message.orEmpty().take(48)}"
                runOnUiThread {
                    autoLoop = false
                    decisionLogStore.append(order, expected.title, null, "服务错误", error.message.orEmpty())
                    finishVisionCycle(); render("视觉服务错误：${error.message}，当前步骤已保持。")
                }
            }
        }
    }

    private fun earlierFramesFor(current: CapturedFrame): List<ByteArray> = synchronized(frames) {
        frames.lastOrNull { it.at <= current.at - 700 }?.let { listOf(it.bytes) } ?: emptyList()
    }

    private fun dispatchOrder(order: CupOrder, retry: Boolean = false) {
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (link == null || !linkReady) {
            render("订单已读取；等待眼镜连接后再下发。")
            return
        }
        if (!glassAppReady) {
            render("订单已读取；等待眼镜端 CupFlow 就绪后自动下发。")
            return
        }
        if (!retry || deliveryOrderId != order.id || deliveryToken == null) {
            deliveryOrderId = order.id
            deliveryAttempts = 0
            deliveryToken = "${order.id}-${++deliverySequence}"
        }
        val token = deliveryToken ?: return
        deliveryPending = true
        deliveryAttempts += 1
        link.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_order")
            write(order.id)
            write(order.drink)
            write(order.options.joinToString("、"))
            write(glassesName)
            write(JSONArray(currentRecipe?.steps?.map { it.title }.orEmpty()).toString())
            write(token)
        })
        render("正在下发订单（第 $deliveryAttempts 次），等待眼镜确认…")
        window.decorView.postDelayed({
            if (!deliveryPending || deliveryToken != token || currentOrder?.id != order.id) return@postDelayed
            if (deliveryAttempts < MAX_DELIVERY_ATTEMPTS) dispatchOrder(order, retry = true)
            else {
                deliveryPending = false
                render("眼镜未确认收到订单，请检查连接后点击“重新下发当前订单”。")
            }
        }, ORDER_CONFIRM_TIMEOUT_MS)
    }

    private fun confirmOrderDelivery(orderId: String?, confirmationToken: String?) {
        val order = currentOrder ?: return
        if (order.id != orderId || deliveryToken != confirmationToken) return
        deliveryPending = false
        deliveryAttempts = 0
        render("订单已送达眼镜：${order.id} · ${order.drink}。")
        if (startAfterDelivery) {
            startAfterDelivery = false
            startProductionFromManager(order)
        }
    }

    private fun startCurrentOrderFromManager() {
        val order = currentOrder ?: run {
            render("请先扫描或导入订单。")
            return
        }
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (!linkReady || link == null) {
            render("眼镜尚未连接，无法开始当前订单。")
            return
        }
        startAfterDelivery = true
        dispatchOrder(order)
        render("正在确认订单已送达眼镜…")
    }

    private fun startProductionFromManager(order: CupOrder) {
        val link = (application as CupFlowCompanionApplication).cxrLink ?: return
        productionStarted = true
        link.sendCustomCmd("cupflow_to_glass", Caps().apply { write("cupflow_start") })
        val firstStep = currentRecipe?.steps?.getOrNull(stepIndex)?.title ?: "当前步骤"
        render("已从店长端开始制作：$firstStep。")
        startAutoRecognition()
    }

    private fun startGlassesVoiceCapture() {
        if (voiceCaptureActive || productionStarted || currentOrder == null) return
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (!linkReady || link == null) {
            sendVoiceResult("error", "眼镜未连接，无法使用语音开始。")
            return
        }
        synchronized(voiceBuffer) { voiceBuffer.reset() }
        voiceCaptureActive = true
        val session = ++voiceCaptureSession
        if (!link.startAudioStream(1)) {
            voiceCaptureActive = false
            sendVoiceResult("error", "眼镜麦克风未授权或不可用，请轻触开始。")
            return
        }
        render("正在聆听眼镜语音…")
        window.decorView.postDelayed({
            if (voiceCaptureActive && session == voiceCaptureSession) finishGlassesVoiceCapture()
        }, VOICE_CAPTURE_DURATION_MS)
    }

    private fun stopGlassesVoiceCapture() {
        if (!voiceCaptureActive) return
        voiceCaptureActive = false
        voiceCaptureSession += 1
        (application as CupFlowCompanionApplication).cxrLink?.stopAudioStream()
    }

    private fun finishGlassesVoiceCapture() {
        if (!voiceCaptureActive) return
        val link = (application as CupFlowCompanionApplication).cxrLink
        voiceCaptureActive = false
        link?.stopAudioStream()
        val pcm = synchronized(voiceBuffer) { voiceBuffer.toByteArray().also { voiceBuffer.reset() } }
        if (pcm.size < MIN_VOICE_PCM_BYTES) {
            sendVoiceResult("retry", "未听清，请说“开始制作”。")
            return
        }
        speechExecutor.execute {
            try {
                val result = speech.recognize(pcmToWav(pcm))
                runOnUiThread {
                    if (productionStarted) return@runOnUiThread
                    if (result.isStart) {
                        decisionLogStore.append(currentOrder, "语音启动", null, "语音开始", "眼镜语音：${result.transcript.take(80)}")
                        sendVoiceResult("start", result.transcript)
                    } else {
                        sendVoiceResult("retry", "未听清，请说“开始制作”。")
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { sendVoiceResult("error", "语音服务暂不可用，请轻触开始。") }
            }
        }
    }

    private fun sendVoiceResult(outcome: String, detail: String) {
        (application as CupFlowCompanionApplication).cxrLink?.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_voice_result")
            write(outcome)
            write(detail.take(120))
        })
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(44 + pcm.size)
        fun writeText(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeLe(value: Int, bytes: Int) {
            repeat(bytes) { offset -> output.write((value ushr (offset * 8)) and 0xff) }
        }
        val byteRate = VOICE_SAMPLE_RATE * VOICE_CHANNELS * VOICE_BITS_PER_SAMPLE / 8
        writeText("RIFF")
        writeLe(36 + pcm.size, 4)
        writeText("WAVEfmt ")
        writeLe(16, 4)
        writeLe(1, 2)
        writeLe(VOICE_CHANNELS, 2)
        writeLe(VOICE_SAMPLE_RATE, 4)
        writeLe(byteRate, 4)
        writeLe(VOICE_CHANNELS * VOICE_BITS_PER_SAMPLE / 8, 2)
        writeLe(VOICE_BITS_PER_SAMPLE, 2)
        writeText("data")
        writeLe(pcm.size, 4)
        output.write(pcm)
        return output.toByteArray()
    }

    private fun advance(result: VisionResult) {
        val completedStep = currentRecipe?.steps?.getOrNull(stepIndex)?.title.orEmpty()
        stepIndex += 1
        val order = currentOrder ?: return
        val next = currentRecipe?.steps?.getOrNull(stepIndex)
        val source = when (result.source) {
            "grid" -> "格位兜底"
            "reference" -> "基准图辅助"
            "manual" -> "人工跳过"
            else -> "直接识别"
        }
        val message = when {
            next == null -> "订单完成，请店长下发下一单。"
            result.event == "manualSkip" -> "已记录跳过$completedStep，下一步：${next.title}。"
            else -> "已完成$completedStep（$source），下一步：${next.title}。"
        }
        (application as CupFlowCompanionApplication).cxrLink?.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_flow")
            write(stepIndex.toString())
            write(next?.title ?: "订单完成")
            write(message)
        })
        if (next == null) autoLoop = false
        render(message)
    }

    private fun saveException(result: VisionResult) {
        if (exceptionSaving) return
        exceptionSaving = true
        autoLoop = false
        val stepName = currentRecipe?.steps?.getOrNull(stepIndex)?.title ?: "当前步骤"
        val errorDetail = "$stepName：${result.reason.ifBlank { "当前操作异常，请纠正后继续。" }}"
        render("步骤错误 $errorDetail。正在保留前后 2 秒关键帧…")
        (application as CupFlowCompanionApplication).cxrLink?.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_alert")
            write(errorDetail)
        })
        window.decorView.postDelayed({
            val order = currentOrder ?: return@postDelayed
            val snapshot = synchronized(frames) { frames.toList() }
            executor.execute {
                try {
                    EvidenceStore(this).save(order, glassesName, result.event, result.confidence, result.reason, "", snapshot)
                    runOnUiThread {
                        exceptionSaving = false
                        render("异常已保存到手机 Download/CupFlow，已继续等待纠正。")
                        if (productionStarted) startAutoRecognition()
                    }
                } catch (error: Exception) {
                    runOnUiThread { exceptionSaving = false; render("异常保存失败：${error.message}") }
                }
            }
        }, 2_000)
    }

    private fun recordManualSkip(requestedStep: String?) {
        if (exceptionSaving || !productionStarted) return
        val order = currentOrder ?: return
        val step = currentRecipe?.steps?.getOrNull(stepIndex) ?: return
        val result = VisionResult(
            event = "manualSkip",
            confidence = 1.0,
            reason = "眼镜轻触跳过：${requestedStep?.takeIf { it.isNotBlank() } ?: step.title}",
            source = "manual",
            ticket = null,
        )
        decisionLogStore.append(order, step.title, result, "人工跳过", "眼镜端轻触跳过，已作为异常记录")
        exceptionSaving = true
        autoLoop = false
        render("已跳过${step.title}，正在保留前后 2 秒关键帧…")
        window.decorView.postDelayed({
            val snapshot = synchronized(frames) { frames.toList() }
            executor.execute {
                try {
                    EvidenceStore(this).save(order, glassesName, result.event, result.confidence, result.reason, "眼镜轻触跳过", snapshot)
                    runOnUiThread {
                        exceptionSaving = false
                        advance(result)
                        (application as CupFlowCompanionApplication).cxrLink?.sendCustomCmd("cupflow_to_glass", Caps().apply {
                            write("cupflow_skip_saved")
                            write("跳过${step.title}已记为异常，已继续下一步。")
                        })
                        if (currentRecipe?.steps?.getOrNull(stepIndex) != null) startAutoRecognition()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        exceptionSaving = false
                        render("跳过记录保存失败：${error.message}")
                    }
                }
            }
        }, 2_000)
    }

    private fun startAutoRecognition() {
        if (autoLoop) return
        if (!linkReady || currentOrder == null || !productionStarted) {
            if (!linkReady) render("眼镜未连接，无法启动识别。")
            return
        }
        if (!AuthorizationHelper.hasGlassPermission(GlassPermission.CAMERA)) {
            cameraPreviewText.text = "眼镜相机未授权：请重新授权后再开始制作。"
            render("眼镜相机未授权，无法启动动作识别。")
            return
        }
        autoLoop = true
        takeGlassesPhoto()
    }

    private fun saveGlassesName() {
        glassesName = glassesNameInput.text.toString().trim().ifBlank { "1号操作员" }
        getPreferences(MODE_PRIVATE).edit().putString("glassesName", glassesName).apply()
        render("眼镜名称已保存为：$glassesName")
    }

    private fun clearOrder() {
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (linkReady && link != null) {
            link.sendCustomCmd("cupflow_to_glass", Caps().apply { write("cupflow_clear_order") })
        }
        autoLoop = false
        currentOrder = null
        currentRecipe = null
        stepIndex = 0
        productionStarted = false
        deliveryPending = false
        deliveryOrderId = null
        deliveryToken = null
        deliverySequence += 1
        startAfterDelivery = false
        render("当前订单已清除，请由店长手机扫描下一张杯贴。")
    }

    private fun useRecipeFor(order: CupOrder) {
        currentRecipe = RecipeStore.recipeFor(order, recipeStore.load(order.drink))
    }

    private fun render(message: String) = runOnUiThread {
        status.text = message
        healthText.text = buildHealthText()
        val order = currentOrder
        val recipe = currentRecipe
        orderText.text = if (order == null) "当前无订单" else "订单管理\n${order.id} · ${order.drink}\n配料：${recipe?.ingredients?.ifEmpty { order.options }?.ifEmpty { listOf("无") }?.joinToString("、") ?: "无"}\n当前步骤：${recipe?.steps?.getOrNull(stepIndex)?.title ?: "已完成"}"
        procedureText.text = if (order == null) {
            "扫描杯贴后，这里会显示该订单的制作步骤。"
        } else {
            "制作流程\n" + recipe.orEmptySteps().mapIndexed { index, step ->
                val marker = when {
                    index < stepIndex -> "✓"
                    index == stepIndex -> "●"
                    else -> "○"
                }
                "$marker ${index + 1}. ${step.title}"
            }.joinToString("\n")
        }
    }

    private fun DrinkRecipe?.orEmptySteps() = this?.steps.orEmpty()

    private fun buildHealthText(): String {
        val frame = if (lastFrameAt == 0L) "未收到" else "${((System.currentTimeMillis() - lastFrameAt) / 1000)} 秒前"
        val recognition = when {
            !linkReady -> "已暂停（眼镜未连接）"
            autoLoop && productionStarted -> "制作中"
            autoLoop -> "空闲杯贴识别"
            else -> "已暂停"
        }
        return "眼镜：${if (linkReady) "已连接" else "未连接"}（CXR ${if (cxrConnected) "通" else "断"} / 蓝牙 ${if (bluetoothConnected) "通" else "断"}）\n关键帧：$frame\n视觉服务：${visionSettings.label()} · $visionHealth\n自动识别：$recognition"
    }

    companion object {
        private const val AUTHORIZATION_TIMEOUT_MS = 12_000L
        private const val VOICE_CAPTURE_DURATION_MS = 2_600L
        private const val MAX_VOICE_PCM_BYTES = 320_000
        private const val MIN_VOICE_PCM_BYTES = 1_600
        private const val VOICE_SAMPLE_RATE = 16_000
        private const val VOICE_CHANNELS = 1
        private const val VOICE_BITS_PER_SAMPLE = 16
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val MAX_DELIVERY_ATTEMPTS = 3
        private const val ORDER_CONFIRM_TIMEOUT_MS = 1_500L
        private const val CAMERA_CAPTURE_TIMEOUT_MS = 3_000L
        private const val PREVIEW_CAPTURE_INTERVAL_MS = 500L
        private const val OPERATION_VISION_INTERVAL_MS = 1_400L
        private const val IDLE_LABEL_RESCAN_MS = 8_000L
        private const val MAX_LABEL_IMAGE_BYTES = 4_500_000
        private const val MAX_LABEL_IMAGE_EDGE = 2_048
    }
}
