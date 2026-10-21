package com.nowis.bipwear.data

import android.content.Context
import com.nowis.bipwear.BipC2dEngine
import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.RedApiClient
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.TicketManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BipRepository {
    fun getConfig(): StandaloneConfig
    fun isTramaValid(): Boolean
    fun getCachedMovements(): List<BipMovement>
    suspend fun fetchMovements(): Result<List<BipMovement>>
    suspend fun refreshDaleQr(): Result<StandaloneConfig>
    fun importConfigJson(jsonStr: String): Boolean
}

class DefaultBipRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BipRepository {

    override fun getConfig(): StandaloneConfig {
        return BipC2dEngine.loadConfig(context)
    }

    override fun isTramaValid(): Boolean {
        return BipC2dEngine.isTramaValid(context)
    }

    override fun getCachedMovements(): List<BipMovement> {
        return RedApiClient.loadCachedMovements(context)
    }

    override suspend fun fetchMovements(): Result<List<BipMovement>> = withContext(ioDispatcher) {
        try {
            val fresh = RedApiClient.fetchTransactions(context)
            Result.success(fresh)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshDaleQr(): Result<StandaloneConfig> = withContext(ioDispatcher) {
        try {
            val updated = RedApiClient.fetchDaleQrBenefitInfo(context)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun importConfigJson(jsonStr: String): Boolean {
        return TicketManager.importStandaloneConfigJson(context, jsonStr)
    }
}
