package com.nowis.bipwear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class BipWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "BipWearListener"
        const val PATH_TICKET_DATA = "/bip/ticket_data"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
        const val PATH_QR_DATA_SYNC = "/qrDataSync"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged recibido con ${dataEvents.count} eventos")
        val dataClient = Wearable.getDataClient(this)

        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path ?: ""
                Log.d(TAG, "DataEvent TYPE_CHANGED en path: $path, URI: $uri")

                if (path.startsWith("/qr") || path.startsWith("/bip")) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    serviceScope.launch {
                        val success = TicketManager.processDataMap(dataClient, dataMap)
                        if (success) {
                            openMainActivity()
                        }
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        Log.d(TAG, "onMessageReceived en path: $path, bytes: ${data?.size ?: 0}")

        when {
            path == PATH_TICKET_ERROR -> {
                val errorMsg = if (data != null && data.isNotEmpty()) {
                    String(data, StandardCharsets.UTF_8)
                } else {
                    "Error desconocido en el teléfono"
                }
                TicketManager.setState(TicketState.Error(errorMsg))
                openMainActivity()
            }
            path.startsWith("/bip") || path.startsWith("/qr") -> {
                if (data != null && data.isNotEmpty()) {
                    val success = TicketManager.processPayload(data)
                    if (success) {
                        openMainActivity()
                    }
                }
            }
            else -> {
                Log.d(TAG, "Ruta de mensaje no manejada: $path")
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
