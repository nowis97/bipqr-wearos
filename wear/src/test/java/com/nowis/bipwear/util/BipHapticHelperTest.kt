package com.nowis.bipwear.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BipHapticHelperTest {

    @Test
    fun testTicketReadyDuration() {
        assertEquals(45L, BipHapticHelper.TICKET_READY_DURATION_MS)
    }
}
