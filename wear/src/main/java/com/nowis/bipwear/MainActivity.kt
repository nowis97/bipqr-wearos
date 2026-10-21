package com.nowis.bipwear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "BipMainActivity"
        const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"
        const val PATH_REFRESH_SEED = "/bip/refresh_seed"
        const val PATH_TICKET_DATA = "/bip/ticket_data"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        observeTicketState()
        syncStoredDataItems()

        setContent {
            MaterialTheme {
                BipWearApp(
                    activity = this,
                    onGenerateTicket = { generarPasajeStandalone() },
                    onStartClaveUnica = { startClaveUnicaRemoteFlow() },
                    onRefreshDaleQr = { refreshDaleQrAsync(showToast = true) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        refreshDaleQrAsync(showToast = false)
    }

    private fun refreshDaleQrAsync(showToast: Boolean = false) {
        val cfg = BipC2dEngine.loadConfig(this)
        if (cfg.isProvisioned && cfg.sessionToken.isNotEmpty()) {
            if (showToast) {
                Toast.makeText(this, "Actualizando Dale QR...", Toast.LENGTH_SHORT).show()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val updated = RedApiClient.fetchDaleQrBenefitInfo(this@MainActivity)
                    if (showToast) {
                        withContext(Dispatchers.Main) {
                            val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(updated.daleQrAcumulado)
                            val metaClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(updated.daleQrMeta)
                            Toast.makeText(this@MainActivity, "Dale QR: $$clp / $$metaClp", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    if (showToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error Dale QR: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
    }

    private fun observeTicketState() {
        lifecycleScope.launch {
            TicketManager.configVersion.collect {
                // If we were waiting for a background seed refresh, immediately generate the ticket
                if (TicketManager.ticketState.value is TicketState.Loading && BipC2dEngine.isTramaValid(this@MainActivity)) {
                    renderOfflineTicketNow()
                }
            }
        }
    }

    /**
     * Checks Wearable DataClient on startup in case the phone pushed `/bip/standalone_config`
     * while the watch app wasn't open.
     */
    private fun syncStoredDataItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dataClient = Wearable.getDataClient(this@MainActivity)
                val uri = Uri.parse("wear://*$PATH_STANDALONE_CONFIG")
                val buffer = dataClient.getDataItems(uri).await()
                buffer.use { items ->
                    for (item in items) {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        val configJson = dataMap.getString("config_json")
                        if (!configJson.isNullOrEmpty()) {
                            TicketManager.importStandaloneConfigJson(applicationContext, configJson)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar DataItems iniciales: ${e.message}")
            }
        }
    }

    /**
     * Inicia el flujo de autenticación con ClaveÚnica abriendo el navegador oficial en el teléfono
     * mediante RemoteActivityHelper nativo de Wear OS (sin necesidad de tener ninguna app instalada en el teléfono).
     */
    private fun startClaveUnicaRemoteFlow() {
        TicketManager.setState(TicketState.Loading(getString(R.string.status_opening_phone)))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Obtiene la URL de autenticación desde el backend de Red Movilidad
                val startInfo = RedApiClient.startClaveUnica(this@MainActivity)

                // 2. Lanza el intent en el navegador predeterminado del teléfono emparejado (Chrome, Samsung Internet, etc.)
                val remoteIntent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(startInfo.authUrl))

                val remoteActivityHelper = RemoteActivityHelper(this@MainActivity)
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val phoneNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

                withContext(Dispatchers.Main) {
                    TicketManager.setState(TicketState.Loading(getString(R.string.status_waiting_phone_login)))
                }

                try {
                    if (phoneNode != null) {
                        remoteActivityHelper.startRemoteActivity(remoteIntent, phoneNode.id)
                    } else {
                        remoteActivityHelper.startRemoteActivity(remoteIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo al enviar RemoteActivity al teléfono, intentando local", e)
                    try {
                        startActivity(remoteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }

                // 3. Monitorea en segundo plano hasta que el usuario termine el login en el navegador
                var profile: ClaveUnicaUserProfile? = null
                var attempts = 0
                while (isActive && attempts < 90) { // Hasta 3 minutos de espera
                    delay(2000)
                    attempts++
                    try {
                        val p = RedApiClient.checkClaveUnicaLogged(this@MainActivity, startInfo.sessionToken)
                        if (p != null) {
                            profile = p
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Esperando login ClaveÚnica: ${e.message}")
                    }
                }

                if (profile == null) {
                    withContext(Dispatchers.Main) {
                        TicketManager.setState(TicketState.Error("Tiempo de espera agotado. Intenta vincular de nuevo."))
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    TicketManager.setState(TicketState.Loading(getString(R.string.status_activating_keys)))
                }

                // 4. Inicia sesión en Red Movilidad con el perfil de ClaveÚnica
                RedApiClient.signInWithClaveUnica(
                    context = this@MainActivity,
                    cuSessionToken = startInfo.sessionToken,
                    profile = profile
                )

                // 5. Enrola o actualiza la llave criptográfica secp224r1 de Sonda libc2d
                RedApiClient.enrollOrUpdateAccount(this@MainActivity)

                // 6. Descarga la primera semilla C2D de 2 horas (tramaSistemaCentral + saldo)
                RedApiClient.requestC2dSeed(this@MainActivity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "¡Cuenta activada con éxito!", Toast.LENGTH_SHORT).show()
                    // Genera de inmediato el primer pasaje offline
                    renderOfflineTicketNow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en flujo ClaveÚnica en el reloj", e)
                withContext(Dispatchers.Main) {
                    TicketManager.setState(TicketState.Error("Error: ${e.localizedMessage}"))
                }
            }
        }
    }

    /**
     * Genera el pasaje Aztec bip!QR 100% en el reloj usando el algoritmo libc2d de Sonda.
     */
    private fun generarPasajeStandalone() {
        val cfg = BipC2dEngine.loadConfig(this)
        if (!cfg.isProvisioned) {
            startClaveUnicaRemoteFlow()
            return
        }

        // Fast path: semilla C2D de 2 horas válida -> <50ms 100% offline
        if (BipC2dEngine.isTramaValid(this)) {
            renderOfflineTicketNow()
            return
        }

        // Slow path: renovación de semilla directa desde reloj o servicio silencioso
        TicketManager.setState(TicketState.Loading(getString(R.string.status_requesting)))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                RedApiClient.requestC2dSeed(this@MainActivity)
                withContext(Dispatchers.Main) {
                    renderOfflineTicketNow()
                }
            } catch (directNetworkError: Exception) {
                Log.w(TAG, "Renovación HTTP directa desde reloj falló (${directNetworkError.message}), intentando vía servicio silencioso...", directNetworkError)
                val requestedViaBackground = requestSilentBackgroundSeedRefresh()
                if (!requestedViaBackground) {
                    withContext(Dispatchers.Main) {
                        TicketManager.setState(
                            TicketState.Error("Error renovando semilla C2D: ${directNetworkError.localizedMessage ?: "Sin red"}")
                        )
                    }
                }
            }
        }
    }

    private fun renderOfflineTicketNow() {
        try {
            val result = BipC2dEngine.generateOfflineTicket(this)
            TicketManager.setState(
                TicketState.Active(
                    TicketData(
                        bitmap = result.bitmap,
                        expiryTimestampMs = result.tramaExpiryEpochMs,
                        refreshIntervalSec = result.refreshIntervalSec,
                        saldo = result.saldo
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error generando pasaje offline", e)
            TicketManager.setState(TicketState.Error("Error C2D: ${e.localizedMessage}"))
        }
    }

    private suspend fun requestSilentBackgroundSeedRefresh(): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            val phoneNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return false
            Wearable.getMessageClient(this).sendMessage(phoneNode.id, PATH_REFRESH_SEED, byteArrayOf()).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo solicitar renovación silenciosa", e)
            false
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val dataClient = Wearable.getDataClient(this)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: ""
                if (path.startsWith("/qr") || path.startsWith("/bip")) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    lifecycleScope.launch(Dispatchers.IO) {
                        TicketManager.processDataMap(applicationContext, dataClient, dataMap)
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data

        if (path == PATH_STANDALONE_CONFIG && data != null && data.isNotEmpty()) {
            val jsonStr = String(data, StandardCharsets.UTF_8)
            if (TicketManager.importStandaloneConfigJson(applicationContext, jsonStr)) {
                Toast.makeText(this, "Cuenta bip!QR sincronizada en el reloj", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (path == PATH_TICKET_ERROR) {
            val errorMsg = if (data != null && data.isNotEmpty()) {
                String(data, StandardCharsets.UTF_8)
            } else {
                getString(R.string.status_error)
            }
            TicketManager.setState(TicketState.Error(errorMsg))
            return
        }

        if ((path.startsWith("/qr") || path.startsWith("/bip")) && data != null && data.isNotEmpty()) {
            TicketManager.processPayload(applicationContext, data)
        }
    }
}

@Composable
fun BipWearApp(
    activity: ComponentActivity,
    onGenerateTicket: () -> Unit,
    onStartClaveUnica: () -> Unit,
    onRefreshDaleQr: () -> Unit
) {
    val context = LocalContext.current
    val ticketState by TicketManager.ticketState.collectAsState()
    val configVersion by TicketManager.configVersion.collectAsState()
    val cfg = remember(configVersion) { BipC2dEngine.loadConfig(context) }

    val isTicketActive = ticketState is TicketState.Active

    // Maximizar brillo y mantener pantalla encendida mientras el código Aztec está visible
    DisposableEffect(isTicketActive) {
        if (isTicketActive) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            activity.window.attributes = params
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = params
        }
    }

    Scaffold(
        timeText = { TimeText() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val state = ticketState) {
                is TicketState.Idle -> {
                    IdleScreen(
                        cfg = cfg,
                        onGenerate = onGenerateTicket,
                        onRelink = onStartClaveUnica,
                        onRefreshDaleQr = onRefreshDaleQr,
                        onViewMovements = { TicketManager.setState(TicketState.Movements) }
                    )
                }
                is TicketState.Loading -> {
                    LoadingScreen(message = state.message)
                }
                is TicketState.Active -> {
                    TicketScreen(
                        ticket = state.ticket,
                        onRefresh = onGenerateTicket
                    )
                }
                is TicketState.Movements -> {
                    MovementsScreen(
                        onBack = { TicketManager.setState(TicketState.Idle) }
                    )
                }
                is TicketState.Error -> {
                    LaunchedEffect(state.errorMessage) {
                        Toast.makeText(context, state.errorMessage, Toast.LENGTH_LONG).show()
                        TicketManager.setState(TicketState.Idle)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdleScreen(
    cfg: StandaloneConfig,
    onGenerate: () -> Unit,
    onRelink: () -> Unit,
    onRefreshDaleQr: () -> Unit,
    onViewMovements: () -> Unit
) {
    val context = LocalContext.current
    val isProvisioned = cfg.isProvisioned
    val isOfflineReady = remember(cfg) { BipC2dEngine.isTramaValid(context) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Anillo circular nativo en el borde de la pantalla redonda
        if (isProvisioned) {
            val rawProgress = cfg.daleQrProgress
            // Si hay progreso pero es pequeño (ej. 1 viaje de $830 = 1.9%),
            // aseguramos al menos un 3.5% visual (~13 grados) para que sea inmediatamente perceptible.
            val progress = when {
                cfg.isDaleQrReached -> 1f
                rawProgress > 0f -> rawProgress.coerceIn(0.035f, 1f)
                else -> 0f
            }

            val ringColor = if (cfg.isDaleQrReached) Color(0xFF00E676) else Color(0xFF00E5FF)
            // Track traslúcido con tinte para que la silueta del anillo sea claramente visible en pantallas OLED incluso con $0 acumulado
            val ringTrackColor = if (cfg.isDaleQrReached) Color(0x3300E676) else Color(0x3300E5FF)

            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                startAngle = 270f,
                endAngle = 270f,
                indicatorColor = ringColor,
                trackColor = ringTrackColor,
                strokeWidth = 4.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "bip!QR",
                color = Color(0xFFE50914),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(2.dp))

            val subtitleText = if (isProvisioned) {
                val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(cfg.saldo)
                val tramaStatus = if (isOfflineReady) "Offline listo" else "Online 2h"
                "${cfg.rut} · $$clp ($tramaStatus)"
            } else {
                stringResource(R.string.status_not_provisioned)
            }

            Text(
                text = subtitleText,
                color = Color(0xFFAAAAAA),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )

            if (isProvisioned) {
                Spacer(modifier = Modifier.height(6.dp))
                DaleQrBadge(
                    acumulado = cfg.daleQrAcumulado,
                    meta = cfg.daleQrMeta,
                    isReached = cfg.isDaleQrReached,
                    onClick = onRefreshDaleQr
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE50914))
                    .combinedClickable(
                        onClick = {
                            if (isProvisioned) onGenerate() else onRelink()
                        },
                        onLongClick = {
                            if (isProvisioned) {
                                Toast.makeText(context, "Re-vinculando ClaveÚnica...", Toast.LENGTH_SHORT).show()
                                onRelink()
                            }
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isProvisioned) stringResource(R.string.btn_generate) else stringResource(R.string.btn_link_clave_unica),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (isProvisioned) {
                Spacer(modifier = Modifier.height(5.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E1E))
                        .clickable { onViewMovements() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📋 Movimientos",
                        color = Color(0xFFDDDDDD),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun DaleQrBadge(
    acumulado: Int,
    meta: Int,
    isReached: Boolean,
    onClick: () -> Unit
) {
    val metaClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(meta)
    val acumClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(acumulado)

    if (isReached) {
        // Estado: Meta superada -> ¡Viajes gratis!
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D3818))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎉 ¡Viajes Gratis este mes!",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Dale QR: $$acumClp superado",
                    color = Color(0xFFB9F6CA),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // Estado: Acumulando para la meta
        val progress = if (meta > 0) (acumulado.toFloat() / meta.toFloat()).coerceIn(0f, 1f) else 0f
        val pct = (progress * 100).toInt()

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1A))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Dale QR: $$acumClp / $$metaClp ($pct%)",
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        CircularProgressIndicator(
            indicatorColor = Color(0xFFE50914),
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = message,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp
        )
    }
}

@Composable
fun TicketScreen(
    ticket: TicketData,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    // Auto-refresco de firma dinámica telemétrica cada 5 segundos
    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive) {
            val intervalMs = (ticket.refreshIntervalSec * 1000L).coerceAtLeast(3000L)
            delay(intervalMs)
            try {
                if (BipC2dEngine.isTramaValid(context)) {
                    val refreshed = BipC2dEngine.generateOfflineTicket(context)
                    TicketManager.setState(
                        TicketState.Active(
                            TicketData(
                                bitmap = refreshed.bitmap,
                                expiryTimestampMs = refreshed.tramaExpiryEpochMs,
                                refreshIntervalSec = refreshed.refreshIntervalSec,
                                saldo = refreshed.saldo
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("BipMainActivity", "Error en auto-refresco C2D: ${e.message}")
            }
        }
    }

    // Contador regresivo
    var remainingMs by remember(ticket.expiryTimestampMs) {
        mutableStateOf((ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive && remainingMs > 0) {
            delay(1000)
            remainingMs = (ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }

    val isExpired = remainingMs <= 0
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val timerText = if (isExpired) {
        stringResource(R.string.status_expired)
    } else if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
    val timerColor = if (isExpired) Color(0xFFE50914) else Color(0xFF00E676)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable { onRefresh() }
            .padding(bottom = 4.dp)
    ) {
        Text(
            text = timerText,
            color = timerColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(3.dp))

        // Contenedor blanco de alto contraste para lectura óptica en torniquetes
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = ticket.bitmap.asImageBitmap(),
                contentDescription = "bip!QR Ticket",
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(ticket.saldo)
        val instructionsText = if (ticket.saldo > 0) {
            "Saldo: $$clp · Toca para actualizar"
        } else {
            stringResource(R.string.status_ticket_ready)
        }

        Text(
            text = instructionsText,
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MovementsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var movements by remember { mutableStateOf(RedApiClient.loadCachedMovements(context)) }
    var isLoading by remember { mutableStateOf(movements.isEmpty()) }
    val listState = rememberScalingLazyListState()

    val refreshMovements: () -> Unit = {
        isLoading = true
        (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            val fresh = RedApiClient.fetchTransactions(context)
            withContext(Dispatchers.Main) {
                movements = fresh
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshMovements()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading && movements.isEmpty()) {
            LoadingScreen(message = "Cargando movimientos...")
        } else if (movements.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                Text(text = "📋", fontSize = 24.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sin movimientos",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tus viajes en bus, metro y tren aparecerán aquí.",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF222222))
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Volver",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable { refreshMovements() }
                    ) {
                        Text(
                            text = "Últimos movimientos",
                            color = Color(0xFFE50914),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (isLoading) "Actualizando..." else "${movements.size} registrados · Toca para recargar",
                            color = Color.Gray,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                items(movements) { item ->
                    MovementItemRow(item = item)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF222222))
                            .clickable { onBack() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Volver al inicio",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
fun MovementItemRow(item: BipMovement) {
    val (icon, label, accentColor) = getMovementVisuals(item)
    val formattedDate = formatMovementDate(item.dateTimeStr)

    val amountText = when {
        item.isFreeTrip -> "$0"
        item.isCarga -> "+$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount.coerceAtLeast(0))}"
        item.amount > 0 -> "-$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount)}"
        else -> "$0"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                if (formattedDate.isNotBlank()) {
                    Text(
                        text = formattedDate,
                        color = Color(0xFFAAAAAA),
                        fontSize = 8.sp
                    )
                }
            }
            Text(
                text = amountText,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}

fun getMovementVisuals(item: BipMovement): Triple<String, String, Color> {
    val descLower = item.description.lowercase(Locale.ROOT)
    val typeLower = item.transportType.lowercase(Locale.ROOT)

    return when {
        item.isFreeTrip || descLower.contains("gratis") || descLower.contains("dale") -> {
            Triple("🎉", if (item.description.isNotBlank()) item.description else "Viaje gratis Dale QR", Color(0xFF00E676))
        }
        item.isCarga || descLower.contains("carga") -> {
            Triple("💳", if (item.description.isNotBlank()) item.description else "Carga de saldo", Color(0xFF4CAF50))
        }
        descLower.contains("metro") || typeLower.contains("metro") || descLower.contains("estaci") -> {
            Triple("🚇", if (item.description.isNotBlank()) item.description else "Viaje en Metro", Color(0xFFFF5252))
        }
        descLower.contains("tren") || typeLower.contains("tren") -> {
            Triple("🚆", if (item.description.isNotBlank()) item.description else "Viaje en Tren", Color(0xFFFF9800))
        }
        else -> {
            Triple("🚌", if (item.description.isNotBlank()) item.description else "Viaje en Bus", Color(0xFF00E5FF))
        }
    }
}

fun formatMovementDate(raw: String): String {
    if (raw.isBlank()) return ""
    val inputPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy HH:mm"
    )
    for (pat in inputPatterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.US)
            val date = sdf.parse(raw)
            if (date != null) {
                val outSdf = SimpleDateFormat("dd MMM, HH:mm", Locale("es", "CL"))
                return outSdf.format(date)
            }
        } catch (_: Exception) {}
    }
    return raw
}
