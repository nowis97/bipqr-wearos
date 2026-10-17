package com.sonda.wiu

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
    val expiryTimestampMs: Long = System.currentTimeMillis() + (120 * 1000)
)

sealed class TicketState {
    object Idle : TicketState()
    data class Loading(val message: String) : TicketState()
    data class Active(val ticket: TicketData) : TicketState()
    data class Error(val errorMessage: String) : TicketState()
}

object TicketManager {
    private const val TAG = "TicketManager"

    private val _ticketState = MutableStateFlow<TicketState>(TicketState.Idle)
    val ticketState: StateFlow<TicketState> = _ticketState.asStateFlow()

    fun setState(state: TicketState) {
        _ticketState.value = state
    }

    /**
     * Procesa eventos de DataClient (PutDataMapRequest / DataItem) provenientes de Red Movilidad.
     */
    suspend fun processDataMap(dataClient: DataClient, dataMap: DataMap): Boolean {
        Log.d(TAG, "Procesando DataMap con ${dataMap.size()} llaves: ${dataMap.keySet()}")

        // 1. Buscar en archivos adjuntos (la app oficial usa mVar.h("file#" + key, mVar2 con asset))
        for (key in dataMap.keySet()) {
            if (key.startsWith("file#")) {
                val fileDataMap = dataMap.getDataMap(key)
                val asset = fileDataMap?.getAsset("asset")
                if (asset != null) {
                    Log.d(TAG, "Encontrado asset en sub-map: $key")
                    val bitmap = loadBitmapFromAsset(dataClient, asset)
                    if (bitmap != null) {
                        _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                        return true
                    }
                }
            }
        }

        // 2. Buscar Asset directo
        for (key in dataMap.keySet()) {
            val asset = dataMap.getAsset(key)
            if (asset != null) {
                Log.d(TAG, "Encontrado asset directo en llave: $key")
                val bitmap = loadBitmapFromAsset(dataClient, asset)
                if (bitmap != null) {
                    _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                    return true
                }
            }
        }

        // 3. Buscar ByteArray directo (rawMapData)
        for (key in dataMap.keySet()) {
            val bytes = dataMap.getByteArray(key)
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    Log.d(TAG, "Encontrada imagen en ByteArray de llave: $key")
                    _ticketState.value = TicketState.Active(TicketData(bitmap = bitmap))
                    return true
                }
            }
        }

        // 4. Buscar String (Base64 o JSON)
        for (key in dataMap.keySet()) {
            val str = dataMap.getString(key)
            if (!str.isNullOrEmpty()) {
                val decoded = decodeStringOrJson(str)
                if (decoded != null) {
                    Log.d(TAG, "Encontrada imagen decodificada desde String en llave: $key")
                    _ticketState.value = TicketState.Active(TicketData(bitmap = decoded))
                    return true
                }
            }
        }

        Log.e(TAG, "No se encontró ninguna imagen válida en el DataMap")
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

    /**
     * Procesa los bytes recibidos desde Red Movilidad vía Bluetooth (/qrDataSync por MessageClient).
     */
    fun processPayload(data: ByteArray): Boolean {
        try {
            val directBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (directBitmap != null) {
                Log.d(TAG, "Imagen recibida y decodificada directamente (${directBitmap.width}x${directBitmap.height})")
                _ticketState.value = TicketState.Active(TicketData(bitmap = directBitmap))
                return true
            }

            val text = String(data, StandardCharsets.UTF_8).trim()
            val decoded = decodeStringOrJson(text)
            if (decoded != null) {
                Log.d(TAG, "Imagen JSON decodificada exitosamente")
                _ticketState.value = TicketState.Active(TicketData(bitmap = decoded))
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando payload de boleto", e)
            _ticketState.value = TicketState.Error("Error al procesar boleto: ${e.message}")
            return false
        }

        _ticketState.value = TicketState.Error("Formato de pasaje no reconocido")
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
