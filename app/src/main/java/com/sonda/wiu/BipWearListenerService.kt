package com.sonda.wiu

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class BipWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "BipWearListener"
        const val PATH_QR_DATA_SYNC = "/qrDataSync"
        const val PATH_QR_GEN_ENABLED_RESPONSE = "/qrGenerationEnabledResponse"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        Log.d(TAG, "Mensaje entrante de Red Movilidad en path: $path, bytes: ${data?.size ?: 0}")

        when (path) {
            PATH_QR_DATA_SYNC -> {
                if (data != null && data.isNotEmpty()) {
                    val success = TicketManager.processPayload(data)
                    if (success) {
                        // Traer la app al frente si estaba en segundo plano
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    }
                }
            }
            PATH_QR_GEN_ENABLED_RESPONSE -> {
                Log.d(TAG, "Handshake de generación habilitada recibido")
            }
            else -> {
                Log.d(TAG, "Ruta no manejada: $path")
            }
        }
    }
}
