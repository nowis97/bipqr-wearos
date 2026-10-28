package com.nowis.bipwear.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.nowis.bipwear.R
import com.nowis.bipwear.StandaloneConfig
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdleScreen(
    cfg: StandaloneConfig,
    isOfflineReady: Boolean,
    onGenerate: () -> Unit,
    onRelink: () -> Unit,
    onRefreshDaleQr: () -> Unit,
    onViewMovements: () -> Unit
) {
    val isProvisioned = cfg.isProvisioned

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isProvisioned) {
            val rawProgress = cfg.daleQrProgress
            val progress = when {
                cfg.isDaleQrReached -> 1f
                rawProgress > 0f -> rawProgress.coerceIn(0.035f, 1f)
                else -> 0f
            }
            val ringColor = if (cfg.isDaleQrReached) Color(0xFF00E676) else Color(0xFF00E5FF)
            val ringTrackColor = if (cfg.isDaleQrReached) Color(0x3300E676) else Color(0x3300E5FF)

            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                startAngle = 295f,
                endAngle = 245f,
                indicatorColor = ringColor,
                trackColor = ringTrackColor,
                strokeWidth = 4.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            if (isProvisioned) {
                val isLowBalance = cfg.isLowBalance
                val balanceColor = if (isLowBalance) Color(0xFFFFB74D) else Color.White

                Text(
                    text = "bip!QR",
                    color = Color(0xFFE50914),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(cfg.saldo)
                Text(
                    text = "$$clp",
                    color = balanceColor,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isLowBalance) {
                        Text(
                            text = "⚠️ Saldo bajo · ",
                            color = Color(0xFFFFB74D),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isOfflineReady) Color(0xFF00E676) else Color(0xFF00E5FF))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isOfflineReady) "Offline listo" else "Online",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                DaleQrBadge(
                    acumulado = cfg.daleQrAcumulado,
                    meta = cfg.daleQrMeta,
                    isReached = cfg.isDaleQrReached,
                    onClick = onRefreshDaleQr
                )

                Spacer(modifier = Modifier.height(7.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFE50914))
                        .combinedClickable(
                            onClick = onGenerate,
                            onLongClick = onRelink
                        )
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.btn_generate),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x2EFFFFFF))
                        .clickable { onViewMovements() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📋 Movimientos",
                        color = Color(0xFFE2E8F0),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = "bip!QR",
                    color = Color(0xFFE50914),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.status_not_provisioned),
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFFE50914))
                        .clickable { onRelink() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.btn_link_clave_unica),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun DaleQrBadge(
    acumulado: Int,
    meta: Int,
    isReached: Boolean,
    onClick: () -> Unit
) {
    val metaClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(meta)
    val acumClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(acumulado)

    if (isReached) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D3818))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎉 ¡Viajes Gratis este mes!",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Dale QR: $$acumClp superado",
                    color = Color(0xFFB9F6CA),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        val progress = if (meta > 0) (acumulado.toFloat() / meta.toFloat()).coerceIn(0f, 1f) else 0f
        val pct = (progress * 100).toInt()

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x2200E5FF))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Dale QR: $$acumClp / $$metaClp ($pct%)",
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
