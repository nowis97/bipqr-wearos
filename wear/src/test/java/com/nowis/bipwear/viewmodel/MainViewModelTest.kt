package com.nowis.bipwear.viewmodel

import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.data.FakeBipRepository
import com.nowis.bipwear.ui.state.MainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStateIsIdleAndLoadsConfig() = runTest {
        val repo = FakeBipRepository(config = StandaloneConfig(rut = "12345678-9", saldo = 5000))
        val viewModel = MainViewModel(repo)

        assertTrue(viewModel.uiState.value is MainUiState.Idle)
        assertEquals("12345678-9", viewModel.config.value.rut)
        assertEquals(5000, viewModel.config.value.saldo)
    }

    @Test
    fun testResetToIdleSetsStateToIdle() = runTest {
        val repo = FakeBipRepository()
        val viewModel = MainViewModel(repo)
        viewModel.setError("Some error")
        assertTrue(viewModel.uiState.value is MainUiState.Error)

        viewModel.resetToIdle()
        assertTrue(viewModel.uiState.value is MainUiState.Idle)
    }

    @Test
    fun testSetErrorUpdatesUiState() = runTest {
        val repo = FakeBipRepository()
        val viewModel = MainViewModel(repo)
        viewModel.setError("Error de red")

        val state = viewModel.uiState.value
        assertTrue(state is MainUiState.Error)
        assertEquals("Error de red", (state as MainUiState.Error).errorMessage)
    }

    @Test
    fun testSetStateUpdatesStateDirectly() = runTest {
        val repo = FakeBipRepository()
        val viewModel = MainViewModel(repo)

        viewModel.setState(MainUiState.Loading("Cargando pasaje..."))
        val loadingState = viewModel.uiState.value
        assertTrue(loadingState is MainUiState.Loading)
        assertEquals("Cargando pasaje...", (loadingState as MainUiState.Loading).message)

        viewModel.setState(MainUiState.Movements)
        assertTrue(viewModel.uiState.value is MainUiState.Movements)
    }

    @Test
    fun testRefreshConfigUpdatesConfigFlow() = runTest {
        val repo = FakeBipRepository(config = StandaloneConfig(rut = "11111111-1", saldo = 1000))
        val viewModel = MainViewModel(repo)
        assertEquals("11111111-1", viewModel.config.value.rut)

        repo.config = StandaloneConfig(rut = "22222222-2", saldo = 6000)
        viewModel.refreshConfig()

        assertEquals("22222222-2", viewModel.config.value.rut)
        assertEquals(6000, viewModel.config.value.saldo)
    }

    @Test
    fun testRefreshDaleQrSuccessUpdatesConfigAndInvokesCallback() = runTest {
        val initialConfig = StandaloneConfig(daleQrAcumulado = 1000, daleQrMeta = 42000)
        val updatedConfig = StandaloneConfig(daleQrAcumulado = 15000, daleQrMeta = 42000)
        val repo = FakeBipRepository(
            config = initialConfig,
            refreshDaleQrResult = Result.success(updatedConfig)
        )
        val viewModel = MainViewModel(repo, testDispatcher)

        var callbackResult: String? = null
        viewModel.refreshDaleQr { resultStr ->
            callbackResult = resultStr
        }

        testScheduler.advanceUntilIdle()

        assertEquals("Dale QR: $15000 / $42000", callbackResult)
        assertEquals(15000, viewModel.config.value.daleQrAcumulado)
    }

    @Test
    fun testRefreshDaleQrFailureInvokesCallbackWithError() = runTest {
        val repo = FakeBipRepository(
            refreshDaleQrResult = Result.failure(RuntimeException("Sin conexión"))
        )
        val viewModel = MainViewModel(repo, testDispatcher)

        var callbackResult: String? = null
        viewModel.refreshDaleQr { resultStr ->
            callbackResult = resultStr
        }

        testScheduler.advanceUntilIdle()

        assertEquals("Error Dale QR: Sin conexión", callbackResult)
    }

    @Test
    fun testImportConfigJsonSuccessRefreshesConfig() = runTest {
        val initialConfig = StandaloneConfig(rut = "11111111-1", saldo = 1000)
        val repo = FakeBipRepository(config = initialConfig, importConfigResult = true)
        val viewModel = MainViewModel(repo)

        repo.config = StandaloneConfig(rut = "33333333-3", saldo = 8500)
        val result = viewModel.importConfigJson("{\"rut\": \"33333333-3\"}")

        assertTrue(result)
        assertEquals("33333333-3", viewModel.config.value.rut)
        assertEquals(8500, viewModel.config.value.saldo)
    }

    @Test
    fun testImportConfigJsonFailureDoesNotUpdateConfig() = runTest {
        val initialConfig = StandaloneConfig(rut = "11111111-1", saldo = 1000)
        val repo = FakeBipRepository(config = initialConfig, importConfigResult = false)
        val viewModel = MainViewModel(repo)

        repo.config = StandaloneConfig(rut = "99999999-9", saldo = 9999)
        val result = viewModel.importConfigJson("invalid json")

        assertFalse(result)
        assertEquals("11111111-1", viewModel.config.value.rut)
        assertEquals(1000, viewModel.config.value.saldo)
    }

    @Test
    fun testProvideFactoryCreatesInstance() {
        val repo = FakeBipRepository()
        val factory = MainViewModel.provideFactory(repo, testDispatcher)
        val vm = factory.create(MainViewModel::class.java)

        assertTrue(vm.uiState.value is MainUiState.Idle)
    }
}
