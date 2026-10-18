package com.nowis.bipwear

import android.content.Context
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
        const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"
        const val PATH_REFRESH_SEED = "/bip/refresh_seed"
        const val PATH_REQUEST_TICKET = "/bip/request_ticket"
        const val PATH_QR_REQUEST = "/qrGenerationRequest"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"

        suspend fun pushConfigToWatches(context: Context, config: StandaloneConfig): Int {
            val jsonStr = config.toJson().toString()
            val jsonBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

            // 1. Persist in Wearable DataClient so the watch receives it even if asleep
            val putReq = PutDataMapRequest.create(PATH_STANDALONE_CONFIG).apply {
                dataMap.putString("config_json", jsonStr)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(putReq).await()

            // 2. Send immediate real-time message to all connected watch nodes
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val messageClient = Wearable.getMessageClient(context)
            for (node in nodes) {
                messageClient.sendMessage(node.id, PATH_STANDALONE_CONFIG, jsonBytes).await()
            }
            return nodes.size
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        Log.i(TAG, "Mensaje recibido desde el reloj ($sourceNodeId) en ruta: $path")

        if (path == PATH_REFRESH_SEED || path == PATH_REQUEST_TICKET || path == PATH_QR_REQUEST) {
            handleSilentSeedRefresh(sourceNodeId)
        }
    }

    /**
     * Silently renews the 2-hour `tramaSistemaCentral` seed via HTTPS in the background
     * without waking the phone screen or opening any app, and pushes the updated config to the watch.
     */
    private fun handleSilentSeedRefresh(nodeId: String) {
        serviceScope.launch {
            try {
                val updatedCfg = RedApiClient.requestC2dSeed(applicationContext)
                pushConfigToWatches(applicationContext, updatedCfg)
                Log.i(TAG, "Semilla C2D renovada silenciosamente y enviada al reloj ($nodeId)")
            } catch (e: Exception) {
                Log.e(TAG, "Error renovando semilla C2D en segundo plano", e)
                try {
                    val msg = ("Error C2D: ${e.localizedMessage ?: "Fallo de red"}").toByteArray(StandardCharsets.UTF_8)
                    Wearable.getMessageClient(applicationContext).sendMessage(nodeId, PATH_TICKET_ERROR, msg).await()
                } catch (_: Exception) {
                }
            }
        }
    }
}
