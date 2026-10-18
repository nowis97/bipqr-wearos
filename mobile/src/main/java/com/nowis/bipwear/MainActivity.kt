package com.nowis.bipwear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BipPhoneMainActivity"
    }

    private lateinit var imgAccessibilityDot: ImageView
    private lateinit var txtAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: Button

    private lateinit var imgWatchDot: ImageView
    private lateinit var txtWatchStatus: TextView
    private lateinit var btnRefreshWatch: Button

    private lateinit var btnOpenRedApp: Button
    private lateinit var btnCaptureRealTicket: Button
    private lateinit var btnSendTestQr: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityState()
        refreshConnectedWatches()
    }

    private fun initViews() {
        imgAccessibilityDot = findViewById(R.id.imgAccessibilityStatusDot)
        txtAccessibilityStatus = findViewById(R.id.txtAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        imgWatchDot = findViewById(R.id.imgWatchStatusDot)
        txtWatchStatus = findViewById(R.id.txtWatchStatus)
        btnRefreshWatch = findViewById(R.id.btnRefreshWatch)

        btnOpenRedApp = findViewById(R.id.btnOpenRedApp)
        btnCaptureRealTicket = findViewById(R.id.btnCaptureRealTicket)
        btnSendTestQr = findViewById(R.id.btnSendTestQr)
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        btnRefreshWatch.setOnClickListener {
            refreshConnectedWatches()
        }

        btnOpenRedApp.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.sonda.wiu")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Red Movilidad no está instalada.", Toast.LENGTH_SHORT).show()
            }
        }

        btnCaptureRealTicket.setOnClickListener {
            if (!BipAccessibilityService.isRunning()) {
                Toast.makeText(this, "Por favor activa primero el servicio de accesibilidad arriba.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Iniciando generación en Red Movilidad...", Toast.LENGTH_SHORT).show()
            BipAccessibilityService.triggerTicketCapture(
                context = this,
                onSuccess = { ticketBytes ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                            val messageClient = Wearable.getMessageClient(this@MainActivity)
                            for (node in nodes) {
                                messageClient.sendMessage(node.id, "/bip/ticket_data", ticketBytes).await()
                                messageClient.sendMessage(node.id, "/qrDataSync", ticketBytes).await()
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "¡Pasaje real enviado al smartwatch!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error enviando pasaje", e)
                        }
                    }
                },
                onError = { error ->
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            )
        }

        btnSendTestQr.setOnClickListener {
            sendTestTicketToWatch()
        }
    }

    private fun updateAccessibilityState() {
        val isRunning = BipAccessibilityService.isRunning()
        if (isRunning) {
            imgAccessibilityDot.setImageResource(R.drawable.dot_green)
            txtAccessibilityStatus.text = "Servicio Activo y Listo"
            txtAccessibilityStatus.setTextColor(getColor(R.color.green_status))
            btnEnableAccessibility.text = "Accesibilidad Configurada"
            btnEnableAccessibility.alpha = 0.7f
        } else {
            imgAccessibilityDot.setImageResource(R.drawable.dot_red)
            txtAccessibilityStatus.text = "Servicio Desactivado (Toca abajo para activar)"
            txtAccessibilityStatus.setTextColor(getColor(R.color.red_status))
            btnEnableAccessibility.text = "Activar Servicio de Accesibilidad"
            btnEnableAccessibility.alpha = 1.0f
        }
    }

    private fun refreshConnectedWatches() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes: List<Node> = nodeClient.connectedNodes.await()
                val watchNode = nodes.firstOrNull { it.isNearby }

                withContext(Dispatchers.Main) {
                    if (watchNode != null) {
                        imgWatchDot.setImageResource(R.drawable.dot_green)
                        txtWatchStatus.text = "Conectado a: ${watchNode.displayName}"
                        txtWatchStatus.setTextColor(getColor(R.color.green_status))
                    } else if (nodes.isNotEmpty()) {
                        imgWatchDot.setImageResource(R.drawable.dot_green)
                        txtWatchStatus.text = "Conectado a: ${nodes.first().displayName}"
                        txtWatchStatus.setTextColor(getColor(R.color.green_status))
                    } else {
                        imgWatchDot.setImageResource(R.drawable.dot_red)
                        txtWatchStatus.text = "Ningún smartwatch Wear OS detectado por Bluetooth"
                        txtWatchStatus.setTextColor(getColor(R.color.red_status))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error buscando nodos Wearable", e)
            }
        }
    }

    private fun sendTestTicketToWatch() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No hay ningún reloj conectado.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Generar código Aztec demo
                val testBitmap = AztecTicketProcessor.generateCleanAztecBitmap("TEST-BIP-QR-OK-2026", 340)
                if (testBitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error generando imagen Aztec.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val bytes = AztecTicketProcessor.toByteArray(testBitmap)
                val messageClient = Wearable.getMessageClient(this@MainActivity)

                for (node in nodes) {
                    messageClient.sendMessage(node.id, "/bip/ticket_data", bytes).await()
                    messageClient.sendMessage(node.id, "/qrDataSync", bytes).await()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "¡Pasaje de prueba enviado a ${nodes.size} reloj(es)!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando boleto de prueba", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Fallo al enviar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
