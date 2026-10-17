package com.nowis.bipwear

import androidx.activity.ComponentActivity
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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "BipMainActivity"
        const val PATH_REQUEST_TICKET = "/bip/request_ticket"
        const val PATH_TICKET_DATA = "/bip/ticket_data"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
        const val PATH_QR_REQUEST = "/qrGenerationRequest"
        private const val REQUEST_TIMEOUT_MS = 25000L // 25 segundos
    }

    private lateinit var layoutIdle: LinearLayout
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutTicket: LinearLayout

    private lateinit var btnGenerate: Button
    private lateinit var txtIdleSubtitle: TextView
    private lateinit var txtLoading: TextView
    private lateinit var imgQrCode: ImageView
    private lateinit var txtTimer: TextView

    private var countDownTimer: CountDownTimer? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (TicketManager.ticketState.value is TicketState.Loading) {
            Log.w(TAG, "Tiempo de espera agotado sin respuesta del teléfono")
            Toast.makeText(
                this,
                "El teléfono no respondió. Verifica que la app puente BipWear esté instalada en el teléfono.",
                Toast.LENGTH_LONG
            ).show()
            TicketManager.setState(TicketState.Idle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        observeTicketState()
    }

    override fun onResume() {
        super.onResume()
        // Escucha en tiempo real tanto DataItems como Mensajes de Google Play Services Wearable
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
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

        btnGenerate.setOnClickListener {
            solicitarGeneracionPasaje()
        }

        layoutTicket.setOnClickListener {
            solicitarGeneracionPasaje()
        }
    }

    private fun observeTicketState() {
        lifecycleScope.launch {
            TicketManager.ticketState.collect { state ->
                when (state) {
                    is TicketState.Idle -> showIdleView()
                    is TicketState.Loading -> showLoadingView(state.message)
                    is TicketState.Active -> {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        showTicketView(state.ticket)
                    }
                    is TicketState.Error -> {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        showErrorView(state.errorMessage)
                    }
                }
            }
        }
    }

    private fun solicitarGeneracionPasaje() {
        TicketManager.setState(TicketState.Loading(getString(R.string.status_requesting)))
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes: List<Node> = nodeClient.connectedNodes.await()
                val phoneNode = nodes.firstOrNull { it.isNearby }

                if (phoneNode == null) {
                    withContext(Dispatchers.Main) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        Toast.makeText(this@MainActivity, getString(R.string.status_no_phone), Toast.LENGTH_SHORT).show()
                        TicketManager.setState(TicketState.Idle)
                    }
                    return@launch
                }

                Log.d(TAG, "Enviando $PATH_REQUEST_TICKET al teléfono: ${phoneNode.displayName} (${phoneNode.id})")
                val messageClient = Wearable.getMessageClient(this@MainActivity)
                messageClient.sendMessage(phoneNode.id, PATH_REQUEST_TICKET, byteArrayOf()).await()

                withContext(Dispatchers.Main) {
                    txtLoading.text = getString(R.string.status_generating)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al enviar mensaje al teléfono", e)
                withContext(Dispatchers.Main) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    TicketManager.setState(TicketState.Idle)
                }
            }
        }
    }

    /**
     * Captura eventos de DataClient en tiempo real mientras MainActivity está abierta.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "MainActivity.onDataChanged: ${dataEvents.count} eventos recibidos")
        val dataClient = Wearable.getDataClient(this)

        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path ?: ""
                Log.d(TAG, "DataEvent path en MainActivity: $path")

                if (path.startsWith("/qr") || path.startsWith("/bip")) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    lifecycleScope.launch(Dispatchers.IO) {
                        val success = TicketManager.processDataMap(dataClient, dataMap)
                        if (success) {
                            withContext(Dispatchers.Main) {
                                timeoutHandler.removeCallbacks(timeoutRunnable)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Captura mensajes en tiempo real mientras MainActivity está abierta.
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        Log.d(TAG, "MainActivity.onMessageReceived en path: $path, bytes: ${data?.size ?: 0}")

        if (path == PATH_TICKET_ERROR) {
            val errorMsg = if (data != null && data.isNotEmpty()) {
                String(data, StandardCharsets.UTF_8)
            } else {
                "Error al generar pasaje en el teléfono"
            }
            timeoutHandler.removeCallbacks(timeoutRunnable)
            TicketManager.setState(TicketState.Error(errorMsg))
            return
        }

        if (path.startsWith("/qr") || path.startsWith("/bip")) {
            if (data != null && data.isNotEmpty()) {
                val success = TicketManager.processPayload(data)
                if (success) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                }
            }
        }
    }

    private fun showIdleView() {
        resetScreenBrightness()
        countDownTimer?.cancel()

        layoutIdle.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutTicket.visibility = View.GONE
    }

    private fun showLoadingView(message: String) {
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        layoutTicket.visibility = View.GONE
        txtLoading.text = message
    }

    private fun showTicketView(ticket: TicketData) {
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        layoutTicket.visibility = View.VISIBLE

        imgQrCode.setImageBitmap(ticket.bitmap)
        maximizeScreenBrightness()

        countDownTimer?.cancel()
        val durationMs = (ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(10 * 1000)

        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) % 60
                val minutes = (millisUntilFinished / (1000 * 60)) % 60
                txtTimer.text = String.format("%02d:%02d", minutes, seconds)
            }

            override fun onFinish() {
                txtTimer.text = getString(R.string.status_expired)
                txtTimer.setTextColor(getColor(R.color.red_primary))
            }
        }.start()
    }

    private fun showErrorView(message: String) {
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
        timeoutHandler.removeCallbacks(timeoutRunnable)
        countDownTimer?.cancel()
    }
}
