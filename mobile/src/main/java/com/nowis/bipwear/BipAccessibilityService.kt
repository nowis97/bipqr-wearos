package com.nowis.bipwear

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

    private val timeoutRunnable = Runnable {
        if (isCapturing.get()) {
            Log.w(TAG, "Tiempo agotado para capturar el pasaje")
            finishCapture(null, "Tiempo de espera agotado. Abre Red Movilidad manualmente en tu teléfono.")
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
            Log.d(TAG, "Evento de accesibilidad recibido de $TARGET_PACKAGE: ${event.eventType}")

            // 1. Intentar interactuar con botones si están presentes
            tryClickGenerateButton(rootInActiveWindow)

            // 2. Programar captura de pantalla
            scheduleScreenshotCheck()
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

        acquireWakeLock()

        // Iniciar Red Movilidad
        val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launchIntent)
            Log.i(TAG, "Lanzada aplicación Red Movilidad ($TARGET_PACKAGE)")
        } else {
            finishCapture(null, "La app Red Movilidad no está instalada en este teléfono.")
            return
        }

        // Timeout máximo de 20 segundos
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 20000L)

        // Primer chequeo a los 1.5 segundos (tiempo para que cargue la UI)
        handler.postDelayed({ scheduleScreenshotCheck() }, 1500L)
    }

    private fun scheduleScreenshotCheck() {
        if (!isCapturing.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(null, "Se requiere Android 11 (API 30+) para captura automática de pantalla.")
            return
        }

        attemptsCount++
        if (attemptsCount > 25) {
            // Máximo de intentos alcanzado
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
                // Reintentar en 600ms si seguimos capturando
                if (isCapturing.get()) {
                    handler.postDelayed({ scheduleScreenshotCheck() }, 600L)
                }
            }
        })
    }

    private fun processScreenshot(bitmap: Bitmap) {
        if (!isCapturing.get()) return

        try {
            val ticketBitmap = AztecTicketProcessor.extractTicketBitmap(bitmap)
            val ticketBytes = AztecTicketProcessor.toByteArray(ticketBitmap)

            Log.i(TAG, "¡Pasaje extraído exitosamente! Tamaño: ${ticketBytes.size} bytes")
            finishCapture(ticketBytes, null)

            // Volver al Home para no dejar la pantalla encendida en Red Movilidad
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 800L)

        } catch (e: Exception) {
            Log.d(TAG, "Boleto aún no visible en pantalla, reintentando...")
            if (isCapturing.get()) {
                handler.postDelayed({ scheduleScreenshotCheck() }, 700L)
            }
        }
    }

    private fun tryClickGenerateButton(node: AccessibilityNodeInfo?) {
        if (node == null) return

        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            if (text.contains("generar") || text.contains("pasaje") || text.contains("bip!qr") ||
                desc.contains("generar") || desc.contains("pasaje") || desc.contains("bip!qr")) {
                if (node.isClickable) {
                    Log.i(TAG, "Haciendo clic automático en botón: ${node.text ?: node.contentDescription}")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }

            for (i in 0 until node.childCount) {
                tryClickGenerateButton(node.getChild(i))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error explorando nodo de accesibilidad", e)
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
            wakeLock?.acquire(30000L) // 30 segundos
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
