package com.nowis.bipwear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.nowis.bipwear.data.DefaultBipRepository
import com.nowis.bipwear.ui.IdleScreen
import com.nowis.bipwear.ui.LoadingScreen
import com.nowis.bipwear.ui.MovementsScreen
import com.nowis.bipwear.ui.TicketScreen
import com.nowis.bipwear.ui.state.MainUiState
import com.nowis.bipwear.viewmodel.MainViewModel
import com.nowis.bipwear.viewmodel.MovementsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "BipMainActivity"
        const val EXTRA_AUTO_GENERATE = "extra_auto_generate"
        const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"
        const val PATH_REFRESH_SEED = "/bip/refresh_seed"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
    }

    private val repository by lazy { DefaultBipRepository(applicationContext) }
    private val mainViewModel: MainViewModel by viewModels { MainViewModel.provideFactory(repository) }
    private val movementsViewModel: MovementsViewModel by viewModels { MovementsViewModel.provideFactory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeTicketState()
        syncStoredDataItems()

        // Handle physical back button: return to Idle instead of instantly exiting if on Ticket or Movements screen
        onBackPressedDispatcher.addCallback(this) {
            if (mainViewModel.uiState.value !is MainUiState.Idle) {
                TicketManager.setState(TicketState.Idle)
                mainViewModel.resetToIdle()
            } else {
                finish()
            }
        }

        // Fast-path auto generation from Tile or Complication
        handleAutoGenerateIntent(intent)

        setContent {
            MaterialTheme {
                BipWearApp(
                    activity = this,
                    mainViewModel = mainViewModel,
                    movementsViewModel = movementsViewModel,
                    onGenerateTicket = { generarPasajeStandalone() },
                    onStartClaveUnica = { startClaveUnicaRemoteFlow() },
                    onAutoRefreshTriggered = { renderOfflineTicketNow() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoGenerateIntent(intent)
    }

    private fun handleAutoGenerateIntent(targetIntent: Intent?) {
        if (targetIntent?.getBooleanExtra(EXTRA_AUTO_GENERATE, false) == true) {
            val cfg = BipC2dEngine.loadConfig(this)
            if (cfg.isProvisioned) {
                generarPasajeStandalone()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        mainViewModel.refreshConfig()
        mainViewModel.refreshDaleQr()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
    }

    private fun observeTicketState() {
        lifecycleScope.launch {
            TicketManager.ticketState.collect { state ->
                when (state) {
                    is TicketState.Active -> mainViewModel.setTicketActive(state.ticket)
                    is TicketState.Error -> mainViewModel.setError(state.errorMessage)
                    is TicketState.Loading -> mainViewModel.setState(MainUiState.Loading(state.message))
                    is TicketState.Movements -> mainViewModel.setState(MainUiState.Movements)
                    is TicketState.Idle -> { /* Idle is handled by ViewModel */ }
                }
            }
        }
        lifecycleScope.launch {
            TicketManager.configVersion.collect {
                mainViewModel.refreshConfig()
                if (mainViewModel.uiState.value is MainUiState.Loading && BipC2dEngine.isTramaValid(this@MainActivity)) {
                    renderOfflineTicketNow()
                }
            }
        }
    }

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
                            mainViewModel.importConfigJson(configJson)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar DataItems iniciales: ${e.message}")
            }
        }
    }

    private fun startClaveUnicaRemoteFlow() {
        mainViewModel.setState(MainUiState.Loading(getString(R.string.status_opening_phone)))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startInfo = RedApiClient.startClaveUnica(this@MainActivity)
                val remoteIntent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(startInfo.authUrl))

                val remoteActivityHelper = RemoteActivityHelper(this@MainActivity)
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val phoneNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

                withContext(Dispatchers.Main) {
                    mainViewModel.setState(MainUiState.Loading(getString(R.string.status_waiting_phone_login)))
                }

                try {
                    if (phoneNode != null) {
                        remoteActivityHelper.startRemoteActivity(remoteIntent, phoneNode.id)
                    } else {
                        remoteActivityHelper.startRemoteActivity(remoteIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo al enviar RemoteActivity, intentando local", e)
                    try {
                        startActivity(remoteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }

                var profile: ClaveUnicaUserProfile? = null
                var attempts = 0
                while (isActive && attempts < 90) {
                    delay(2000)
                    attempts++
                    try {
                        val p = RedApiClient.checkClaveUnicaLogged(this@MainActivity, startInfo.sessionToken)
                        if (p != null) {
                            profile = p
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (profile == null) {
                    withContext(Dispatchers.Main) {
                        mainViewModel.setError("Tiempo de espera agotado. Intenta vincular de nuevo.")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    mainViewModel.setState(MainUiState.Loading(getString(R.string.status_activating_keys)))
                }

                RedApiClient.signInWithClaveUnica(this@MainActivity, startInfo.sessionToken, profile)
                RedApiClient.enrollOrUpdateAccount(this@MainActivity)
                RedApiClient.requestC2dSeed(this@MainActivity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "¡Cuenta activada con éxito!", Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshConfig()
                    generarPasajeStandalone()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en flujo ClaveÚnica", e)
                withContext(Dispatchers.Main) {
                    mainViewModel.setError("Error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun generarPasajeStandalone() {
        val cfg = mainViewModel.config.value
        if (!cfg.isProvisioned) {
            startClaveUnicaRemoteFlow()
            return
        }

        if (BipC2dEngine.isTramaValid(this)) {
            renderOfflineTicketNow()
            return
        }

        mainViewModel.setState(MainUiState.Loading(getString(R.string.status_requesting)))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                RedApiClient.requestC2dSeed(this@MainActivity)
                withContext(Dispatchers.Main) {
                    mainViewModel.refreshConfig()
                    renderOfflineTicketNow()
                }
            } catch (directNetworkError: Exception) {
                Log.w(TAG, "Renovación HTTP directa desde reloj falló (${directNetworkError.message}), intentando vía servicio silencioso...", directNetworkError)
                val requestedViaBackground = requestSilentBackgroundSeedRefresh()
                if (!requestedViaBackground) {
                    withContext(Dispatchers.Main) {
                        mainViewModel.setError("Error renovando semilla C2D: ${directNetworkError.localizedMessage ?: "Sin red"}")
                    }
                }
            }
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

    fun renderOfflineTicketNow() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = BipC2dEngine.generateOfflineTicket(applicationContext)
                val ticket = TicketData(
                    bitmap = result.bitmap,
                    expiryTimestampMs = result.tramaExpiryEpochMs,
                    refreshIntervalSec = result.refreshIntervalSec,
                    saldo = result.saldo
                )
                withContext(Dispatchers.Main) {
                    mainViewModel.setTicketActive(ticket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generando pasaje", e)
                withContext(Dispatchers.Main) {
                    mainViewModel.setError("Error C2D: ${e.localizedMessage}")
                }
            }
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
                        mainViewModel.refreshConfig()
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
            if (mainViewModel.importConfigJson(jsonStr)) {
                Toast.makeText(this, "Cuenta bip!QR sincronizada", Toast.LENGTH_SHORT).show()
                if (mainViewModel.uiState.value is MainUiState.Loading && BipC2dEngine.isTramaValid(this)) {
                    renderOfflineTicketNow()
                }
            }
            return
        }

        if (path == PATH_TICKET_ERROR) {
            val errorMsg = if (data != null && data.isNotEmpty()) {
                String(data, StandardCharsets.UTF_8)
            } else {
                getString(R.string.status_error)
            }
            mainViewModel.setError(errorMsg)
            return
        }

        if ((path.startsWith("/qr") || path.startsWith("/bip")) && data != null && data.isNotEmpty()) {
            TicketManager.processPayload(applicationContext, data)
            mainViewModel.refreshConfig()
        }
    }
}

@Composable
fun BipWearApp(
    activity: ComponentActivity,
    mainViewModel: MainViewModel,
    movementsViewModel: MovementsViewModel,
    onGenerateTicket: () -> Unit,
    onStartClaveUnica: () -> Unit,
    onAutoRefreshTriggered: () -> Unit
) {
    val context = LocalContext.current
    val ticketState by mainViewModel.uiState.collectAsState()
    val cfg by mainViewModel.config.collectAsState()
    val movementsState by movementsViewModel.uiState.collectAsState()

    val isTicketActive = ticketState is MainUiState.Active

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

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val state = ticketState) {
                is MainUiState.Idle -> {
                    val isOfflineReady = remember(cfg) { BipC2dEngine.isTramaValid(context) }
                    IdleScreen(
                        cfg = cfg,
                        isOfflineReady = isOfflineReady,
                        onGenerate = onGenerateTicket,
                        onRelink = onStartClaveUnica,
                        onRefreshDaleQr = {
                            mainViewModel.refreshDaleQr { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onViewMovements = { mainViewModel.setState(MainUiState.Movements) }
                    )
                }
                is MainUiState.Loading -> {
                    LoadingScreen(message = state.message)
                }
                is MainUiState.Active -> {
                    TicketScreen(
                        ticket = state.ticket,
                        cfg = cfg,
                        onRefresh = onGenerateTicket,
                        onAutoRefreshTriggered = onAutoRefreshTriggered,
                        onViewMovements = { mainViewModel.setState(MainUiState.Movements) }
                    )
                }
                is MainUiState.Movements -> {
                    MovementsScreen(
                        uiState = movementsState,
                        onRefresh = { movementsViewModel.refresh(forceNetwork = true) },
                        onBack = {
                            TicketManager.setState(TicketState.Idle)
                            mainViewModel.resetToIdle()
                        }
                    )
                }
                is MainUiState.Error -> {
                    LaunchedEffect(state.errorMessage) {
                        Toast.makeText(context, state.errorMessage, Toast.LENGTH_LONG).show()
                        TicketManager.setState(TicketState.Idle)
                        mainViewModel.resetToIdle()
                    }
                }
            }
        }
    }
}
