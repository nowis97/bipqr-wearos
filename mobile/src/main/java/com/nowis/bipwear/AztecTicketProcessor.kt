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
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.util.EnumMap

object AztecTicketProcessor {

    private const val TAG = "AztecTicketProcessor"
    private const val OUTPUT_AZTEC_SIZE = 340 // Pixeles óptimos para pantallas Wear OS

    /**
     * Intenta decodificar el código Aztec del screenshot. Si tiene éxito, regenera
     * una imagen perfecta, nítida y de alto contraste lista para los validadores del metro/bus.
     * Si falla la decodificación, recorta el área central como alternativa.
     */
    fun extractTicketBitmap(screenshot: Bitmap): Bitmap {
        try {
            val decodedData = decodeAztecFromBitmap(screenshot)
            if (decodedData != null) {
                Log.d(TAG, "Código Aztec decodificado con éxito. Contenido: ${decodedData.take(20)}...")
                val regenerated = generateCleanAztecBitmap(decodedData, OUTPUT_AZTEC_SIZE)
                if (regenerated != null) {
                    return regenerated
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al procesar Aztec mediante ZXing: ${e.message}")
        }

        // Fallback: Recorta el centro de la pantalla donde se sitúa el código bip!QR
        Log.i(TAG, "Usando recorte óptico central como fallback")
        return cropCenterSquare(screenshot)
    }

    private fun decodeAztecFromBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

        return try {
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap, hints)
            result.text
        } catch (e: Exception) {
            // Reintentar sólo en la mitad central de la pantalla
            tryCenterCropDecode(bitmap)
        }
    }

    private fun tryCenterCropDecode(bitmap: Bitmap): String? {
        val cropW = (bitmap.width * 0.85).toInt()
        val cropH = (bitmap.height * 0.55).toInt()
        val startX = (bitmap.width - cropW) / 2
        val startY = (bitmap.height - cropH) / 2

        val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
        val pixels = IntArray(cropW * cropH)
        cropped.getPixels(pixels, 0, cropW, 0, 0, cropW, cropH)

        val source = RGBLuminanceSource(cropW, cropH, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)
        }

        return try {
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap, hints)
            result.text
        } catch (e: Exception) {
            null
        }
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
     * Recorta un cuadrado centrado de la pantalla.
     */
    private fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val size = (bitmap.width * 0.75).toInt()
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        val cropped = Bitmap.createBitmap(bitmap, x, y, size, size)
        return Bitmap.createScaledBitmap(cropped, OUTPUT_AZTEC_SIZE, OUTPUT_AZTEC_SIZE, true)
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
