package com.cupflow.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.toBitmap
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
  private var imageCapture: ImageCapture? = null
  private val executor = Executors.newSingleThreadExecutor()
  private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    setContent { GlassesScreen() }
  }

  @Composable
  private fun GlassesScreen() {
    val context = LocalContext.current
    var host by remember { mutableStateOf("http://10.0.2.2:3000") }
    var status by remember { mutableStateOf("等待连接 CupFlow 服务") }
    val tts = remember { TextToSpeech(context) { } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    MaterialTheme {
      Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CupFlow Glasses", style = MaterialTheme.typography.titleLarge)
        Text("第一视角采集端 · 只显示关键步骤与风险提醒")
        OutlinedTextField(host, { host = it }, label = { Text("CupFlow 服务地址") }, modifier = Modifier.fillMaxWidth())
        CameraPreview(Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
          val capture = imageCapture
          if (capture == null) {
            status = "摄像头尚未就绪"
          } else {
            status = "正在分析关键帧…"
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
              val bitmap = image.toBitmap()
              image.close()
              executor.execute {
                runCatching { AgentClient(AgentConfig(host)).inspect(bitmap) }
                  .onSuccess { result -> runOnUiThread {
                    status = "${result.outcome}：${result.reason}"
                    tts.language = Locale.SIMPLIFIED_CHINESE
                    tts.speak(result.reason, TextToSpeech.QUEUE_FLUSH, null, result.traceId)
                  }}
                  .onFailure { error -> runOnUiThread { status = error.message ?: "服务不可用" } }
              }
            }
            override fun onError(exception: ImageCaptureException) { runOnUiThread { status = "拍摄失败：${exception.message}" } }
            })
          }
        }) { Text("分析当前关键帧") }
      }
    }
  }

  @Composable
  private fun CameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(modifier = modifier, factory = { previewView ->
      val providerFuture = ProcessCameraProvider.getInstance(context)
      providerFuture.addListener({
        val provider = providerFuture.get()
        val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        provider.unbindAll()
        provider.bindToLifecycle(this@MainActivity, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
      }, ContextCompat.getMainExecutor(context))
      previewView
    })
  }
}
