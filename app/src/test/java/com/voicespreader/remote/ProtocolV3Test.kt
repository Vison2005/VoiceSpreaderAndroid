package com.voicespreader.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolV3Test {
    @Test
    fun pointerPayloadUsesNetworkByteOrderAndFixedLength() {
        val payload = ProtocolV3.encodePointer(
            ProtocolV3.PointerPayload(
                sequence = 0x01020304,
                timestampMicros = 0x0102030405060708L,
                action = ProtocolV3.PointerAction.MOVE,
                pointerId = 9,
                deltaX = 1.25f,
                deltaY = -2.5f,
            ),
        )

        assertEquals(25, payload.size)
        val decoded = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x01020304, decoded.int)
        assertEquals(0x0102030405060708L, decoded.long)
        assertEquals(ProtocolV3.PointerAction.MOVE.value, decoded.get().toInt())
        assertEquals(9, decoded.int)
        assertEquals(1250, decoded.int)
        assertEquals(-2500, decoded.int)
    }

    @Test
    fun fileChunkLimitLeavesRoomForTypeByte() {
        assertTrue(ProtocolV3.FILE_CHUNK_BYTES + 1 <= ProtocolV3.MAX_FILE_FRAME_BYTES)
        assertEquals(256 * 1024, ProtocolV3.FILE_CHUNK_BYTES)
    }
}
