package com.nowis.bipwear

import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
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

    private lateinit var layoutIdle: LinearLayout
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutTicket: LinearLayout

    private lateinit var btnGenerate: Button
    private lateinit var txtIdleSubtitle: TextView
    private lateinit var txtLoading: TextView
    private lateinit var imgQrCode: ImageView
    private lateinit var txtTimer: TextView
    private lateinit var txtInstructions: TextView

    private var countDownTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTicketVisible = false

    // Refreshes the Aztec C2D dynamic signature every 5 seconds (tiempoRefrescoC2D)
    private val c2dRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isTicketVisible) return
            try {
                if (BipC2dEngine.isTramaValid(this@MainActivity)) {
                    val refreshed = BipC2dEngine.generateOfflineTicket(this@MainActivity)
                    imgQrCode.setImageBitmap(refreshed.bitmap)
                    val intervalMs = (refreshed.refreshIntervalSec * 1000L).coerceAtLeast(3000L)
                    mainHandler.postDelayed(this, intervalMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error en auto-refresco C2D: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        observeTicketState()
        syncStoredDataItems()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        updateIdleSubtitle()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
    }

    private fun initViews() {
        layoutIdle = findViewById(R.id.layoutIdle)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutTicket = findViewById(R.id.layoutTicket)

        btnGenerate = findViewById(R.id.btnGenerate)
        txtIdleSubtitle = findViewById(R.id.txtIdleSubtitle)
        txtLoading = findViewById(R.id.txtLoading)
        imgQrCode = findViewById(R.id.imgQrCode)
        txtTimer = findViewById(R.id.txtTimer)
        txtInstructions = findViewById(R.id.txtInstructions)

        btnGenerate.setOnClickListener {
            generarPasajeStandalone()
        }

        layoutTicket.setOnClickListener {
            generarPasajeStandalone()
        }

        updateIdleSubtitle()
    }

    private fun observeTicketState() {
        lifecycleScope.launch {
            TicketManager.ticketState.collect { state ->
                when (state) {
                    is TicketState.Idle -> showIdleView()
                    is TicketState.Loading -> showLoadingView(state.message)
                    is TicketState.Active -> showTicketView(state.ticket)
                    is TicketState.Error -> showErrorView(state.errorMessage)
                }
            }
        }

        lifecycleScope.launch {
            TicketManager.configVersion.collect {
                updateIdleSubtitle()
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

    private fun updateIdleSubtitle() {
        val cfg = BipC2dEngine.loadConfig(this)
        if (cfg.isProvisioned) {
            val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(cfg.saldo)
            val tramaStatus = if (BipC2dEngine.isTramaValid(this)) "Offline listo" else "Online 2h"
            txtIdleSubtitle.text = "${cfg.rut} · $$clp\n($tramaStatus)"
        } else {
            txtIdleSubtitle.text = getString(R.string.status_not_provisioned)
        }
    }

    /**
     * Generates the bip!QR Aztec ticket 100% locally on the watch using Sonda's libc2d algorithm.
     * Never opens or wakes up the phone screen.
     */
    private fun generarPasajeStandalone() {
        val cfg = BipC2dEngine.loadConfig(this)
        if (!cfg.isProvisioned) {
            Toast.makeText(
                this,
                getString(R.string.status_not_provisioned),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Fast path: 2-hour C2D seed (`tramaSistemaCentral`) is already valid on the watch -> <50ms 100% offline!
        if (BipC2dEngine.isTramaValid(this)) {
            renderOfflineTicketNow()
            return
        }

        // Slow path (once every 2 hours): renew `tramaSistemaCentral` directly from watch HTTP (or background phone API fallback)
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

    /**
     * Fallback when the watch has no direct Wi-Fi/LTE/BT-proxy internet: asks the phone's
     * background listener service to call `/payment/qr/account/generatec2d` silently (NO UI/screen).
     */
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

    private fun showIdleView() {
        isTicketVisible = false
        mainHandler.removeCallbacks(c2dRefreshRunnable)
        resetScreenBrightness()
        countDownTimer?.cancel()

        updateIdleSubtitle()
        layoutIdle.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutTicket.visibility = View.GONE
    }

    private fun showLoadingView(message: String) {
        isTicketVisible = false
        mainHandler.removeCallbacks(c2dRefreshRunnable)
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        layoutTicket.visibility = View.GONE
        txtLoading.text = message
    }

    private fun showTicketView(ticket: TicketData) {
        isTicketVisible = true
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        layoutTicket.visibility = View.VISIBLE

        imgQrCode.setImageBitmap(ticket.bitmap)
        maximizeScreenBrightness()

        val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(ticket.saldo)
        txtInstructions.text = if (ticket.saldo > 0) {
            "Saldo: $$clp · Toca para actualizar"
        } else {
            getString(R.string.status_ticket_ready)
        }

        // Schedule dynamic 5-second Aztec signature rotation
        mainHandler.removeCallbacks(c2dRefreshRunnable)
        val refreshMs = (ticket.refreshIntervalSec * 1000L).coerceAtLeast(3000L)
        mainHandler.postDelayed(c2dRefreshRunnable, refreshMs)

        countDownTimer?.cancel()
        val remainingMs = (ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(10_000L)

        countDownTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                txtTimer.setTextColor(getColor(R.color.accent_green))
                txtTimer.text = if (hours > 0) {
                    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
            }

            override fun onFinish() {
                isTicketVisible = false
                mainHandler.removeCallbacks(c2dRefreshRunnable)
                txtTimer.text = getString(R.string.status_expired)
                txtTimer.setTextColor(getColor(R.color.red_primary))
            }
        }.start()
    }

    private fun showErrorView(message: String) {
        isTicketVisible = false
        mainHandler.removeCallbacks(c2dRefreshRunnable)
        resetScreenBrightness()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        TicketManager.setState(TicketState.Idle)
    }

    private fun maximizeScreenBrightness() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = params
    }

    private fun resetScreenBrightness() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    override fun onDestroy() {
        super.onDestroy()
        isTicketVisible = false
        mainHandler.removeCallbacks(c2dRefreshRunnable)
        countDownTimer?.cancel()
    }
}
