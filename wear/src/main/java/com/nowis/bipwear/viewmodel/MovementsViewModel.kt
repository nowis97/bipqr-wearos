package com.nowis.bipwear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nowis.bipwear.data.BipRepository
import com.nowis.bipwear.ui.state.MovementsUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MovementsViewModel(
    private val repository: BipRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MovementsUiState(
            isLoading = false,
            movements = repository.getCachedMovements()
        )
    )
    val uiState: StateFlow<MovementsUiState> = _uiState.asStateFlow()

    init {
        refresh(forceNetwork = false)
    }

    fun refresh(forceNetwork: Boolean = true) {
        val currentList = _uiState.value.movements
        if (forceNetwork || currentList.isEmpty()) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }

        viewModelScope.launch(ioDispatcher) {
            val result = repository.fetchMovements()
            result.onSuccess { fresh ->
                _uiState.update { it.copy(isLoading = false, movements = fresh, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        movements = if (currentList.isNotEmpty()) currentList else it.movements,
                        errorMessage = err.localizedMessage ?: "Error de red"
                    )
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: BipRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MovementsViewModel(repository, ioDispatcher) as T
                }
            }
    }
}
