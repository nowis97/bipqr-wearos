package com.sonda.wiu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Procesa los bytes recibidos desde Red Movilidad vía Bluetooth (/qrDataSync).
     * Puede llegar como:
     * 1) Bytes directos de imagen comprimida (PNG / JPEG / WebP) generados por compressImages.
     * 2) Trama JSON empaquetada conteniendo base64 y fecha de expiración.
     */
    fun processPayload(data: ByteArray): Boolean {
        try {
            // Intento 1: Decodificación directa de mapa de bits
            val directBitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (directBitmap != null) {
                Log.d(TAG, "Imagen recibida y decodificada directamente (${directBitmap.width}x${directBitmap.height})")
                _ticketState.value = TicketState.Active(TicketData(bitmap = directBitmap))
                return true
            }

            // Intento 2: JSON estructurado
            val text = String(data, StandardCharsets.UTF_8).trim()
            if (text.startsWith("{") && text.endsWith("}")) {
                val json = JSONObject(text)
                val base64Img = json.optString("image", json.optString("data", ""))
                val expiryStr = json.optString("expiryDate", "")

                if (base64Img.isNotEmpty()) {
                    val decodedBytes = Base64.decode(base64Img, Base64.DEFAULT)
                    val jsonBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (jsonBitmap != null) {
                        Log.d(TAG, "Imagen JSON decodificada exitosamente")
                        _ticketState.value = TicketState.Active(TicketData(bitmap = jsonBitmap))
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando payload de boleto", e)
            _ticketState.value = TicketState.Error("Error al procesar boleto: ${e.message}")
            return false
        }

        _ticketState.value = TicketState.Error("Formato de pasaje no reconocido")
        return false
    }
}
