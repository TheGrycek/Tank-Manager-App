package com.example.tank_manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HistoryTest {

    private static final long T0 = 1_800_000_000L;
    private static final long STEP = 600;

    @Test
    public void historyRequest_isLittleEndian() {
        assertArrayEquals(new byte[]{5, 0, 0, 0, 0x00, (byte) 0xD2, 0x49, 0x6B},
                Protocol.historyRequest(5, T0));
    }

    @Test
    public void historyPacket_decodesSamplesAndUnknownTimes() {
        ByteBuffer b = ByteBuffer.allocate(5 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(42).put((byte) 2);
        b.putInt((int) T0).put((byte) 30).put((byte) 80).putShort((short) 425);
        b.putInt(0x80000000 | 120).put((byte) 31).put((byte) 79).putShort(Short.MIN_VALUE);
        Protocol.HistoryPacket p = Protocol.HistoryPacket.decode(b.array());
        assertEquals(42, p.firstSeq);
        assertEquals(44, p.nextSeq());
        assertEquals(T0, p.samples.get(0).time);
        assertEquals(80, p.samples.get(0).clean);
        assertEquals(4.25f, p.samples.get(0).temperature, 0.001f);
        assertEquals(-1, p.samples.get(1).time);
        assertNull(p.samples.get(1).temperature);
    }

    @Test
    public void historyPacket_endAndBroken() {
        Protocol.HistoryPacket end = Protocol.HistoryPacket.decode(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0});
        assertTrue(end.isEnd());
        assertEquals(0xFFFFFFFFL, end.nextSeq());
        assertNull(Protocol.HistoryPacket.decode(new byte[]{1, 0, 0, 0, 2, 0}));
        assertNull(Protocol.HistoryPacket.decode(new byte[]{1, 0}));
    }

    /** Clean level sequence, one sample every 10 min. */
    private static List<HistorySample> clean(int... levels) {
        List<HistorySample> out = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            out.add(new HistorySample(T0 + i * STEP, 0, levels[i], null));
        }
        return out;
    }

    @Test
    public void cleanUse_ignoresRefillsAndNoise() {
        // 2 days: falls 1 % per hour with noise, refilled from 52 to 100 in the middle
        int n = 2 * 144 + 1;
        int[] levels = new int[n];
        double level = 100;
        for (int i = 0; i < n; i++) {
            if (i == 144) level = 100;          // refill
            levels[i] = (int) Math.round(level + (i % 3 == 0 ? 1 : i % 3 == 1 ? -1 : 0));
            level -= 1.0 / 6;
        }
        Float use = HistoryStats.cleanUsePerDay(clean(levels));
        assertEquals(24f, use, 1f);
        assertEquals(Arrays.asList(T0 + 143 * STEP), HistoryStats.cleanRefills(clean(levels)));
    }

    @Test
    public void rates_needTwelveHours() {
        assertNull(HistoryStats.cleanUsePerDay(clean(90, 89, 88)));
    }

    @Test
    public void jumpOverSeveralSamplesIsOneEvent() {
        List<HistorySample> gray = new ArrayList<>();
        int[] levels = {80, 81, 60, 40, 20, 20, 21};
        for (int i = 0; i < levels.length; i++) gray.add(new HistorySample(T0 + i * STEP, levels[i], 0, null));
        assertEquals(Arrays.asList(T0 + STEP), HistoryStats.grayEmptyings(gray));
    }

    @Test
    public void pauseIsNotAnEvent() {
        List<HistorySample> s = new ArrayList<>();
        s.add(new HistorySample(T0, 0, 20, null));
        s.add(new HistorySample(T0 + 5 * 3600, 0, 90, null)); // controller off for 5 h
        assertTrue(HistoryStats.cleanRefills(s).isEmpty());
    }

    @Test
    public void historyPacket_decodesDrivingAndTiltFlags() {
        ByteBuffer b = ByteBuffer.allocate(5 + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).put((byte) 1);
        b.putInt((int) T0).put((byte) (0x80 | 40)).put((byte) (0x80 | 70)).putShort((short) 0);
        HistorySample s = Protocol.HistoryPacket.decode(b.array()).samples.get(0);
        assertEquals(40, s.gray);
        assertEquals(70, s.clean);
        assertTrue(s.moving);
        assertTrue(s.tilted);
        assertFalse(s.steady());
    }

    /** Clean levels with a drive in the middle: sloshing readings while moving. */
    private static List<HistorySample> withDrive(int[] levels, int driveFrom, int driveTo) {
        List<HistorySample> out = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            boolean moving = i >= driveFrom && i < driveTo;
            out.add(new HistorySample(T0 + i * STEP, 0, levels[i], null, moving, false));
        }
        return out;
    }

    @Test
    public void drivingIsNoRefillAndNoUse() {
        // 14 h steady at 1 % per hour down, a 2 h drive with readings jumping 40-90, then steady again
        int n = 14 * 6 + 12 + 14 * 6;
        int[] levels = new int[n];
        for (int i = 0; i < n; i++) {
            levels[i] = 90 - i / 6;
        }
        for (int i = 84; i < 96; i++) {
            levels[i] = i % 2 == 0 ? 40 : 90;
        }
        List<HistorySample> s = withDrive(levels, 84, 96);
        assertTrue(HistoryStats.cleanRefills(s).isEmpty());
        // Integer levels step every 6 samples: about 22.5; with the drive counted it would be far off
        assertEquals(24f, HistoryStats.cleanUsePerDay(s), 2f);
    }

    @Test
    public void refillAcrossADriveIsFound() {
        // Steady at 30 %, a drive, then steady at 95 % (filled up at the end of the drive)
        int[] levels = new int[30];
        for (int i = 0; i < 30; i++) {
            levels[i] = i < 10 ? 30 : i < 20 ? 60 : 95;
        }
        assertEquals(Arrays.asList(T0 + 9 * STEP), HistoryStats.cleanRefills(withDrive(levels, 10, 20)));
    }

    @Test
    public void daysUntil() {
        assertEquals(2.5f, HistoryStats.daysUntil(50, 20f), 0.001f);
        assertEquals(0f, HistoryStats.daysUntil(-5, 20f), 0f);
        assertNull(HistoryStats.daysUntil(50, 0.1f));
        assertNull(HistoryStats.daysUntil(50, null));
    }
}
