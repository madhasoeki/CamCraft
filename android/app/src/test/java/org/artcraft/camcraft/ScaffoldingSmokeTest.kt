package org.artcraft.camcraft

import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldingSmokeTest {
    @Test
    fun protocolConstantsMatchSpec() {
        assertEquals("CCFT", CcftProtocolConstants.MAGIC)
        assertEquals(1.toByte(), CcftProtocolConstants.VERSION)
        assertEquals(20, CcftProtocolConstants.HEADER_SIZE_BYTES)
        assertEquals(9876, CcftProtocolConstants.DEFAULT_PORT)
    }
}
