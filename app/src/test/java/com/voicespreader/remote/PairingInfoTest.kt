package com.voicespreader.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingInfoTest {
    @Test
    fun parsesDesktopQrPayload() {
        val value = PairingInfo.fromQrPayload(
            "VSP1:192.168.1.20:39742:00112233445566778899AABBCCDDEEFF:FFEEDDCCBBAA99887766554433221100",
        )
        assertEquals("192.168.1.20", value?.host)
        assertEquals(39742, value?.port)
    }

    @Test
    fun rejectsUnrelatedQrCode() {
        assertNull(PairingInfo.fromQrPayload("https://example.com"))
    }

    @Test
    fun rejectsMalformedCredentials() {
        assertNull(
            PairingInfo.fromQrPayload(
                "VSP1:192.168.1.20:39742:NOT_HEX:FFEEDDCCBBAA99887766554433221100",
            ),
        )
        assertNull(
            PairingInfo.fromDiscoveryJson(
                """{"protocol":1,"host":"","port":70000,"session":"00","secret":"11"}""",
            ),
        )
    }
}
