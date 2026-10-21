package com.nowis.bipwear.ui.state

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.TicketData

sealed interface MainUiState {
    object Idle : MainUiState
    data class Loading(val message: String) : MainUiState
    data class Active(val ticket: TicketData) : MainUiState
    data class Error(val errorMessage: String) : MainUiState
    object Movements : MainUiState
}

data class MovementsUiState(
    val isLoading: Boolean = false,
    val movements: List<BipMovement> = emptyList(),
    val errorMessage: String? = null
)
