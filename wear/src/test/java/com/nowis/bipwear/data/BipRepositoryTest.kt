package com.nowis.bipwear.data

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.StandaloneConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBipRepository(
    var config: StandaloneConfig = StandaloneConfig(),
    var isTramaValid: Boolean = true,
    var movements: List<BipMovement> = emptyList(),
    var fetchMovementsResult: Result<List<BipMovement>>? = null,
    var refreshDaleQrResult: Result<StandaloneConfig>? = null,
    var importConfigResult: Boolean = true
) : BipRepository {
    override fun getConfig(): StandaloneConfig = config
    override fun isTramaValid(): Boolean = isTramaValid
    override fun getCachedMovements(): List<BipMovement> = movements
    override suspend fun fetchMovements(): Result<List<BipMovement>> =
        fetchMovementsResult ?: Result.success(movements)
    override suspend fun refreshDaleQr(): Result<StandaloneConfig> =
        refreshDaleQrResult ?: Result.success(config)
    override fun importConfigJson(jsonStr: String): Boolean = importConfigResult
}

class BipRepositoryTest {

    @Test
    fun testFakeRepositoryReturnsCorrectData() {
        val repo = FakeBipRepository(
            config = StandaloneConfig(rut = "12345678-9", saldo = 5000),
            movements = listOf(BipMovement(id = "1", amount = 830, transportType = "BUS"))
        )
        assertEquals("12345678-9", repo.getConfig().rut)
        assertEquals(5000, repo.getConfig().saldo)
        assertEquals(1, repo.getCachedMovements().size)
        assertTrue(repo.isTramaValid())
    }

    @Test
    fun testFakeRepositoryTramaInvalid() {
        val repo = FakeBipRepository(isTramaValid = false)
        assertFalse(repo.isTramaValid())
    }

    @Test
    fun testFakeRepositoryAsyncCalls() = runTest {
        val config = StandaloneConfig(rut = "11111111-1", saldo = 1000)
        val movements = listOf(BipMovement(id = "2", amount = 0, description = "Viaje gratis Dale QR"))
        val repo = FakeBipRepository(config = config, movements = movements)

        val fetchedMovements = repo.fetchMovements()
        assertTrue(fetchedMovements.isSuccess)
        assertEquals(1, fetchedMovements.getOrNull()?.size)
        assertEquals("Viaje gratis Dale QR", fetchedMovements.getOrNull()?.first()?.description)

        val refreshedConfig = repo.refreshDaleQr()
        assertTrue(refreshedConfig.isSuccess)
        assertEquals("11111111-1", refreshedConfig.getOrNull()?.rut)
        assertEquals(1000, refreshedConfig.getOrNull()?.saldo)

        assertTrue(repo.importConfigJson("{}"))
    }

    @Test
    fun testFakeRepositoryFailureResults() = runTest {
        val networkException = RuntimeException("Connection failed")
        val repo = FakeBipRepository(
            fetchMovementsResult = Result.failure(networkException),
            refreshDaleQrResult = Result.failure(networkException),
            importConfigResult = false
        )

        val movementsResult = repo.fetchMovements()
        assertTrue(movementsResult.isFailure)
        assertEquals("Connection failed", movementsResult.exceptionOrNull()?.message)

        val refreshResult = repo.refreshDaleQr()
        assertTrue(refreshResult.isFailure)
        assertEquals("Connection failed", refreshResult.exceptionOrNull()?.message)

        assertFalse(repo.importConfigJson("invalid json"))
    }
}
