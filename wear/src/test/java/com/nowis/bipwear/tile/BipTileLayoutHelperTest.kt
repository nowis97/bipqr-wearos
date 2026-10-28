package com.nowis.bipwear.tile

import com.nowis.bipwear.StandaloneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BipTileLayoutHelperTest {

    @Test
    fun testDaleQrProgressCalculation() {
        val configZero = StandaloneConfig(daleQrAcumulado = 0)
        assertEquals(0.0f, BipTileLayoutHelper.calculateDaleQrProgress(configZero), 0.001f)

        val configMid = StandaloneConfig(daleQrAcumulado = 21000)
        assertEquals(0.5f, BipTileLayoutHelper.calculateDaleQrProgress(configMid), 0.001f)

        val configMax = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals(1.0f, BipTileLayoutHelper.calculateDaleQrProgress(configMax), 0.001f)

        val configOver = StandaloneConfig(daleQrAcumulado = 50000)
        assertEquals(1.0f, BipTileLayoutHelper.calculateDaleQrProgress(configOver), 0.001f)

        // Dynamic custom meta (e.g. 30000)
        val configDynamic = StandaloneConfig(daleQrAcumulado = 15000, daleQrMeta = 30000)
        assertEquals(0.5f, BipTileLayoutHelper.calculateDaleQrProgress(configDynamic), 0.001f)
    }

    @Test
    fun testDaleQrColorHex() {
        // Under target: Cyan #00E5FF (0xFF00E5FF)
        val underTarget = StandaloneConfig(daleQrAcumulado = 15000)
        assertEquals(0xFF00E5FF.toInt(), BipTileLayoutHelper.getDaleQrProgressColor(underTarget))

        // Reached target: Neon Green #00E676 (0xFF00E676)
        val reachedTarget = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals(0xFF00E676.toInt(), BipTileLayoutHelper.getDaleQrProgressColor(reachedTarget))

        // Dynamic target reached
        val dynamicReached = StandaloneConfig(daleQrAcumulado = 30000, daleQrMeta = 30000)
        assertEquals(0xFF00E676.toInt(), BipTileLayoutHelper.getDaleQrProgressColor(dynamicReached))
    }

    @Test
    fun testDaleQrStatusText() {
        val underTarget = StandaloneConfig(daleQrAcumulado = 21000)
        assertEquals("Dale QR: 50%", BipTileLayoutHelper.getDaleQrStatusText(underTarget))

        val reachedTarget = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals("🎉 ¡Viajes Gratis!", BipTileLayoutHelper.getDaleQrStatusText(reachedTarget))

        val dynamicReached = StandaloneConfig(daleQrAcumulado = 35000, daleQrMeta = 35000)
        assertEquals("🎉 ¡Viajes Gratis!", BipTileLayoutHelper.getDaleQrStatusText(dynamicReached))
    }

    @Test
    fun testFormattedBalance() {
        val config = StandaloneConfig(saldo = 3400)
        assertEquals("$3.400", BipTileLayoutHelper.getFormattedBalance(config))

        val emptyConfig = StandaloneConfig(saldo = 0)
        assertEquals("$0", BipTileLayoutHelper.getFormattedBalance(emptyConfig))
    }

    @Test
    fun testIsLowBalanceLogic() {
        val lowBal = StandaloneConfig(saldo = 500)
        assertTrue(lowBal.isLowBalance)

        val normalBal = StandaloneConfig(saldo = 1200)
        assertFalse(normalBal.isLowBalance)

        val lowBalWithFreeTrips = StandaloneConfig(saldo = 500, daleQrAcumulado = 42000, daleQrMeta = 42000)
        assertFalse(lowBalWithFreeTrips.isLowBalance)
    }
}
