package com.nowis.bipwear

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class BipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BipAccessibility"
        const val TARGET_PACKAGE = "com.sonda.wiu"

        @Volatile
        var instance: BipAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun triggerTicketCapture(
            context: Context,
            onSuccess: (ByteArray) -> Unit,
            onError: (String) -> Unit
        ) {
            val service = instance
            if (service == null) {
                onError("El servicio de accesibilidad de BipWear no está activo en el teléfono.")
                return
            }
            service.startTicketCaptureSession(context, onSuccess, onError)
        }
    }

    private val isCapturing = AtomicBoolean(false)
    private var successCallback: ((ByteArray) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var attemptsCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasClickedPagar = false
    private var hasClickedGenerar = false

    private val timeoutRunnable = Runnable {
        if (isCapturing.get()) {
            Log.w(TAG, "Tiempo agotado para capturar el pasaje")
            finishCapture(null, "Tiempo de espera agotado. Abre Red Movilidad y toca 'Pagar' -> 'Generar pasaje'.")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Servicio de Accesibilidad BipWear conectado y listo")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        releaseWakeLock()
        Log.i(TAG, "Servicio de Accesibilidad BipWear destruido")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servicio de Accesibilidad BipWear interrumpido")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCapturing.get() || event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == TARGET_PACKAGE) {
            Log.d(TAG, "Evento de accesibilidad en $TARGET_PACKAGE: ${event.eventType}")

            // Intentar avanzar en el flujo de navegación (Pagar -> Generar pasaje)
            tryNavigateAndGenerate(rootInActiveWindow)
        }
    }

    fun startTicketCaptureSession(
        context: Context,
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCapturing.getAndSet(true)) {
            Log.w(TAG, "Ya hay una sesión de captura en curso")
            return
        }

        successCallback = onSuccess
        errorCallback = onError
        attemptsCount = 0
        hasClickedPagar = false
        hasClickedGenerar = false

        acquireWakeLock()

        // 1. Iniciar Red Movilidad
        val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launchIntent)
            Log.i(TAG, "Lanzada aplicación Red Movilidad ($TARGET_PACKAGE)")
        } else {
            finishCapture(null, "La app Red Movilidad no está instalada en este teléfono.")
            return
        }

        // Timeout máximo de 25 segundos
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 25000L)

        // Primer chequeo a los 1.5 segundos
        handler.postDelayed({ scheduleScreenshotCheck() }, 1500L)
    }

    private fun scheduleScreenshotCheck() {
        if (!isCapturing.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(null, "Se requiere Android 11 (API 30+) para captura automática de pantalla.")
            return
        }

        attemptsCount++
        if (attemptsCount > 35) {
            finishCapture(null, "No se detectó el código QR en pantalla. Verifica si Red Movilidad solicita PIN o inicio de sesión.")
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshotResult: ScreenshotResult) {
                val hwBuffer = screenshotResult.hardwareBuffer
                val colorSpace = screenshotResult.colorSpace
                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                hwBuffer.close()

                val softwareBmp = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                if (softwareBmp != null) {
                    processScreenshot(softwareBmp)
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot falló con código $errorCode (intento #$attemptsCount)")
                if (isCapturing.get()) {
                    handler.postDelayed({ scheduleScreenshotCheck() }, 400L)
                }
            }
        })
    }

    private fun processScreenshot(bitmap: Bitmap) {
        if (!isCapturing.get()) return

        // 1. Intentar decodificar código Aztec real
        val ticketBitmap = AztecTicketProcessor.extractTicketBitmap(bitmap)
        if (ticketBitmap != null) {
            val ticketBytes = AztecTicketProcessor.toByteArray(ticketBitmap)
            Log.i(TAG, "¡Pasaje Aztec extraído exitosamente! Tamaño: ${ticketBytes.size} bytes")
            finishCapture(ticketBytes, null)

            // Volver a Home después de capturar
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 600L)
            return
        }

        // 2. Si aún no hay código Aztec, intentar avanzar en la interfaz de Red Movilidad
        Log.d(TAG, "Código aún no presente en pantalla (intento #$attemptsCount). Navegando...")
        val root = rootInActiveWindow
        tryNavigateAndGenerate(root)

        // Si después de varios intentos no logramos hacer click por texto, intentar toque gestual en barra inferior ("Pagar")
        if (attemptsCount == 4 && !hasClickedPagar) {
            tapBottomBarPagar(bitmap.width, bitmap.height)
        } else if (attemptsCount == 8 && !hasClickedGenerar) {
            tapCenterGenerar(bitmap.width, bitmap.height)
        }

        // Programar siguiente escaneo
        if (isCapturing.get()) {
            handler.postDelayed({ scheduleScreenshotCheck() }, 400L)
        }
    }

    /**
     * Busca y presiona automáticamente los botones en orden de prioridad:
     * 1° "Generar pasaje"
     * 2° "Pagar" / "bip!QR"
     */
    private fun tryNavigateAndGenerate(root: AccessibilityNodeInfo?) {
        if (root == null) return

        // Prioridad 1: Si ya estamos en la pantalla de pago, buscar "Generar pasaje"
        if (findAndClickKeyword(root, listOf("generar pasaje", "generar", "pagar pasaje"))) {
            Log.i(TAG, "Clic exitoso en botón Generar pasaje")
            hasClickedGenerar = true
            return
        }

        // Prioridad 2: Si estamos en el mapa u otra pestaña, buscar la pestaña "Pagar" / "bip!QR"
        if (!hasClickedPagar) {
            if (findAndClickKeyword(root, listOf("pagar", "bip!qr", "bip qr", "centro bip"))) {
                Log.i(TAG, "Clic exitoso en pestaña Pagar / bip!QR")
                hasClickedPagar = true
                return
            }
        }
    }

    private fun findAndClickKeyword(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false

        try {
            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""

            for (kw in keywords) {
                if (text == kw || text.contains(kw) || desc == kw || desc.contains(kw)) {
                    if (clickNodeOrParent(node)) {
                        return true
                    }
                }
            }

            for (i in 0 until node.childCount) {
                if (findAndClickKeyword(node.getChild(i), keywords)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error explorando árbol de nodos", e)
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                Log.i(TAG, "Ejecutando ACTION_CLICK en nodo: [${current.text ?: current.contentDescription}]")
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }

        // Si no está marcado como clickable (típico en algunos widgets Flutter), despachar toque en sus coordenadas
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            Log.i(TAG, "Despachando toque gestual en coordenadas: (${rect.centerX()}, ${rect.centerY()})")
            dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }

        return false
    }

    /**
     * Toca la posición típica del botón "Pagar" en la barra inferior de Red Movilidad
     * (centro de la pantalla, borde inferior).
     */
    private fun tapBottomBarPagar(screenWidth: Int, screenHeight: Int) {
        val tapX = screenWidth * 0.50f
        val tapY = screenHeight * 0.94f
        Log.i(TAG, "Simulando toque en barra inferior Pagar: ($tapX, $tapY)")
        dispatchTap(tapX, tapY)
        hasClickedPagar = true
    }

    /**
     * Toca la posición típica del botón grande "Generar pasaje" en la pantalla de bip!QR.
     */
    private fun tapCenterGenerar(screenWidth: Int, screenHeight: Int) {
        val tapX = screenWidth * 0.50f
        val tapY = screenHeight * 0.60f
        Log.i(TAG, "Simulando toque en área central Generar pasaje: ($tapX, $tapY)")
        dispatchTap(tapX, tapY)
        hasClickedGenerar = true
    }

    private fun dispatchTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    private fun finishCapture(ticketData: ByteArray?, errorMessage: String?) {
        handler.removeCallbacks(timeoutRunnable)
        if (isCapturing.compareAndSet(true, false)) {
            releaseWakeLock()
            if (ticketData != null) {
                successCallback?.invoke(ticketData)
            } else if (errorMessage != null) {
                errorCallback?.invoke(errorMessage)
            }
            successCallback = null
            errorCallback = null
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "bipwear:capture_wakelock"
                )
            }
            wakeLock?.acquire(30000L)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando WakeLock", e)
        }
    }
}
