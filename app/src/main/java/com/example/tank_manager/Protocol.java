package com.example.tank_manager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** BLE protocol of the tank controller: UUIDs and payload formats (see esp32/main.py). */
final class Protocol {

    static final UUID SERVICE_UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214");
    static final UUID CLEAN_LEVEL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID GRAY_LEVEL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID GRAY_TEMP_UUID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID MPU_UUID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID VALVE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    // Actual valve state including the movement, see VALVE_* below
    static final UUID VALVE_STATE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    static final UUID HEATER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    static final UUID SETTINGS_UUID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    // Standard BLE descriptor for notifications
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int VALVE_CLOSED = 0;
    static final int VALVE_OPEN = 1;
    static final int VALVE_CLOSING = 2;
    static final int VALVE_OPENING = 3;

    private Protocol() { }

    /** First byte as an unsigned number, or null when the payload is empty. */
    static Integer uint8(byte[] value) {
        return value == null || value.length < 1 ? null : value[0] & 0xFF;
    }

    /** Temperature in °C, or null when the payload is short or the sensor is reported broken. */
    static Float temperature(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        short raw = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort();
        // The controller sends INT16_MIN when the temperature sensor is broken
        return raw == Short.MIN_VALUE ? null : raw / 100.0f;
    }

    /**
     * Pitch (front up > 0), roll (right side down > 0) and heading of the front (0-360, clockwise
     * from magnetic north) in degrees, or null when the payload is short.
     */
    static float[] orientation(byte[] value) {
        if (value == null || value.length < 12) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        return new float[]{buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
    }

    private static final String[] COMPASS_POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Nearest of the eight compass points for a heading in degrees clockwise from north. */
    static String compassPoint(float heading) {
        int index = Math.round(((heading % 360f) + 360f) % 360f / 45f) % 8;
        return COMPASS_POINTS[index];
    }

    /**
     * The user settings; ranges are the same as in the controller firmware. The fifth byte, the clean
     * tank fill mode, is not stored by the controller; an older controller sends only four bytes.
     */
    static final class Settings {
        static final int GRAY_THRESHOLD_MIN = 50;
        static final int GRAY_THRESHOLD_MAX = 100;
        static final int GRAY_THRESHOLD_STEP = 5;
        static final int HEATING_THRESHOLD_MIN = 1;
        static final int HEATING_THRESHOLD_MAX = 5;

        final boolean buzzerOn;
        final int grayThreshold;
        final boolean autoHeating;
        final int heatingThreshold;
        final boolean fillMode;

        Settings(boolean buzzerOn, int grayThreshold, boolean autoHeating, int heatingThreshold,
                 boolean fillMode) {
            this.buzzerOn = buzzerOn;
            this.grayThreshold = clamp(grayThreshold, GRAY_THRESHOLD_MIN, GRAY_THRESHOLD_MAX);
            this.autoHeating = autoHeating;
            this.heatingThreshold = clamp(heatingThreshold, HEATING_THRESHOLD_MIN, HEATING_THRESHOLD_MAX);
            this.fillMode = fillMode;
        }

        static Settings decode(byte[] value) {
            if (value == null || value.length < 4) {
                return null;
            }
            return new Settings(value[0] != 0, value[1] & 0xFF, value[2] != 0, value[3] & 0xFF,
                    value.length > 4 && value[4] != 0);
        }

        byte[] encode() {
            return new byte[]{
                    (byte) (buzzerOn ? 1 : 0),
                    (byte) grayThreshold,
                    (byte) (autoHeating ? 1 : 0),
                    (byte) heatingThreshold,
                    (byte) (fillMode ? 1 : 0)
            };
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
