package com.nowis.bipwear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.ui.state.MovementsUiState
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MovementsScreen(
    uiState: MovementsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isLoading && uiState.movements.isEmpty()) {
            LoadingScreen(message = "Cargando movimientos...")
        } else if (uiState.movements.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                Text(text = "📋", fontSize = 24.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sin movimientos",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tus viajes en bus, metro y tren aparecerán aquí.",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x2EFFFFFF))
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Volver",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onRotaryScrollEvent { event ->
                        coroutineScope.launch {
                            listState.scrollBy(event.verticalScrollPixels)
                        }
                        true
                    },
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable { onRefresh() }
                    ) {
                        Text(
                            text = "Últimos movimientos",
                            color = Color(0xFFE50914),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (uiState.isLoading) "Actualizando..." else "${uiState.movements.size} registrados · Toca para recargar",
                            color = Color.Gray,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                items(
                    items = uiState.movements,
                    key = { item -> if (item.id.isNotBlank()) item.id else "${item.dateTimeStr}_${item.amount}" }
                ) { item ->
                    MovementItemRow(item = item)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x2EFFFFFF))
                            .clickable { onBack() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Volver al inicio",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
fun MovementItemRow(item: BipMovement) {
    val (icon, label, accentColor) = getMovementVisuals(item)
    val formattedDate = formatMovementDate(item.dateTimeStr)

    val amountText = when {
        item.isFreeTrip -> "$0"
        item.isCarga -> "+$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount.coerceAtLeast(0))}"
        item.amount > 0 -> "-$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount)}"
        else -> "$0"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                if (formattedDate.isNotBlank()) {
                    Text(
                        text = formattedDate,
                        color = Color(0xFFAAAAAA),
                        fontSize = 8.sp
                    )
                }
            }
            Text(
                text = amountText,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}

fun getMovementVisuals(item: BipMovement): Triple<String, String, Color> {
    val descLower = item.description.lowercase(Locale.ROOT)
    val typeLower = item.transportType.lowercase(Locale.ROOT)

    return when {
        item.isFreeTrip || descLower.contains("gratis") || descLower.contains("dale") -> {
            Triple("🎉", if (item.description.isNotBlank()) item.description else "Viaje gratis Dale QR", Color(0xFF00E676))
        }
        item.isCarga || descLower.contains("carga") -> {
            Triple("💳", if (item.description.isNotBlank()) item.description else "Carga de saldo", Color(0xFF4CAF50))
        }
        descLower.contains("metro") || typeLower.contains("metro") || descLower.contains("estaci") -> {
            Triple("🚇", if (item.description.isNotBlank()) item.description else "Viaje en Metro", Color(0xFFFF5252))
        }
        descLower.contains("tren") || typeLower.contains("tren") -> {
            Triple("🚆", if (item.description.isNotBlank()) item.description else "Viaje en Tren", Color(0xFFFF9800))
        }
        else -> {
            Triple("🚌", if (item.description.isNotBlank()) item.description else "Viaje en Bus", Color(0xFF00E5FF))
        }
    }
}

private val inputFormatters = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
    SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US),
    SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US),
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
)
private val outputFormatter = SimpleDateFormat("dd MMM, HH:mm", Locale("es", "CL"))
private val dateFormatLock = Any()

fun formatMovementDate(raw: String): String {
    if (raw.isBlank()) return ""
    synchronized(dateFormatLock) {
        for (sdf in inputFormatters) {
            try {
                val date = sdf.parse(raw)
                if (date != null) {
                    return outputFormatter.format(date)
                }
            } catch (_: Exception) {}
        }
    }
    return raw
}
