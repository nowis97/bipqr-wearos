package com.nowis.bipwear

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ClaveUnicaStartInfo(
    val authUrl: String,
    val sessionToken: String,
    val redirectUri: String
)

data class ClaveUnicaUserProfile(
    val rut: String,
    val names: String,
    val lastNames: String,
    val email: String,
    val nickname: String,
    val phoneNumber: String
)

object RedApiClient {
    private const val TAG = "RedApiClient"

    private val BASE_URLS = listOf(
        "https://appred.tstgo.cl/phone/v13",
        "https://appred2.tstgo.cl/phone/v13"
    )
    private const val APP_VERSION = "5.18.6"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000

    private fun currentUsageDay(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    private fun encodeForm(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun applyHeaders(conn: HttpURLConnection, cfg: StandaloneConfig) {
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Os-Type", "Android")
        conn.setRequestProperty("App-Version", APP_VERSION)
        conn.setRequestProperty("Phone-Id", cfg.phoneId)
        conn.setRequestProperty("User-Id", cfg.userId)
        conn.setRequestProperty("Session-Token", cfg.sessionToken)
        conn.setRequestProperty("Usage-Days", currentUsageDay())
    }

    private fun requestWithFallback(
        cfg: StandaloneConfig,
        method: String,
        endpointPath: String,
        formParams: Map<String, String> = emptyMap()
    ): JSONObject {
        var lastError: Exception? = null
        for (baseUrl in BASE_URLS) {
            try {
                val fullUrl = if (method == "GET" && formParams.isNotEmpty()) {
                    "$baseUrl$endpointPath?${encodeForm(formParams)}"
                } else {
                    "$baseUrl$endpointPath"
                }
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doInput = true
                    applyHeaders(this, cfg)
                }

                if (method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = encodeForm(formParams)
                    OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
                } ?: "{}"

                Log.d(TAG, "$method $endpointPath -> HTTP $code (${responseText.take(180)})")
                val json = JSONObject(responseText.ifBlank { "{}" })
                if (!json.has("httpCode")) {
                    json.put("httpCode", code)
                }
                return json
            } catch (e: Exception) {
                Log.w(TAG, "Error en $baseUrl$endpointPath: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No fue posible conectar con los servidores de Red Movilidad")
    }

    fun startClaveUnica(context: Context): ClaveUnicaStartInfo {
        var cfg = BipC2dEngine.loadConfig(context)
        if (cfg.phoneId.length < 32) {
            val newUuid = java.util.UUID.randomUUID().toString().lowercase(java.util.Locale.US)
            cfg = cfg.copy(phoneId = newUuid, uuid = newUuid)
            BipC2dEngine.saveConfig(context, cfg)
        }

        val resp = requestWithFallback(
            cfg = cfg,
            method = "POST",
            endpointPath = "/claveunica/start",
            formParams = mapOf("phoneId" to cfg.phoneId)
        )

        val status = resp.optInt("status", 200)
        val msg = resp.optString("message", resp.optString("mensaje", "Respuesta inválida de ClaveÚnica"))
        require(status == 200) { "Error al iniciar ClaveÚnica: $msg" }

        val root = resp.optJSONObject("data") ?: resp
        val clientId = root.optString("clientId", "")
        val responseType = root.optString("responseType", "code")
        val scope = root.optString("scope", "openid run name")
        val redirectUri = root.optString("redirectURI", root.optString("redirectUri", ""))
        val sessionToken = root.optString("sessionToken", "")

        require(clientId.isNotEmpty() && sessionToken.isNotEmpty()) {
            "Datos incompletos de ClaveÚnica: $msg"
        }

        val authUrl = "https://accounts.claveunica.gob.cl/openid/authorize?" + encodeForm(
            mapOf(
                "client_id" to clientId,
                "response_type" to responseType,
                "scope" to scope,
                "redirect_uri" to redirectUri,
                "state" to sessionToken
            )
        )

        return ClaveUnicaStartInfo(
            authUrl = authUrl,
            sessionToken = sessionToken,
            redirectUri = redirectUri
        )
    }

    fun checkClaveUnicaLogged(context: Context, cuSessionToken: String): ClaveUnicaUserProfile? {
        val cfg = BipC2dEngine.loadConfig(context)
        val resp = requestWithFallback(
            cfg = cfg,
            method = "GET",
            endpointPath = "/claveunica/isLogged",
            formParams = mapOf(
                "phoneId" to cfg.phoneId,
                "sessionToken" to cuSessionToken
            )
        )

        val status = resp.optInt("status", resp.optInt("httpCode", 0))
        val root = resp.optJSONObject("data") ?: resp
        val rut = root.optString("rut", "").trim()
        if (status != 200 || rut.isEmpty()) {
            return null
        }

        val names = root.optString("names", root.optString("name", "Usuario")).trim()
        val lastNames = root.optString("lastNames", root.optString("lastName", root.optString("apellidos", "Red"))).trim()
        val email = root.optString("email", root.optString("mail", "")).trim()
        val nickname = root.optString("nickname", names.split(" ").firstOrNull() ?: "Usuario").trim()
        val phone = root.optString("phoneNumber", root.optString("phone", root.optString("numeroCelular", ""))).trim()

        return ClaveUnicaUserProfile(
            rut = rut,
            names = names,
            lastNames = lastNames,
            email = email,
            nickname = nickname,
            phoneNumber = phone
        )
    }

    fun signInWithClaveUnica(
        context: Context,
        cuSessionToken: String,
        profile: ClaveUnicaUserProfile,
        overrideEmail: String = "",
        overridePhone: String = ""
    ): StandaloneConfig {
        val cfg = BipC2dEngine.loadConfig(context)
        val cleanRut = profile.rut.replace(".", "").trim().uppercase(Locale.US)
        val userId = BipC2dEngine.sha256Hex(cleanRut)
        val finalEmail = overrideEmail.ifBlank { profile.email }.ifBlank { "usuario@red.cl" }
        val finalPhone = overridePhone.ifBlank { profile.phoneNumber }.ifBlank { "+56900000000" }
        val fullName = "${profile.names} ${profile.lastNames}".trim()

        val preSignInCfg = cfg.copy(
            rut = cleanRut,
            userId = userId,
            names = profile.names,
            lastNames = profile.lastNames,
            email = finalEmail,
            phoneNumber = finalPhone
        )
        BipC2dEngine.saveConfig(context, preSignInCfg)

        val resp = requestWithFallback(
            cfg = preSignInCfg,
            method = "POST",
            endpointPath = "/user/signIn",
            formParams = mapOf(
                "accessToken" to cuSessionToken,
                "userId" to userId,
                "phoneId" to preSignInCfg.phoneId,
                "accountType" to "CLAVEUNICA",
                "name" to fullName,
                "email" to finalEmail,
                "nickname" to profile.nickname.ifBlank { profile.names },
                "rut" to cleanRut,
                "phoneNumber" to finalPhone
            )
        )

        val dataObj = resp.optJSONObject("data")
        val userToken = dataObj?.optString("userToken", "")?.ifEmpty { null }
            ?: resp.optString("userToken", "").ifEmpty { null }
            ?: cuSessionToken

        val signedInCfg = preSignInCfg.copy(sessionToken = userToken)
        BipC2dEngine.saveConfig(context, signedInCfg)
        return signedInCfg
    }

    fun enrollOrUpdateAccount(context: Context): StandaloneConfig {
        var cfg = BipC2dEngine.loadConfig(context)
        require(cfg.rut.isNotEmpty() && cfg.sessionToken.isNotEmpty()) {
            "Sesión Red Movilidad incompleta (falta RUT o Session-Token)"
        }

        val enrolDataJson = BipC2dEngine.createEnrolOrUpdatePayload(
            context = context,
            rut = cfg.rut,
            names = cfg.names,
            lastNames = cfg.lastNames,
            email = cfg.email,
            phoneNumber = cfg.phoneNumber,
            idEnrolamiento = null,
            regenerateKeys = true
        )
        cfg = BipC2dEngine.loadConfig(context)

        val enrolResp = requestWithFallback(
            cfg = cfg,
            method = "POST",
            endpointPath = "/payment/qr/account/enrollment",
            formParams = mapOf(
                "phoneId" to cfg.phoneId,
                "phoneNumber" to cfg.phoneNumber,
                "sessionToken" to cfg.sessionToken,
                "userId" to cfg.userId,
                "data" to enrolDataJson
            )
        )

        val enrolData = enrolResp.optJSONObject("data") ?: enrolResp
        val codResultado = enrolData.optInt("codigoResultado", -1)
        val nuAbt = enrolData.optString("nuAbt", "").trim()
        val idEnrol = enrolData.optInt("idEnrolamiento", cfg.idEnrolamiento)

        if (codResultado == 0 && nuAbt.isNotEmpty()) {
            val saved = cfg.copy(nuAbt = nuAbt, idEnrolamiento = idEnrol)
            BipC2dEngine.saveConfig(context, saved)
            return saved
        }

        val effectiveIdEnrol = if (idEnrol != Int.MIN_VALUE && idEnrol > 0) idEnrol else 1
        val updateDataJson = BipC2dEngine.createEnrolOrUpdatePayload(
            context = context,
            rut = cfg.rut,
            names = cfg.names,
            lastNames = cfg.lastNames,
            email = cfg.email,
            phoneNumber = cfg.phoneNumber,
            idEnrolamiento = effectiveIdEnrol,
            regenerateKeys = false
        )
        cfg = BipC2dEngine.loadConfig(context)

        val updateResp = requestWithFallback(
            cfg = cfg,
            method = "POST",
            endpointPath = "/payment/qr/account/update",
            formParams = mapOf(
                "phoneId" to cfg.phoneId,
                "phoneNumber" to cfg.phoneNumber,
                "sessionToken" to cfg.sessionToken,
                "userId" to cfg.userId,
                "data" to updateDataJson
            )
        )

        val updateData = updateResp.optJSONObject("data") ?: updateResp
        val updateCod = updateData.optInt("codigoResultado", -1)
        val updatedNuAbt = updateData.optString("nuAbt", nuAbt).trim()
        val msg = updateData.optString(
            "mensajeResultado",
            enrolData.optString("mensajeResultado", "No se pudo enrolar/actualizar la cuenta bip!QR")
        )

        require(updatedNuAbt.isNotEmpty()) {
            "Error al activar bip!QR (código $updateCod): $msg"
        }

        val finalCfg = cfg.copy(
            nuAbt = updatedNuAbt,
            idEnrolamiento = updateData.optInt("idEnrolamiento", effectiveIdEnrol)
        )
        BipC2dEngine.saveConfig(context, finalCfg)
        return finalCfg
    }

    fun requestC2dSeed(context: Context): StandaloneConfig {
        val cfg = BipC2dEngine.loadConfig(context)
        require(cfg.sessionToken.isNotEmpty() && cfg.nuAbt.isNotEmpty() && cfg.privKeyBase64.isNotEmpty()) {
            "Configuración bip!QR incompleta en el dispositivo"
        }

        val requestEpochMs = System.currentTimeMillis()
        val dataPayload = BipC2dEngine.createRequestQrCodePayload(context)

        val resp = requestWithFallback(
            cfg = cfg,
            method = "POST",
            endpointPath = "/payment/qr/account/generatec2d",
            formParams = mapOf(
                "phoneId" to cfg.phoneId,
                "sessionToken" to cfg.sessionToken,
                "rut" to cfg.rut,
                "userId" to cfg.userId,
                "data" to dataPayload
            )
        )

        val responseEpochMs = System.currentTimeMillis()
        val dataObj = resp.optJSONObject("data") ?: resp
        val tramaSC = dataObj.optString("tramaSistemaCentral", "").trim().uppercase(Locale.US)
        val codResultado = dataObj.optInt("codigoResultado", 0)
        val msgResultado = dataObj.optString(
            "mensajeResultado",
            resp.optString("message", "No se pudo obtener trama C2D")
        )

        require(tramaSC.length == 62) {
            "Error C2D ($codResultado): $msgResultado"
        }

        val serverUtcStr = dataObj.optString("fechaHoraUTCServidor", "").trim()
        val serverEpochMs = parseServerUtcEpochMs(serverUtcStr) ?: ((requestEpochMs + responseEpochMs) / 2L)
        val midpointLocalMs = (requestEpochMs + responseEpochMs) / 2L
        val deltaEpochMs = midpointLocalMs - serverEpochMs

        val saldo = dataObj.optInt("saldo", cfg.saldo)
        val tiempoRefrescoSec = dataObj.optInt("tiempoRefrescoC2D", 5).coerceAtLeast(3)

        val updated = cfg.copy(
            tramaSistemaCentral = tramaSC,
            serverEpochMs = serverEpochMs,
            deltaEpochMs = deltaEpochMs,
            saldo = saldo,
            tiempoRefrescoSec = tiempoRefrescoSec
        )
        BipC2dEngine.saveConfig(context, updated)
        return updated
    }

    private fun parseServerUtcEpochMs(serverUtcStr: String): Long? {
        if (serverUtcStr.isBlank()) return null
        serverUtcStr.toLongOrNull()?.let { num ->
            return if (num < 100_000_000_000L) num * 1000L else num
        }
        val patterns = listOf(
            "ddMMyyyyHHmmss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(serverUtcStr)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
