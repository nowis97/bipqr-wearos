package com.nowis.bipwear.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nowis.bipwear.BipC2dEngine
import com.nowis.bipwear.RedApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BipSeedRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = BipC2dEngine.loadConfig(applicationContext)
        if (!cfg.isProvisioned || cfg.sessionToken.isEmpty()) {
            return@withContext Result.success()
        }

        val expMs = BipC2dEngine.getTramaExpirationEpochMs(cfg.tramaSistemaCentral)
        val adjustedNowMs = System.currentTimeMillis() - cfg.deltaEpochMs
        val remainingMs = expMs - adjustedNowMs

        // Si la semilla expira en menos de 45 minutos (o ya expiró), renovar preventivamente
        if (remainingMs < 45 * 60 * 1000L) {
            try {
                Log.d(TAG, "Renovando semilla C2D preventivamente en segundo plano...")
                RedApiClient.requestC2dSeed(applicationContext)
                Log.i(TAG, "Semilla C2D renovada con éxito en segundo plano")
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase(java.util.Locale.ROOT)
                val isAuthError = msg.contains("session") || msg.contains("token") || msg.contains("401") || msg.contains("403")
                Log.w(TAG, "Fallo al renovar semilla C2D en segundo plano: ${e.message}")
                return@withContext if (isAuthError || runAttemptCount >= 3) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } else {
            Log.d(TAG, "Semilla C2D aún vigente (${remainingMs / 60000} min restantes)")
        }

        Result.success()
    }

    companion object {
        private const val TAG = "BipSeedRefreshWorker"
        private const val WORK_NAME = "bip_seed_periodic_refresh"

        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                // Intervalo periódico de 60 minutos con red disponible
                val request = PeriodicWorkRequestBuilder<BipSeedRefreshWorker>(60, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Log.d(TAG, "Tarea periódica de renovación de semilla programada cada 60 min")
            } catch (e: Exception) {
                Log.e(TAG, "Error programando BipSeedRefreshWorker", e)
            }
        }
    }
}
