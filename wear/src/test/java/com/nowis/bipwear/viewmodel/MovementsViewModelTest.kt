package com.nowis.bipwear.viewmodel

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.data.FakeBipRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovementsViewModelTest {

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
    fun testInitialStateLoadsCachedMovements() = runTest {
        val cached = listOf(
            BipMovement(id = "1", description = "Metro Baquedano", amount = 830, transportType = "METRO")
        )
        val repo = FakeBipRepository(movements = cached)
        val viewModel = MovementsViewModel(repo)

        assertEquals(1, viewModel.uiState.value.movements.size)
        assertEquals("Metro Baquedano", viewModel.uiState.value.movements[0].description)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun testInitialStateWithoutCachedMovementsSetsLoading() = runTest {
        val repo = FakeBipRepository(movements = emptyList())
        val viewModel = MovementsViewModel(repo, testDispatcher)

        // Empty cache triggers initial loading state in refresh(forceNetwork = false)
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(0, viewModel.uiState.value.movements.size)

        // After coroutine completes
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testRefreshUpdatesMovementsOnSuccess() = runTest {
        val initialCached = listOf(
            BipMovement(id = "1", description = "Metro Baquedano", amount = 830, transportType = "METRO")
        )
        val freshMovements = listOf(
            BipMovement(id = "2", description = "Bus 506", amount = 730, transportType = "BUS"),
            BipMovement(id = "1", description = "Metro Baquedano", amount = 830, transportType = "METRO")
        )
        val repo = FakeBipRepository(
            movements = initialCached,
            fetchMovementsResult = Result.success(freshMovements)
        )
        val viewModel = MovementsViewModel(repo, testDispatcher)
        testScheduler.advanceUntilIdle()

        viewModel.refresh(forceNetwork = true)
        assertTrue(viewModel.uiState.value.isLoading)

        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(2, viewModel.uiState.value.movements.size)
        assertEquals("Bus 506", viewModel.uiState.value.movements[0].description)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testRefreshFailurePreservesExistingMovementsAndSetsError() = runTest {
        val initialCached = listOf(
            BipMovement(id = "1", description = "Metro Baquedano", amount = 830, transportType = "METRO")
        )
        val repo = FakeBipRepository(
            movements = initialCached,
            fetchMovementsResult = Result.failure(RuntimeException("Error de conexión"))
        )
        val viewModel = MovementsViewModel(repo, testDispatcher)
        testScheduler.advanceUntilIdle()

        viewModel.refresh(forceNetwork = true)
        assertTrue(viewModel.uiState.value.isLoading)

        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.movements.size)
        assertEquals("Metro Baquedano", viewModel.uiState.value.movements[0].description)
        assertEquals("Error de conexión", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testProvideFactoryCreatesInstance() {
        val repo = FakeBipRepository()
        val factory = MovementsViewModel.provideFactory(repo)
        val viewModel = factory.create(MovementsViewModel::class.java)

        assertNotNull(viewModel)
        assertEquals(0, viewModel.uiState.value.movements.size)
    }
}
