package com.nowis.bipwear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class TicketData(
    val bitmap: Bitmap,
    val expiryTimestampMs: Long = System.currentTimeMillis() + (120 * 1000),
    val refreshIntervalSec: Int = 5,
    val saldo: Int = 0
)

sealed class TicketState {
    object Idle : TicketState()
    data class Loading(val message: String) : TicketState()
    data class Active(val ticket: TicketData) : TicketState()
    data class Error(val errorMessage: String) : TicketState()
    object Movements : TicketState()
}

object TicketManager {
    private const val TAG = "TicketManager"
    const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"

    private val _ticketState = MutableStateFlow<TicketState>(TicketState.Idle)
    val ticketState: StateFlow<TicketState> = _ticketState.asStateFlow()

    private val _configVersion = MutableStateFlow(0L)
    val configVersion: StateFlow<Long> = _configVersion.asStateFlow()

    fun setState(state: TicketState) {
        _ticketState.value = state
    }

    fun notifyConfigUpdated() {
        _configVersion.value = System.currentTimeMillis()
    }

    /**
     * Imports a JSON StandaloneConfig received from the phone during one-time provisioning
     * or background C2D seed refresh.
     */
    fun importStandaloneConfigJson(context: Context, jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val cfg = StandaloneConfig.fromJson(json)
            if (cfg.privKeyBase64.isNotEmpty() || cfg.tramaSistemaCentral.isNotEmpty()) {
                BipC2dEngine.saveConfig(context, cfg)
                notifyConfigUpdated()
                Log.i(TAG, "StandaloneConfig sincronizada en el reloj (rut=${cfg.rut}, nuAbt=${cfg.nuAbt})")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importando StandaloneConfig JSON", e)
            false
        }
    }

    suspend fun processDataMap(context: Context, dataClient: DataClient, dataMap: DataMap): Boolean {
        Log.d(TAG, "Procesando DataMap con ${dataMap.size()} llaves: ${dataMap.keySet()}")

        val configJson = dataMap.getString("config_json")
        if (!configJson.isNullOrEmpty()) {
            if (importStandaloneConfigJson(context, configJson)) {
                return true
            }
        }

        // Legacy / fallback image support
        for (key in dataMap.keySet()) {
            if (key.startsWith("file#")) {
                val fileDataMap = dataMap.getDataMap(key)
                val asset = fileDataMap?.getAsset("asset")
                if (asset != null) {
                    val bitmap = loadBitmapFromAsset(dataClient, asset)
                    if (bitmap != null) {
                        _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                        return true
                    }
                }
            }
        }

        for (key in dataMap.keySet()) {
            val asset = dataMap.getAsset(key)
            if (asset != null) {
                val bitmap = loadBitmapFromAsset(dataClient, asset)
                if (bitmap != null) {
                    _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                    return true
                }
            }
        }

        for (key in dataMap.keySet()) {
            val bytes = dataMap.getByteArray(key)
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                    return true
                }
            }
        }

        for (key in dataMap.keySet()) {
            val str = dataMap.getString(key)
            if (!str.isNullOrEmpty()) {
                val decoded = decodeStringOrJson(str)
                if (decoded != null) {
                    _ticketState.value = TicketState.Active(TicketData(bitmap = decoded))
                    return true
                }
            }
        }

        return false
    }

    private suspend fun loadBitmapFromAsset(dataClient: DataClient, asset: Asset): Bitmap? {
        return try {
            val fd = dataClient.getFdForAsset(asset).await() ?: return null
            fd.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al decodificar Asset de imagen", e)
            null
        }
    }

    fun processPayload(context: Context, data: ByteArray): Boolean {
        try {
            val text = String(data, StandardCharsets.UTF_8).trim()
            if (text.startsWith("{") && text.contains("privKeyBase64")) {
                return importStandaloneConfigJson(context, text)
            }

            val directBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (directBitmap != null) {
                _ticketState.value = TicketState.Active(TicketData(bitmap = directBitmap))
                return true
            }

            val decoded = decodeStringOrJson(text)
            if (decoded != null) {
                _ticketState.value = TicketState.Active(TicketData(bitmap = decoded))
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando payload", e)
            _ticketState.value = TicketState.Error("Error al procesar datos: ${e.message}")
            return false
        }
        return false
    }

    private fun decodeStringOrJson(text: String): Bitmap? {
        try {
            if (text.startsWith("{") && text.endsWith("}")) {
                val json = JSONObject(text)
                val base64Img = json.optString("image", json.optString("data", json.optString("max-image", "")))
                if (base64Img.isNotEmpty()) {
                    val decodedBytes = Base64.decode(base64Img, Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                }
            } else if (text.length > 100) {
                val decodedBytes = Base64.decode(text, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No es string base64 ni json decodificable: ${e.message}")
        }
        return null
    }
}
