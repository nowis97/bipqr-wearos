package com.sonda.wiu

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    companion object {
        private const val TAG = "BipMainActivity"
        const val PATH_QR_REQUEST = "/qrGenerationRequest"
        const val PATH_QR_ENABLED = "/qrGenerationEnabled"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        observeTicketState()
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

        // Tocar el boleto permite refrescar o solicitar uno nuevo
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
                    is TicketState.Active -> showTicketView(state.ticket)
                    is TicketState.Error -> showErrorView(state.errorMessage)
                }
            }
        }
    }

    private fun solicitarGeneracionPasaje() {
        TicketManager.setState(TicketState.Loading(getString(R.string.status_requesting)))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes: List<Node> = nodeClient.connectedNodes.await()
                val phoneNode = nodes.firstOrNull { it.isNearby }

                if (phoneNode == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.status_no_phone), Toast.LENGTH_SHORT).show()
                        TicketManager.setState(TicketState.Idle)
                    }
                    return@launch
                }

                Log.d(TAG, "Enviando $PATH_QR_REQUEST al nodo del teléfono: ${phoneNode.displayName} (${phoneNode.id})")
                val messageClient = Wearable.getMessageClient(this@MainActivity)
                messageClient.sendMessage(phoneNode.id, PATH_QR_REQUEST, byteArrayOf()).await()

                withContext(Dispatchers.Main) {
                    txtLoading.text = getString(R.string.status_generating)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al enviar mensaje al teléfono", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    TicketManager.setState(TicketState.Idle)
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

        // Iniciar temporizador de caducidad
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
        countDownTimer?.cancel()
    }
}
