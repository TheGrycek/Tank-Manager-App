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
    static final UUID HEATER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    static final UUID SETTINGS_UUID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    // Standard BLE descriptor for notifications
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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

    /** Pitch, roll and yaw in degrees, or null when the payload is short. */
    static float[] orientation(byte[] value) {
        if (value == null || value.length < 12) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        return new float[]{buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
    }

    /** The four user settings; ranges are the same as in the controller firmware. */
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

        Settings(boolean buzzerOn, int grayThreshold, boolean autoHeating, int heatingThreshold) {
            this.buzzerOn = buzzerOn;
            this.grayThreshold = clamp(grayThreshold, GRAY_THRESHOLD_MIN, GRAY_THRESHOLD_MAX);
            this.autoHeating = autoHeating;
            this.heatingThreshold = clamp(heatingThreshold, HEATING_THRESHOLD_MIN, HEATING_THRESHOLD_MAX);
        }

        static Settings decode(byte[] value) {
            if (value == null || value.length < 4) {
                return null;
            }
            return new Settings(value[0] != 0, value[1] & 0xFF, value[2] != 0, value[3] & 0xFF);
        }

        byte[] encode() {
            return new byte[]{
                    (byte) (buzzerOn ? 1 : 0),
                    (byte) grayThreshold,
                    (byte) (autoHeating ? 1 : 0),
                    (byte) heatingThreshold
            };
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
