package com.nowis.bipwear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.aztec.AztecWriter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

data class StandaloneConfig(
    val privKeyBase64: String = "",
    val pubKeyBase64: String = "",
    val uuid: String = "",
    val phoneId: String = "",
    val userId: String = "",
    val sessionToken: String = "",
    val rut: String = "",
    val names: String = "",
    val lastNames: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val nuAbt: String = "",
    val idEnrolamiento: Int = Int.MIN_VALUE,
    val tramaSistemaCentral: String = "",
    val serverEpochMs: Long = 0L,
    val deltaEpochMs: Long = 0L,
    val saldo: Int = 0,
    val tiempoRefrescoSec: Int = 5
) {
    val isProvisioned: Boolean
        get() = privKeyBase64.isNotEmpty() &&
            uuid.isNotEmpty() &&
            (tramaSistemaCentral.length == 62 || (sessionToken.isNotEmpty() && nuAbt.isNotEmpty() && rut.isNotEmpty()))

    fun toJson(): JSONObject = JSONObject().apply {
        put("privKeyBase64", privKeyBase64)
        put("pubKeyBase64", pubKeyBase64)
        put("uuid", uuid)
        put("phoneId", phoneId)
        put("userId", userId)
        put("sessionToken", sessionToken)
        put("rut", rut)
        put("names", names)
        put("lastNames", lastNames)
        put("email", email)
        put("phoneNumber", phoneNumber)
        put("nuAbt", nuAbt)
        put("idEnrolamiento", idEnrolamiento)
        put("tramaSistemaCentral", tramaSistemaCentral)
        put("serverEpochMs", serverEpochMs)
        put("deltaEpochMs", deltaEpochMs)
        put("saldo", saldo)
        put("tiempoRefrescoSec", tiempoRefrescoSec)
    }

    companion object {
        fun fromJson(json: JSONObject): StandaloneConfig = StandaloneConfig(
            privKeyBase64 = json.optString("privKeyBase64", ""),
            pubKeyBase64 = json.optString("pubKeyBase64", ""),
            uuid = json.optString("uuid", ""),
            phoneId = json.optString("phoneId", ""),
            userId = json.optString("userId", ""),
            sessionToken = json.optString("sessionToken", ""),
            rut = json.optString("rut", ""),
            names = json.optString("names", ""),
            lastNames = json.optString("lastNames", ""),
            email = json.optString("email", ""),
            phoneNumber = json.optString("phoneNumber", ""),
            nuAbt = json.optString("nuAbt", ""),
            idEnrolamiento = json.optInt("idEnrolamiento", Int.MIN_VALUE),
            tramaSistemaCentral = json.optString("tramaSistemaCentral", ""),
            serverEpochMs = json.optLong("serverEpochMs", 0L),
            deltaEpochMs = json.optLong("deltaEpochMs", 0L),
            saldo = json.optInt("saldo", 0),
            tiempoRefrescoSec = json.optInt("tiempoRefrescoSec", 5).coerceAtLeast(3)
        )
    }
}

data class GeneratedTicketResult(
    val aztecPayload: String,
    val bitmap: Bitmap,
    val tramaExpiryEpochMs: Long,
    val refreshIntervalSec: Int,
    val saldo: Int
)

/**
 * Exact implementation of Sonda's libc2d cryptographic engine (secp224r1 + SHA224withECDSA)
 * and offline Aztec C2D barcode generator.
 */
object BipC2dEngine {
    private const val TAG = "BipC2dEngine"
    private const val PREFS_NAME = "bip_standalone_prefs"
    private const val KEY_CONFIG_JSON = "config_json"

    const val OPERATOR_CODE = 29
    const val APPLICATION_CODE = 1
    const val DEFAULT_PIN = "dpin"

    // Hardcoded alternative PKCS#8 secp224r1 private key from com.sonda.libc2d.alternativo (p329y9.e)
    private const val ALTERNATIVE_PRIV_KEY_B64 =
        "MIGBAgEAMBAGByqGSM49AgEGBSuBBAAhBGowaAIBAQQcBpYxNvkBmlJ5uEizsvy5BRvdIN+XNGU0saliX6AHBgUrgQQAIaE8AzoABKMlxh7xZat19AK1hm8KkCbU0EkWVqWQrUZ1oxvhJwG6jEnO//PVsw6Wodg+/SKv3nE52C5BofHw"

    // Default Santiago center coordinates (Plaza Baquedano / Alameda) used when watch GPS is off
    private const val DEFAULT_LAT = -33.442909
    private const val DEFAULT_LON = -70.653870
    private const val DEFAULT_ACCURACY = 12.0f

    private val bcProvider = BouncyCastleProvider()

    fun loadConfig(context: Context): StandaloneConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        val existing = if (!raw.isNullOrEmpty()) {
            try {
                StandaloneConfig.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved StandaloneConfig", e)
                StandaloneConfig()
            }
        } else {
            StandaloneConfig()
        }

        if (existing.uuid.isNotEmpty() && existing.phoneId.isNotEmpty()) {
            return existing
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val cleanId = if (!androidId.isNullOrBlank() && androidId.length >= 8) {
            androidId.lowercase(Locale.US).take(16)
        } else {
            UUID.randomUUID().toString().replace("-", "").lowercase(Locale.US).take(16)
        }

        val initialized = existing.copy(
            uuid = existing.uuid.ifEmpty { cleanId },
            phoneId = existing.phoneId.ifEmpty { cleanId }
        )
        saveConfig(context, initialized)
        return initialized
    }

    fun saveConfig(context: Context, config: StandaloneConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG_JSON, config.toJson().toString()).apply()
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.trim().toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a new 224-bit EC (secp224r1) keypair exactly like p329y9.e.b()
     * and persists it in StandaloneConfig.
     */
    fun generateNewKeyPair(context: Context): StandaloneConfig {
        val kpg = KeyPairGenerator.getInstance("EC", bcProvider)
        kpg.initialize(ECGenParameterSpec("secp224r1"), SecureRandom())
        val kp = kpg.generateKeyPair()

        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

        val current = loadConfig(context)
        val updated = current.copy(
            privKeyBase64 = privB64,
            pubKeyBase64 = pubB64
        )
        saveConfig(context, updated)
        return updated
    }

    private fun loadPrivateKey(base64Pkcs8: String): PrivateKey {
        val keyBytes = Base64.decode(base64Pkcs8, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC", bcProvider)
        return kf.generatePrivate(spec)
    }

    fun signSha224WithEcdsa(privateKeyBase64: String, data: ByteArray): ByteArray {
        val privateKey = loadPrivateKey(privateKeyBase64)
        val signature = Signature.getInstance("SHA224withECDSA", bcProvider)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    private fun formatUtcCompact(epochMs: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("ddMMyyyyHHmmss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    /**
     * Builds the `data` JSON payload for `/payment/qr/account/enrollment` (idEnrolamiento == null)
     * or `/payment/qr/account/update` (idEnrolamiento != null), matching p329y9.f.d().
     */
    fun createEnrolOrUpdatePayload(
        context: Context,
        rut: String,
        names: String,
        lastNames: String,
        email: String,
        phoneNumber: String,
        idEnrolamiento: Int? = null,
        regenerateKeys: Boolean = true
    ): String {
        val cfg = if (regenerateKeys || loadConfig(context).privKeyBase64.isEmpty()) {
            generateNewKeyPair(context)
        } else {
            loadConfig(context)
        }

        val cleanRut = rut.trim().replace(".", "").uppercase(Locale.US)
        val cleanNames = names.trim().ifEmpty { "Usuario" }.take(25)
        val cleanLastNames = lastNames.trim().ifEmpty { "Red" }.take(25)
        val cleanEmail = email.trim().ifEmpty { "usuario@red.cl" }.take(50)
        val cleanPhone = phoneNumber.trim().ifEmpty { "+56900000000" }.take(20)
        val userId = sha256Hex(cleanRut)

        val updatedCfg = cfg.copy(
            rut = cleanRut,
            userId = userId,
            names = cleanNames,
            lastNames = cleanLastNames,
            email = cleanEmail,
            phoneNumber = cleanPhone,
            idEnrolamiento = idEnrolamiento ?: cfg.idEnrolamiento
        )
        saveConfig(context, updatedCfg)

        val fechaUtc = formatUtcCompact()
        val toSign = "$fechaUtc$OPERATOR_CODE$APPLICATION_CODE${updatedCfg.uuid}"
        val sigBytes = signSha224WithEcdsa(
            updatedCfg.privKeyBase64,
            toSign.toByteArray(StandardCharsets.UTF_8)
        )
        val firmaB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)

        val json = JSONObject().apply {
            if (idEnrolamiento != null && idEnrolamiento != Int.MIN_VALUE) {
                put("idEnrolamiento", idEnrolamiento)
            }
            put("codigoOperador", OPERATOR_CODE)
            put("codigoApp", APPLICATION_CODE)
            put("email", cleanEmail)
            put("apellidos", cleanLastNames)
            put("nombres", cleanNames)
            put("numeroCelular", cleanPhone)
            put("rut", cleanRut)
            put("uuid", updatedCfg.uuid)
            put("publicKey", updatedCfg.pubKeyBase64)
            put("fechaHoraUTCMovil", fechaUtc)
            put("firma", firmaB64)
        }
        return json.toString()
    }

    /**
     * Builds the `data` JSON payload for `/payment/qr/account/generatec2d`, matching p066e9.b.e().
     */
    fun createRequestQrCodePayload(context: Context): String {
        val cfg = loadConfig(context)
        require(cfg.privKeyBase64.isNotEmpty()) { "Llave privada C2D no configurada" }
        require(cfg.nuAbt.isNotEmpty()) { "Cuenta nuAbt no configurada" }

        val fechaUtc = formatUtcCompact()
        val toSign = "${cfg.nuAbt}${cfg.uuid}$fechaUtc"
        val sigBytes = signSha224WithEcdsa(
            cfg.privKeyBase64,
            toSign.toByteArray(StandardCharsets.UTF_8)
        )
        val firmaB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("codigoOperador", OPERATOR_CODE)
            put("codigoApp", APPLICATION_CODE)
            put("nuAbt", cfg.nuAbt)
            put("fechaHoraUTC", fechaUtc)
            put("uuid", cfg.uuid)
            put("firma", firmaB64)
        }
        return json.toString()
    }

    /**
     * Extracts the expiration timestamp (in milliseconds) from `tramaSistemaCentral.substring(20, 28)`
     * (4-byte little-endian unsigned int seconds).
     */
    fun getTramaExpirationEpochMs(tramaSC: String): Long {
        if (tramaSC.length != 62) return 0L
        return try {
            val bigEndianHex = reverseHexBytes(tramaSC.substring(20, 28))
            bigEndianHex.toLong(16) * 1000L
        } catch (e: Exception) {
            Log.e(TAG, "Invalid tramaSC expiration hex", e)
            0L
        }
    }

    fun isTramaValid(context: Context): Boolean {
        val cfg = loadConfig(context)
        if (cfg.tramaSistemaCentral.length != 62 || cfg.privKeyBase64.isEmpty()) return false
        val expMs = getTramaExpirationEpochMs(cfg.tramaSistemaCentral)
        val adjustedNowMs = System.currentTimeMillis() - cfg.deltaEpochMs
        // Keep a 15-second safety margin before expiration
        return expMs > (adjustedNowMs + 15_000L)
    }

    /**
     * 100% Offline Aztec C2D Ticket Generator matching p066e9.b.d() (generateQRCode).
     */
    fun generateOfflineTicket(
        context: Context,
        latitude: Double = DEFAULT_LAT,
        longitude: Double = DEFAULT_LON,
        accuracy: Float = DEFAULT_ACCURACY,
        size: Int = 340
    ): GeneratedTicketResult {
        val cfg = loadConfig(context)
        val tramaSC = cfg.tramaSistemaCentral.trim().uppercase(Locale.US)
        require(tramaSC.length == 62) { "Trama del sistema central inválida o ausente" }
        require(cfg.privKeyBase64.isNotEmpty()) { "Llave criptográfica C2D no disponible" }

        val adjustedNowMs = System.currentTimeMillis() - cfg.deltaEpochMs
        val epochSec = adjustedNowMs / 1000L
        val expMs = getTramaExpirationEpochMs(tramaSC)

        // 1. Epoch seconds as 4-byte little-endian uppercase hex (8 chars)
        val epochBeHex = String.format(Locale.US, "%08X", epochSec and 0xFFFFFFFFL)
        val strB = reverseHexBytes(epochBeHex)

        // 2. Latitude & Longitude truncated to 6 decimals as 8-byte little-endian IEEE-754 double hex (16 chars each)
        val scale = 1_000_000.0
        val latTrunc = floor(latitude * scale) / scale
        val lonTrunc = floor(longitude * scale) / scale

        val latBytes = ByteBuffer.allocate(8).putDouble(latTrunc).array()
        val lonBytes = ByteBuffer.allocate(8).putDouble(lonTrunc).array()

        val strB2 = reverseHexBytes(bytesToHexUppercase(latBytes))
        val lonHex = reverseHexBytes(bytesToHexUppercase(lonBytes))

        // 3. Accuracy rounded to integer as 2-byte little-endian uppercase hex (4 chars)
        val accInt = accuracy.roundToInt().coerceIn(0, 0xFFFF)
        val accHex = reverseHexBytes(String.format(Locale.US, "%04X", accInt))

        // 4. Telemetry block (44 uppercase hex chars = 22 bytes)
        val str2 = "$strB2$lonHex$strB$accHex"

        // 5. Prefix of final Aztec code: first 30 hex chars (15 bytes) of tramaSC + 44 hex chars of str2
        val strConcat = tramaSC.substring(0, 30) + str2

        // 6. Select key based on bit 0 of byte 14 (tramaSC.substring(28, 30))
        val keySelectorByte = tramaSC.substring(28, 30).toInt(16)
        val signingKeyB64 = if ((keySelectorByte and 1) > 0) {
            ALTERNATIVE_PRIV_KEY_B64
        } else {
            cfg.privKeyBase64
        }

        // 7. Sign raw bytes of (full 62-char tramaSC + 44-char str2 = 106 hex chars = 53 bytes)
        val rawBytesToSign = hexToBytes(tramaSC + str2)
        val sigBytes = signSha224WithEcdsa(signingKeyB64, rawBytesToSign)
        val strB3 = bytesToHexUppercase(sigBytes)

        val finalAztecString = strConcat + strB3
        val bitmap = renderAztecBitmap(finalAztecString, size)

        return GeneratedTicketResult(
            aztecPayload = finalAztecString,
            bitmap = bitmap,
            tramaExpiryEpochMs = expMs,
            refreshIntervalSec = cfg.tiempoRefrescoSec,
            saldo = cfg.saldo
        )
    }

    fun renderAztecBitmap(data: String, size: Int = 340): Bitmap {
        val writer = AztecWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.AZTEC, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Reverses byte order of a hex string in pairs of 2 characters (matches p329y9.g.b()).
     */
    fun reverseHexBytes(hex: String): String {
        val padded = if (hex.length % 2 != 0) "0$hex" else hex
        val sb = StringBuilder(padded.length)
        var i = padded.length - 2
        while (i >= 0) {
            sb.append(padded[i]).append(padded[i + 1])
            i -= 2
        }
        return sb.toString().uppercase(Locale.US)
    }

    fun bytesToHexUppercase(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
