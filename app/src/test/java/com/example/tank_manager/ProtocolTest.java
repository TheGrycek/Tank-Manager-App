package com.example.tank_manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ProtocolTest {

    @Test
    public void uint8_isUnsigned() {
        assertEquals(Integer.valueOf(200), Protocol.uint8(new byte[]{(byte) 200}));
        assertNull(Protocol.uint8(new byte[0]));
        assertNull(Protocol.uint8(null));
    }

    @Test
    public void temperature_isLittleEndianHundredths() {
        assertEquals(21.5f, Protocol.temperature(new byte[]{(byte) 0x66, 0x08}), 0.001f);
        assertEquals(-3.25f, Protocol.temperature(new byte[]{(byte) 0xBB, (byte) 0xFE}), 0.001f);
    }

    @Test
    public void temperature_sensorFaultAndShortPayload() {
        assertNull(Protocol.temperature(new byte[]{0x00, (byte) 0x80}));
        assertNull(Protocol.temperature(new byte[]{0x01}));
    }

    @Test
    public void orientation_readsThreeFloats() {
        byte[] data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(1.5f).putFloat(-2.5f).putFloat(180f).array();
        assertArrayEquals(new float[]{1.5f, -2.5f, 180f}, Protocol.orientation(data), 0f);
        assertNull(Protocol.orientation(new byte[11]));
    }

    @Test
    public void settings_roundTrip() {
        Protocol.Settings s = Protocol.Settings.decode(new byte[]{1, 85, 0, 3});
        assertTrue(s.buzzerOn);
        assertEquals(85, s.grayThreshold);
        assertFalse(s.autoHeating);
        assertEquals(3, s.heatingThreshold);
        assertArrayEquals(new byte[]{1, 85, 0, 3}, s.encode());
    }

    @Test
    public void settings_areClampedAndShortPayloadRejected() {
        Protocol.Settings s = Protocol.Settings.decode(new byte[]{0, (byte) 200, 1, 0});
        assertEquals(100, s.grayThreshold);
        assertEquals(1, s.heatingThreshold);
        assertNull(Protocol.Settings.decode(new byte[]{1, 90, 1}));
    }

    @Test
    public void compassPointIsNearestOfEight() {
        assertEquals("N", Protocol.compassPoint(0f));
        assertEquals("N", Protocol.compassPoint(359f));
        assertEquals("N", Protocol.compassPoint(22f));
        assertEquals("NE", Protocol.compassPoint(23f));
        assertEquals("E", Protocol.compassPoint(90f));
        assertEquals("SW", Protocol.compassPoint(225f));
        assertEquals("NW", Protocol.compassPoint(-45f));
    }
}
