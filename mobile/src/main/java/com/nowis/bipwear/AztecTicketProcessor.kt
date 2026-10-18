package com.nowis.bipwear

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.util.EnumMap

object AztecTicketProcessor {

    private const val TAG = "AztecTicketProcessor"
    private const val OUTPUT_AZTEC_SIZE = 340 // Pixeles óptimos para pantallas Wear OS

    /**
     * Intenta decodificar el código Aztec del screenshot.
     * Retorna el Bitmap limpio SI Y SÓLO SI se detecta un código válido (Aztec o QR).
     * Retorna null si la pantalla aún muestra el mapa, cargando o cualquier otra vista.
     */
    fun extractTicketBitmap(screenshot: Bitmap): Bitmap? {
        val decodedData = decodeBarcodeFromBitmap(screenshot)
        if (decodedData != null) {
            Log.i(TAG, "¡Código Aztec decodificado con éxito! (${decodedData.take(25)}...)")
            val regenerated = generateCleanAztecBitmap(decodedData, OUTPUT_AZTEC_SIZE)
            if (regenerated != null) {
                return regenerated
            }
        }
        return null
    }

    private fun decodeBarcodeFromBitmap(bitmap: Bitmap): String? {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

        // 1. Intento: Tercio central (donde se ubica la tarjeta bip!QR con el código de barras)
        try {
            val cropW = (bitmap.width * 0.88).toInt()
            val cropH = (bitmap.height * 0.58).toInt()
            val startX = (bitmap.width - cropW) / 2
            val startY = (bitmap.height * 0.20).toInt().coerceAtMost(bitmap.height - cropH)

            val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
            val result = tryDecodeWithVariousBinarizers(cropped, hints)
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Error escaneando área central: ${e.message}")
        }

        // 2. Intento: Cuadrado centrado exacto
        try {
            val size = (bitmap.width * 0.75).toInt()
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2
            val square = Bitmap.createBitmap(bitmap, x, y, size, size)
            val result = tryDecodeWithVariousBinarizers(square, hints)
            if (result != null) return result
        } catch (_: Exception) {}

        // 3. Intento: Pantalla completa
        return tryDecodeWithVariousBinarizers(bitmap, hints)
    }

    private fun tryDecodeWithVariousBinarizers(bitmap: Bitmap, hints: Map<DecodeHintType, Any>): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)

        // Intento A: HybridBinarizer estándar (óptimo para capturas nítidas)
        try {
            val binaryBmp = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBmp, hints)
            if (result != null && result.text.isNotEmpty()) return result.text
        } catch (_: Exception) {}

        // Intento B: GlobalHistogramBinarizer (resistente a variaciones globales)
        try {
            val binaryBmp = BinaryBitmap(GlobalHistogramBinarizer(source))
            val result = MultiFormatReader().decode(binaryBmp, hints)
            if (result != null && result.text.isNotEmpty()) return result.text
        } catch (_: Exception) {}

        // Intento C: Invertido (en caso de tema oscuro o fondo invertido)
        try {
            val invertedSource = source.invert()
            val binaryBmp = BinaryBitmap(HybridBinarizer(invertedSource))
            val result = MultiFormatReader().decode(binaryBmp, hints)
            if (result != null && result.text.isNotEmpty()) return result.text
        } catch (_: Exception) {}

        return null
    }

    /**
     * Genera un mapa de bits nítido a partir de datos Aztec.
     */
    fun generateCleanAztecBitmap(data: String, size: Int = OUTPUT_AZTEC_SIZE): Bitmap? {
        return try {
            val writer = AztecWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.AZTEC, size, size)
            val matrixWidth = bitMatrix.width
            val matrixHeight = bitMatrix.height
            val bitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888)

            for (x in 0 until matrixWidth) {
                for (y in 0 until matrixHeight) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generando Bitmap Aztec limpio", e)
            null
        }
    }

    /**
     * Convierte Bitmap a ByteArray en formato PNG comprimido.
     */
    fun toByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
