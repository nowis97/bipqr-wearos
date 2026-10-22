package com.nowis.bipwear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.nowis.bipwear.MainActivity
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.tile.BipTileLayoutHelper

object BipComplicationHelper {
    const val TITLE_TEXT = "bip!"
    const val DALE_QR_MAX_VALUE = 42000f
    const val EXTRA_AUTO_GENERATE = "extra_auto_generate"

    fun getBalanceText(config: StandaloneConfig): String {
        return BipTileLayoutHelper.getFormattedBalance(config)
    }

    fun getDaleQrValue(config: StandaloneConfig): Float {
        return config.daleQrAcumulado.toFloat().coerceAtLeast(0f)
    }

    fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_AUTO_GENERATE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildComplicationData(
        context: Context,
        type: ComplicationType,
        config: StandaloneConfig
    ): ComplicationData? {
        val balance = getBalanceText(config)
        val tapAction = createPendingIntent(context)
        val title = PlainComplicationText.Builder(TITLE_TEXT).build()
        val text = PlainComplicationText.Builder(balance).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = PlainComplicationText.Builder("Saldo bip! $balance").build()
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                val value = getDaleQrValue(config)
                RangedValueComplicationData.Builder(
                    value = value,
                    min = 0f,
                    max = DALE_QR_MAX_VALUE,
                    contentDescription = PlainComplicationText.Builder("Dale QR: $value de $DALE_QR_MAX_VALUE").build()
                )
                    .setText(text)
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .build()
            }
            else -> null
        }
    }
}
