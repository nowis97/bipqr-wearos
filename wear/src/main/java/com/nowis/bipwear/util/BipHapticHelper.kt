package com.nowis.bipwear.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object BipHapticHelper {
    private const val TAG = "BipHapticHelper"
    const val TICKET_READY_DURATION_MS = 45L

    fun vibrateTicketReady(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } catch (e: Exception) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            TICKET_READY_DURATION_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        TICKET_READY_DURATION_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    internal fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
