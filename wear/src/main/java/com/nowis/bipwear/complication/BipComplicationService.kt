package com.nowis.bipwear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.nowis.bipwear.BipC2dEngine
import com.nowis.bipwear.StandaloneConfig

class BipComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val dummyConfig = StandaloneConfig(
            saldo = 3400,
            daleQrAcumulado = 21000
        )
        return BipComplicationHelper.buildComplicationData(this, type, dummyConfig)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val config = BipC2dEngine.loadConfig(this)
        return BipComplicationHelper.buildComplicationData(this, request.complicationType, config)
    }
}
