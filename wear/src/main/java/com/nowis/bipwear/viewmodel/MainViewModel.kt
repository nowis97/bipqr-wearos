package com.nowis.bipwear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.TicketData
import com.nowis.bipwear.data.BipRepository
import com.nowis.bipwear.ui.state.MainUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: BipRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _config = MutableStateFlow(repository.getConfig())
    val config: StateFlow<StandaloneConfig> = _config.asStateFlow()

    fun refreshConfig() {
        _config.value = repository.getConfig()
    }

    fun setState(state: MainUiState) {
        _uiState.value = state
    }

    fun resetToIdle() {
        _uiState.value = MainUiState.Idle
    }

    fun setError(message: String) {
        _uiState.value = MainUiState.Error(message)
    }

    fun setTicketActive(ticket: TicketData) {
        _uiState.value = MainUiState.Active(ticket)
    }

    fun refreshDaleQr(onResult: (String) -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            val result = repository.refreshDaleQr()
            result.onSuccess { updated ->
                _config.value = updated
                onResult("Dale QR: $${updated.daleQrAcumulado} / $${updated.daleQrMeta}")
            }.onFailure { err ->
                onResult("Error Dale QR: ${err.message}")
            }
        }
    }

    fun importConfigJson(jsonStr: String): Boolean {
        val success = repository.importConfigJson(jsonStr)
        if (success) {
            refreshConfig()
        }
        return success
    }

    companion object {
        fun provideFactory(
            repository: BipRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(repository, ioDispatcher) as T
                }
            }
    }
}
