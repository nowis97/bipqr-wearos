package com.nowis.bipwear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets

class PhoneWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWearListener"
        const val PATH_REQUEST_TICKET = "/bip/request_ticket"
        const val PATH_QR_REQUEST = "/qrGenerationRequest"
        const val PATH_TICKET_DATA = "/bip/ticket_data"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
        const val PATH_QR_DATA_SYNC = "/qrDataSync"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        Log.i(TAG, "Mensaje recibido desde el reloj ($sourceNodeId) en ruta: $path")

        if (path == PATH_REQUEST_TICKET || path == PATH_QR_REQUEST) {
            handleTicketRequest(sourceNodeId)
        }
    }

    private fun handleTicketRequest(nodeId: String) {
        Log.i(TAG, "Iniciando proceso de generación y captura para el nodo $nodeId")

        BipAccessibilityService.triggerTicketCapture(
            context = this,
            onSuccess = { ticketBytes ->
                serviceScope.launch {
                    sendTicketToWatch(nodeId, ticketBytes)
                }
            },
            onError = { errorMessage ->
                serviceScope.launch {
                    sendErrorToWatch(nodeId, errorMessage)
                }
            }
        )
    }

    private suspend fun sendTicketToWatch(nodeId: String, ticketBytes: ByteArray) {
        try {
            Log.i(TAG, "Transmitiendo pasaje (${ticketBytes.size} bytes) al reloj...")
            val messageClient = Wearable.getMessageClient(this)

            // Enviar por ambas rutas (/bip/ticket_data y /qrDataSync) para compatibilidad absoluta
            messageClient.sendMessage(nodeId, PATH_TICKET_DATA, ticketBytes).await()
            messageClient.sendMessage(nodeId, PATH_QR_DATA_SYNC, ticketBytes).await()

            // También publicar en DataClient (DataMap)
            val putDataMapReq = PutDataMapRequest.create("/bip/ticket").apply {
                dataMap.putByteArray("ticket_image", ticketBytes)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            Wearable.getDataClient(this).putDataItem(putDataMapReq.asPutDataRequest().setUrgent()).await()

            Log.i(TAG, "¡Pasaje enviado exitosamente al smartwatch!")
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al enviar pasaje vía Wearable API", e)
        }
    }

    private suspend fun sendErrorToWatch(nodeId: String, errorMessage: String) {
        try {
            Log.w(TAG, "Enviando notificación de error al reloj: $errorMessage")
            val messageClient = Wearable.getMessageClient(this)
            val errorBytes = errorMessage.toByteArray(StandardCharsets.UTF_8)
            messageClient.sendMessage(nodeId, PATH_TICKET_ERROR, errorBytes).await()
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al transmitir error al reloj", e)
        }
    }
}
