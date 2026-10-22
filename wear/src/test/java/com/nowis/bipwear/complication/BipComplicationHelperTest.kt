package com.nowis.bipwear.complication

import com.nowis.bipwear.StandaloneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BipComplicationHelperTest {

    @Test
    fun testComplicationTextValues() {
        val config = StandaloneConfig(saldo = 3400, daleQrAcumulado = 12000)
        assertEquals("$3.400", BipComplicationHelper.getBalanceText(config))
        assertEquals("bip!", BipComplicationHelper.TITLE_TEXT)
        assertEquals(12000f, BipComplicationHelper.getDaleQrValue(config), 0.001f)
        assertEquals(42000f, BipComplicationHelper.DALE_QR_MAX_VALUE, 0.001f)
    }

    @Test
    fun testEmptyConfigValues() {
        val emptyConfig = StandaloneConfig()
        assertEquals("$0", BipComplicationHelper.getBalanceText(emptyConfig))
        assertEquals(0f, BipComplicationHelper.getDaleQrValue(emptyConfig), 0.001f)
    }

    @Test
    fun testNegativeDaleQrValueCoercedToZero() {
        val negativeConfig = StandaloneConfig(saldo = -100, daleQrAcumulado = -500)
        assertEquals("$0", BipComplicationHelper.getBalanceText(negativeConfig))
        assertEquals(0f, BipComplicationHelper.getDaleQrValue(negativeConfig), 0.001f)
    }

    @Test
    fun testExtraAutoGenerateConstant() {
        assertEquals("extra_auto_generate", BipComplicationHelper.EXTRA_AUTO_GENERATE)
    }
}
