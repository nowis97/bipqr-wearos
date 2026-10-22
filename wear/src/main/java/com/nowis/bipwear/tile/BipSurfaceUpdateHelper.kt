package com.nowis.bipwear.tile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.nowis.bipwear.complication.BipComplicationService

object BipSurfaceUpdateHelper {
    private const val TAG = "BipSurfaceUpdate"

    fun requestSurfaceUpdates(context: Context) {
        try {
            // Request Tile update
            TileService.getUpdater(context).requestUpdate(BipTileService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo solicitar actualización de Tile: ${e.message}")
        }

        try {
            // Request Complication update
            val component = ComponentName(context, BipComplicationService::class.java)
            ComplicationDataSourceUpdateRequester.create(context, component).requestUpdateAll()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo solicitar actualización de Complication: ${e.message}")
        }
    }
}
