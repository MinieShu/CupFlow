package com.cupflow.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import java.util.ArrayDeque
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
    private lateinit var orderText: TextView
    private lateinit var procedureText: TextView
    private lateinit var glassesNameInput: EditText
    private var glassesName = "1号操作员"
    private var currentOrder: CupOrder? = null
    private var currentRecipe: DrinkRecipe? = null
    private var stepIndex = 0
    private var productionStarted = false
    private var linkReady = false
    private var cxrConnected = false
    private var bluetoothConnected = false
    private var captureBusy = false
    private var analysisBusy = false
    private var exceptionSaving = false
    private var lastVisionAt = 0L
    private var autoLoop = false
    private var latestFrame: CapturedFrame? = null
    private var latestFingerprint: IntArray? = null
    private var lastVisionFingerprint: IntArray? = null
    private var lastFrameAt = 0L
    private var visionHealth = "等待视觉服务检查"
    private var authorizationPending = false
    private var authorizationRequestedAt = 0L
    private var voiceCaptureActive = false
    private var voiceCaptureSession = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glassesName = getPreferences(MODE_PRIVATE).getString("glassesName", glassesName) ?: glassesName
        setContentView(buildView())
        val app = application as CupFlowCompanionApplication
        if (app.token.isBlank()) render("请先授权并连接 Rokid AI App。")
        else window.decorView.post { connect(app.token) }
    }

    override fun onDestroy() {
        autoLoop = false
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
        healthText = text("", 13f, 0xff39554b.toInt())
        healthCard.addView(healthText)
        healthCard.addView(button("重新连接眼镜") { reconnectGlasses() })
        healthCard.addView(button("恢复当前订单识别") { resumeRecognition() })
        healthCard.addView(button("配置视觉服务地址") { configureVisionEndpoint() })
        healthCard.addView(button("查看识别决策日志") { startActivity(Intent(this, DecisionLogActivity::class.java)) })
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
        runCatching {
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
            phoneScanRequest -> handlePhonePhoto(data)
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
                (application as CupFlowCompanionApplication).token = result.token
                AuthTokenStore(this).save(result.token)
                connect(result.token)
            }
            is AuthResult.AuthFail -> render("Rokid 授权失败，请在 Rokid AI App 中允许 CupFlow。")
            else -> render("授权已取消。")
        }
    }

    private fun connect(token: String) {
        val app = application as CupFlowCompanionApplication
        autoLoop = false
        linkReady = false
        cxrConnected = false
        bluetoothConnected = false
        app.token = token
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
                }
                override fun onGlassBtConnected(connected: Boolean) {
                    if (app.cxrLink !== link) return
                    bluetoothConnected = connected
                    updateLinkReady()
                }
                override fun onGlassAiAssistStart() {}
                override fun onGlassAiAssistStop() {}
                override fun onGlassAiInterrupt(interrupt: Boolean) {}
                override fun onGlassDeviceInfo(info: com.rokid.cxr.link.utils.GlassInfo) {}
                override fun onGlassWearingStatus(wearing: Boolean) {}
            })
            setCXRImageCbk(object : IImageStreamCbk {
                override fun onImageReceived(data: ByteArray?) {
                    captureBusy = false
                    data?.let(::handleGlassesFrame) ?: render("眼镜未返回图片。")
                }
                override fun onImageError(code: Int, msg: String?) {
                    captureBusy = false
                    render("眼镜拍照失败：$code ${msg.orEmpty()}")
                }
            })
            setCXRAudioCbk(object : IAudioStreamCbk {
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
            setCXRCustomCmdCbk(object : ICustomCmdCbk {
                override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
                    if (key != "cupflow_to_phone") return
                    val values = payload?.let { caps -> Caps.fromBytes(caps) }?.let { caps ->
                        (0 until caps.size()).map { index -> caps.at(index).string.orEmpty() }
                    }.orEmpty()
                    val event = values.firstOrNull().orEmpty()
                    runOnUiThread {
                        when (event) {
                            "cupflow_glass_opened" -> {
                                currentOrder?.takeIf { !productionStarted }?.let(::dispatchOrder)
                                render("● 眼镜端 CupFlow 已启动，等待店长端下发订单。")
                            }
                            "cupflow_started" -> {
                                if (currentOrder != null) {
                                    productionStarted = true
                                    render("已开始制作：等待${currentRecipe?.steps?.getOrNull(stepIndex)?.title.orEmpty()}。")
                                    startAutoRecognition()
                                }
                            }
                            "cupflow_skip" -> recordManualSkip(values.getOrNull(1))
                            "cupflow_voice_start" -> startGlassesVoiceCapture()
                            "cupflow_voice_stop" -> stopGlassesVoiceCapture()
                        }
                    }
                }
            })
        }
        if (!link.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, "com.cupflow.glass"))) {
            render("CupFlow 无法创建 Rokid 会话，请先在眼镜端打开 CupFlow 后重试。")
            return
        }
        app.cxrLink = link
        startCxrConnection(app, link, token)
    }

    private fun startCxrConnection(app: CupFlowCompanionApplication, link: CXRLink, token: String) {
        if (!link.connect(token)) {
            render("Rokid 媒体服务暂未接受连接。请不要连续重试；关闭后重新打开 CupFlow，再授权一次。")
            return
        }
        render("正在连接 Rokid 媒体服务…")
        window.decorView.postDelayed({
            if (app.cxrLink !== link) return@postDelayed
            when {
                !cxrConnected -> {
                    render("Rokid 媒体服务 10 秒未响应。请关闭后重新打开 CupFlow，再授权一次。")
                }
                !bluetoothConnected -> render("Rokid 服务已连接，但未检测到眼镜数据通道。请在 Rokid AI App 确认眼镜已连接后重试。")
            }
        }, 10_000)
    }

    private fun updateLinkReady() = runOnUiThread {
        linkReady = cxrConnected && bluetoothConnected
        if (!linkReady) autoLoop = false
        val message = when {
            linkReady -> "● 眼镜数据通道已连接，等待眼镜端 CupFlow 启动。"
            cxrConnected -> "Rokid 服务已连接，正在等待眼镜数据通道。"
            else -> "○ 眼镜连接中断，当前步骤已保持。"
        }
        render(message)
        if (linkReady) currentOrder?.takeIf { !productionStarted }?.let(::dispatchOrder)
    }

    private fun scanCupLabelFromPhone() {
        startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), phoneScanRequest)
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
        if (token.isBlank()) requestAuthorization() else connect(token)
    }

    private fun connectAuthorizedGlasses() {
        val token = (application as CupFlowCompanionApplication).token
        if (token.isBlank()) {
            render("尚无已保存授权，请先重新授权眼镜。")
            return
        }
        connect(token)
    }

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

    private fun handlePhonePhoto(data: Intent?) {
        val bitmap = data?.extras?.get("data") as? Bitmap ?: run {
            render("未取得手机扫描照片。")
            return
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        analyzeLabel(output.toByteArray())
    }

    private fun takeGlassesPhoto() {
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (!linkReady || link == null || captureBusy) {
            render("眼镜尚未连接或正在拍照。")
            return
        }
        captureBusy = true
        link.takePhoto(1024, 768, 80)
    }

    private fun handleGlassesFrame(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        lastFrameAt = now
        val frame = CapturedFrame(now, bytes)
        synchronized(frames) {
            frames.addLast(frame)
            while (frames.isNotEmpty() && now - frames.first.at > 5_000) frames.removeFirst()
        }
        frameExecutor.execute {
            val fingerprint = fingerprint(bytes) ?: return@execute
            runOnUiThread {
                latestFrame = frame
                latestFingerprint = fingerprint
                evaluateLatestFrame()
            }
        }
    }

    /**
     * Frames arrive at 2 FPS for responsive evidence capture. The cloud model sees a new
     * frame as soon as the scene differs materially, never receives a backlog, and gets a
     * 7-second safety recheck if the scene appears static.
     */
    private fun evaluateLatestFrame() {
        val frame = latestFrame ?: return
        val fingerprint = latestFingerprint ?: return
        if (analysisBusy || !autoLoop) return
        val now = System.currentTimeMillis()
        val sceneChanged = sceneDifference(lastVisionFingerprint, fingerprint) >= 12
        val dueForSafetyCheck = now - lastVisionAt >= 7_000
        if (!sceneChanged && !dueForSafetyCheck) return
        if (now - lastVisionAt < 800) return
        lastVisionFingerprint = fingerprint
        val order = currentOrder
        if (order != null && productionStarted) analyzeOperation(frame.bytes, order)
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

    private fun analyzeLabel(bytes: ByteArray) {
        analysisBusy = true
        lastVisionAt = System.currentTimeMillis()
        render("店长手机正在识别杯贴…")
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
                    if (id.isBlank() || drink.isBlank() || result.confidence < 0.85) {
                        decisionLogStore.append(null, "杯贴识别", result, "等待人工核对", "订单字段不完整或置信度不足")
                        render("杯贴识别不完整或置信度不足，请重新扫描后手动核对。")
                    } else {
                        currentOrder = CupOrder(id, drink, options)
                        stepIndex = 0
                        productionStarted = false
                        useRecipeFor(currentOrder!!)
                        dispatchOrder(currentOrder!!)
                        decisionLogStore.append(currentOrder, "杯贴识别", result, "订单已下发", result.reason.ifBlank { "高置信度订单识别" })
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

    private fun analyzeOperation(bytes: ByteArray, order: CupOrder) {
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
                val result = vision.analyze(bytes, "operation", order, expected.title, gridContext)
                visionHealth = "正常"
                runOnUiThread {
                    finishVisionCycle()
                    when {
                        result.event == expected.event && result.confidence >= 0.75 -> {
                            decisionLogStore.append(order, expected.title, result, "推进步骤", result.reason.ifBlank { "事件与当前步骤匹配" })
                            advance(result)
                        }
                        result.event in setOf("wrong", "wrongLabel", "overfill") -> {
                            decisionLogStore.append(order, expected.title, result, "异常提醒", result.reason.ifBlank { "检测到不匹配操作" })
                            saveException(result)
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

    private fun dispatchOrder(order: CupOrder) {
        val link = (application as CupFlowCompanionApplication).cxrLink
        if (link == null || !linkReady) {
            render("订单已读取；等待眼镜连接后再下发。")
            return
        }
        link.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_order")
            write(order.id)
            write(order.drink)
            write(order.options.joinToString("、"))
            write(glassesName)
            write(JSONArray(currentRecipe?.steps?.map { it.title }.orEmpty()).toString())
        })
        render("已下发订单，请在眼镜点击或说“开始制作”。")
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
        dispatchOrder(order)
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
        render("异常 ${result.event}：${result.reason}。正在保留前后 2 秒关键帧…")
        (application as CupFlowCompanionApplication).cxrLink?.sendCustomCmd("cupflow_to_glass", Caps().apply {
            write("cupflow_alert")
            write(result.reason.ifBlank { "当前操作异常，请纠正后继续。" })
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
        autoLoop = true
        tickAutoLoop()
    }

    private fun tickAutoLoop() {
        if (!autoLoop) return
        takeGlassesPhoto()
        window.decorView.postDelayed({ tickAutoLoop() }, 500)
    }

    private fun saveGlassesName() {
        glassesName = glassesNameInput.text.toString().trim().ifBlank { "1号操作员" }
        getPreferences(MODE_PRIVATE).edit().putString("glassesName", glassesName).apply()
        render("眼镜名称已保存为：$glassesName")
    }

    private fun clearOrder() {
        autoLoop = false
        currentOrder = null
        currentRecipe = null
        stepIndex = 0
        productionStarted = false
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
    }
}
