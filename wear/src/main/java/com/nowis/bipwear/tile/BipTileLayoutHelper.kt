package com.nowis.bipwear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import com.nowis.bipwear.MainActivity
import com.nowis.bipwear.R
import com.nowis.bipwear.StandaloneConfig
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object BipTileLayoutHelper {
    const val COLOR_RED_BRAND = 0xFFE50914.toInt()
    const val COLOR_CYAN_DALE_QR = 0xFF00E5FF.toInt()
    const val COLOR_GREEN_SUCCESS = 0xFF00E676.toInt()
    const val COLOR_TRACK = 0x3300E5FF.toInt()
    const val COLOR_MUTED_GRAY = 0xFF9E9E9E.toInt()
    const val COLOR_WHITE = 0xFFFFFFFF.toInt()

    fun calculateDaleQrProgress(config: StandaloneConfig): Float {
        if (config.daleQrAcumulado <= 0) return 0f
        val fraction = config.daleQrAcumulado.toFloat() / 42000f
        return fraction.coerceIn(0f, 1f)
    }

    fun getDaleQrProgressColor(config: StandaloneConfig): Int {
        return if (config.daleQrAcumulado >= 42000) COLOR_GREEN_SUCCESS else COLOR_CYAN_DALE_QR
    }

    fun getDaleQrStatusText(config: StandaloneConfig): String {
        return if (config.daleQrAcumulado >= 42000) {
            "🎉 ¡Viajes Gratis!"
        } else {
            val pct = (calculateDaleQrProgress(config) * 100).toInt()
            "Dale QR: $pct%"
        }
    }

    fun getFormattedBalance(config: StandaloneConfig): String {
        return if (config.saldo > 0) {
            val symbols = DecimalFormatSymbols(Locale("es", "CL")).apply {
                groupingSeparator = '.'
            }
            val clp = DecimalFormat("#,###", symbols).format(config.saldo)
            "$$clp"
        } else {
            "$0"
        }
    }

    fun buildTileLayout(
        context: Context,
        config: StandaloneConfig,
        isOfflineReady: Boolean
    ): LayoutElementBuilders.LayoutElement {
        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())

        // Outer progress arc for Dale QR
        val progress = calculateDaleQrProgress(config)
        val sweepAngle = progress * 300f
        val arcColor = getDaleQrProgressColor(config)

        val arcBuilder = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(120f))
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(300f))
                    .setThickness(dp(6f))
                    .setColor(argb(COLOR_TRACK))
                    .build()
            )

        if (sweepAngle > 0f) {
            arcBuilder.addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(sweepAngle))
                    .setThickness(dp(6f))
                    .setColor(argb(arcColor))
                    .build()
            )
        }
        rootBox.addContent(arcBuilder.build())

        // Center content column
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Title: bip!QR
        column.addContent(
            Text.Builder(context, "bip!QR")
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setColor(argb(COLOR_RED_BRAND))
                .build()
        )

        if (config.isProvisioned) {
            // Balance
            column.addContent(
                Text.Builder(context, getFormattedBalance(config))
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                    .setColor(argb(COLOR_WHITE))
                    .build()
            )

            // Dale QR progress caption
            column.addContent(
                Text.Builder(context, getDaleQrStatusText(config))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(arcColor))
                    .build()
            )

            // Status chip: Offline listo vs Online
            val statusText = if (isOfflineReady) {
                context.getString(R.string.tile_status_offline_ready)
            } else {
                context.getString(R.string.tile_status_online)
            }
            column.addContent(
                Text.Builder(context, "● $statusText")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(if (isOfflineReady) COLOR_GREEN_SUCCESS else COLOR_MUTED_GRAY))
                    .build()
            )

            // Spacing
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

            // Fast-path Launch button: Generar QR (EXTRA_AUTO_GENERATE = true)
            val launchAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .addKeyToExtraMapping(
                            MainActivity.EXTRA_AUTO_GENERATE,
                            ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build()
                        )
                        .build()
                )
                .build()

            val clickable = ModifiersBuilders.Clickable.Builder()
                .setId("action_generate_qr")
                .setOnClick(launchAction)
                .build()

            val generateButton = Button.Builder(context, clickable)
                .setTextContent(context.getString(R.string.tile_btn_generate))
                .setButtonColors(ButtonColors(COLOR_RED_BRAND, COLOR_WHITE))
                .setSize(dp(48f))
                .build()

            column.addContent(generateButton)
        } else {
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            column.addContent(
                Text.Builder(context, context.getString(R.string.tile_status_not_linked))
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(argb(COLOR_MUTED_GRAY))
                    .build()
            )
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())

            val linkAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()

            val clickable = ModifiersBuilders.Clickable.Builder()
                .setId("action_link_account")
                .setOnClick(linkAction)
                .build()

            val linkButton = Button.Builder(context, clickable)
                .setTextContent(context.getString(R.string.tile_btn_link))
                .setButtonColors(ButtonColors(COLOR_RED_BRAND, COLOR_WHITE))
                .setSize(dp(48f))
                .build()

            column.addContent(linkButton)
        }

        rootBox.addContent(column.build())
        return rootBox.build()
    }
}
