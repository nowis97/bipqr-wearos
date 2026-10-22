package com.nowis.bipwear

import com.nowis.bipwear.complication.BipComplicationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityIntentTest {

    @Test
    fun testExtraAutoGenerateConstant() {
        assertEquals("extra_auto_generate", MainActivity.EXTRA_AUTO_GENERATE)
    }

    @Test
    fun testExtraMatchesComplicationConstant() {
        assertEquals(MainActivity.EXTRA_AUTO_GENERATE, BipComplicationHelper.EXTRA_AUTO_GENERATE)
    }
}
