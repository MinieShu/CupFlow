package com.cupflow.glass

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/** CXR-S endpoint for the phone Companion's CUSTOMAPP messages. */
class GlassCommandBridge(private val onCommand: (List<String>) -> Unit) {
    private val bridge = CXRServiceBridge()

    init {
        // Registers this foreground glass app as the active CXR command endpoint.
        bridge.appLaunch()
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(peer: String?, deviceId: String?, deviceType: Int) {
                Log.d("CupFlowCXR", "connected")
                bridge.sendMessage(GLASS_TO_PHONE, Caps().apply { write("cupflow_glass_opened") })
            }
            override fun onDisconnected() { Log.d("CupFlowCXR", "disconnected") }
            override fun onConnecting(peer: String?, deviceId: String?, deviceType: Int) {}
            override fun onARTCStatus(value: Float, active: Boolean) {}
            override fun onRokidAccountChanged(accountId: String?) {}
            override fun onAudioNoise(value: Float) {}
        })
        bridge.subscribe(PHONE_TO_GLASS, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                val values = args?.let { caps ->
                    (0 until caps.size()).map { index -> caps.at(index).string.orEmpty() }
                }.orEmpty()
                if (values.isNotEmpty()) onCommand(values)
            }
        })
    }

    fun sendEvent(event: String, vararg details: String) {
        bridge.sendMessage(GLASS_TO_PHONE, Caps().apply {
            write(event)
            details.forEach(::write)
        })
    }

    fun close() {
        bridge.disconnectCXRDevice()
    }

    companion object {
        const val PHONE_TO_GLASS = "cupflow_to_glass"
        const val GLASS_TO_PHONE = "cupflow_to_phone"
    }
}
