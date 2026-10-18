package com.nowis.bipwear

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BipPhoneMainActivity"
    }

    private lateinit var imgWatchDot: ImageView
    private lateinit var txtWatchStatus: TextView
    private lateinit var btnRefreshWatch: Button

    private lateinit var imgAccountDot: ImageView
    private lateinit var txtAccountStatus: TextView
    private lateinit var inputEmail: EditText
    private lateinit var inputPhone: EditText
    private lateinit var btnLoginClaveUnica: Button
    private lateinit var btnSyncToWatch: Button

    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAccountState()
        refreshConnectedWatches()
    }

    private fun initViews() {
        imgWatchDot = findViewById(R.id.imgWatchStatusDot)
        txtWatchStatus = findViewById(R.id.txtWatchStatus)
        btnRefreshWatch = findViewById(R.id.btnRefreshWatch)

        imgAccountDot = findViewById(R.id.imgAccountStatusDot)
        txtAccountStatus = findViewById(R.id.txtAccountStatus)
        inputEmail = findViewById(R.id.inputEmail)
        inputPhone = findViewById(R.id.inputPhone)
        btnLoginClaveUnica = findViewById(R.id.btnLoginClaveUnica)
        btnSyncToWatch = findViewById(R.id.btnSyncToWatch)

        val cfg = BipC2dEngine.loadConfig(this)
        if (cfg.email.isNotEmpty() && inputEmail.text.isEmpty()) {
            inputEmail.setText(cfg.email)
        }
        if (cfg.phoneNumber.isNotEmpty() && inputPhone.text.isEmpty()) {
            inputPhone.setText(cfg.phoneNumber)
        }
    }

    private fun setupListeners() {
        btnRefreshWatch.setOnClickListener {
            refreshConnectedWatches()
        }

        btnLoginClaveUnica.setOnClickListener {
            startClaveUnicaFlow()
        }

        btnSyncToWatch.setOnClickListener {
            refreshAndSyncToWatch()
        }
    }

    private fun updateAccountState() {
        val cfg = BipC2dEngine.loadConfig(this)
        if (cfg.isProvisioned) {
            val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(cfg.saldo)
            val seedValid = if (BipC2dEngine.isTramaValid(this)) "Semilla 2h activa" else "Semilla renovable"
            imgAccountDot.setImageResource(R.drawable.dot_green)
            txtAccountStatus.text = "Activa: ${cfg.rut} · Cuenta ${cfg.nuAbt}\nSaldo: $$clp ($seedValid)"
            txtAccountStatus.setTextColor(getColor(R.color.green_status))
        } else {
            imgAccountDot.setImageResource(R.drawable.dot_red)
            txtAccountStatus.text = "Sin vincular (Inicia sesión con ClaveÚnica abajo)"
            txtAccountStatus.setTextColor(getColor(R.color.red_status))
        }
    }

    private fun refreshConnectedWatches() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodes: List<Node> = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val watchNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

                withContext(Dispatchers.Main) {
                    if (watchNode != null) {
                        imgWatchDot.setImageResource(R.drawable.dot_green)
                        txtWatchStatus.text = "Conectado a: ${watchNode.displayName}"
                        txtWatchStatus.setTextColor(getColor(R.color.green_status))
                    } else {
                        imgWatchDot.setImageResource(R.drawable.dot_red)
                        txtWatchStatus.text = "Ningún smartwatch Wear OS detectado"
                        txtWatchStatus.setTextColor(getColor(R.color.red_status))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error buscando nodos Wearable", e)
            }
        }
    }

    private fun startClaveUnicaFlow() {
        btnLoginClaveUnica.isEnabled = false
        Toast.makeText(this, "Conectando con ClaveÚnica...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startInfo = RedApiClient.startClaveUnica(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnLoginClaveUnica.isEnabled = true
                    showClaveUnicaWebViewDialog(startInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando ClaveÚnica", e)
                withContext(Dispatchers.Main) {
                    btnLoginClaveUnica.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showClaveUnicaWebViewDialog(startInfo: ClaveUnicaStartInfo) {
        pollingJob?.cancel()
        var completed = false

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.78).toInt()
            )
        }

        var dialogRef: AlertDialog? = null

        fun checkLoginStateNow() {
            if (completed) return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val profile = RedApiClient.checkClaveUnicaLogged(this@MainActivity, startInfo.sessionToken)
                    if (profile != null && !completed) {
                        completed = true
                        withContext(Dispatchers.Main) {
                            dialogRef?.dismiss()
                            completeAccountProvisioning(startInfo.sessionToken, profile)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Verificando login ClaveÚnica: ${e.message}")
                }
            }
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains("/claveunica/done") || url.contains("code=") || url.startsWith(startInfo.redirectUri)) {
                        checkLoginStateNow()
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.requestFocus(View.FOCUS_DOWN)
                    if (url != null && (url.contains("/claveunica/done") || url.contains("code=") || url.startsWith(startInfo.redirectUri))) {
                        checkLoginStateNow()
                    }
                }
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                        if (!v.hasFocus()) {
                            v.requestFocus()
                        }
                    }
                }
                false
            }
            loadUrl(startInfo.authUrl)
        }
        container.addView(webView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Iniciar Sesión con ClaveÚnica")
            .setView(container)
            .setNegativeButton("Cancelar") { d, _ ->
                pollingJob?.cancel()
                d.dismiss()
            }
            .setOnDismissListener {
                pollingJob?.cancel()
                webView.destroy()
            }
            .create()

        dialogRef = dialog
        dialog.show()

        // Permite explícitamente que el teclado en pantalla (IME) aparezca al tocar campos de texto en el WebView
        dialog.window?.apply {
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        webView.requestFocus(View.FOCUS_DOWN)

        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !completed) {
                delay(2000)
                try {
                    val profile = RedApiClient.checkClaveUnicaLogged(this@MainActivity, startInfo.sessionToken)
                    if (profile != null && !completed) {
                        completed = true
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            completeAccountProvisioning(startInfo.sessionToken, profile)
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Esperando login ClaveÚnica: ${e.message}")
                }
            }
        }
    }

    private fun completeAccountProvisioning(cuSessionToken: String, profile: ClaveUnicaUserProfile) {
        val overrideEmail = inputEmail.text.toString().trim()
        val overridePhone = inputPhone.text.toString().trim()

        Toast.makeText(this, "Activando llave C2D para ${profile.rut}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Sign in to Red Movilidad backend
                RedApiClient.signInWithClaveUnica(
                    context = this@MainActivity,
                    cuSessionToken = cuSessionToken,
                    profile = profile,
                    overrideEmail = overrideEmail,
                    overridePhone = overridePhone
                )

                // 2. Enroll or update C2D EC secp224r1 public key on bip!QR account
                RedApiClient.enrollOrUpdateAccount(this@MainActivity)

                // 3. Request initial 2-hour C2D seed (`tramaSistemaCentral`)
                val finalCfg = RedApiClient.requestC2dSeed(this@MainActivity)

                // 4. Push complete StandaloneConfig to the Wear OS watch
                val syncedWatchCount = PhoneWearListenerService.pushConfigToWatches(this@MainActivity, finalCfg)

                withContext(Dispatchers.Main) {
                    if (finalCfg.email.isNotEmpty()) inputEmail.setText(finalCfg.email)
                    if (finalCfg.phoneNumber.isNotEmpty()) inputPhone.setText(finalCfg.phoneNumber)
                    updateAccountState()
                    Toast.makeText(
                        this@MainActivity,
                        "¡Listo! Cuenta bip!QR y llave C2D enviadas a $syncedWatchCount reloj(es).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en provisión de cuenta bip!QR", e)
                withContext(Dispatchers.Main) {
                    updateAccountState()
                    Toast.makeText(
                        this@MainActivity,
                        "Error activando cuenta: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshAndSyncToWatch() {
        val cfg = BipC2dEngine.loadConfig(this)
        if (!cfg.isProvisioned) {
            Toast.makeText(this, "Primero inicia sesión con ClaveÚnica.", Toast.LENGTH_SHORT).show()
            return
        }

        btnSyncToWatch.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val updatedCfg = try {
                    RedApiClient.requestC2dSeed(this@MainActivity)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo renovar semilla online (${e.message}), sincronizando configuración actual")
                    cfg
                }

                val count = PhoneWearListenerService.pushConfigToWatches(this@MainActivity, updatedCfg)
                withContext(Dispatchers.Main) {
                    btnSyncToWatch.isEnabled = true
                    updateAccountState()
                    Toast.makeText(
                        this@MainActivity,
                        "Configuración y semilla C2D sincronizadas con $count reloj(es).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sincronizando con el reloj", e)
                withContext(Dispatchers.Main) {
                    btnSyncToWatch.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Error al sincronizar: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
