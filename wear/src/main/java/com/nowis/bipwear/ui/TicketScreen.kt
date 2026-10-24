package com.nowis.bipwear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.nowis.bipwear.R
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.TicketData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale

@Composable
fun TicketScreen(
    ticket: TicketData,
    cfg: StandaloneConfig,
    onRefresh: () -> Unit,
    onAutoRefreshTriggered: () -> Unit,
    onViewMovements: () -> Unit
) {
    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive) {
            val intervalMs = (ticket.refreshIntervalSec * 1000L).coerceAtLeast(3000L)
            delay(intervalMs)
            onAutoRefreshTriggered()
        }
    }

    var remainingMs by remember(ticket.expiryTimestampMs) {
        mutableStateOf((ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive && remainingMs > 0) {
            delay(1000)
            remainingMs = (ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }

    val isExpired = remainingMs <= 0
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val timerText = if (isExpired) {
        stringResource(R.string.status_expired)
    } else if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
    val timerColor = if (isExpired) Color(0xFFEF4444) else Color(0xFFF1F5F9)
    val timerBgColor = if (isExpired) Color(0x33EF4444) else Color(0x2EFFFFFF)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Anillo Dale QR en el bisel exterior
        if (cfg.isProvisioned) {
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
                startAngle = 270f,
                endAngle = 270f,
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
                .padding(bottom = 2.dp)
        ) {
            // Option A: Pill chip translúcido con temporizador
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(timerBgColor)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isExpired) timerText else "⏳ $timerText",
                    color = timerColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Contenedor blanco de alto contraste para lectura óptica en torniquetes
            Box(
                modifier = Modifier
                    .size(122.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .clickable { onRefresh() }
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = ticket.bitmap.asImageBitmap(),
                    contentDescription = "bip!QR Ticket",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(ticket.saldo)
            val instructionsText = if (ticket.saldo > 0) {
                "Saldo: $$clp"
            } else {
                stringResource(R.string.status_ticket_ready)
            }

            Text(
                text = instructionsText,
                color = Color(0xFFE2E8F0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x2EFFFFFF))
                    .clickable { onViewMovements() }
                    .padding(horizontal = 9.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📋 Movimientos",
                    color = Color(0xFFF1F5F9),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
