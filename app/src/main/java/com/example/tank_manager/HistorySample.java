package com.example.tank_manager;

/**
 * One history sample: averaged levels and temperature over 10 minutes, and whether the camper was
 * driving or standing tilted then (the levels read are not reliable, statistics leave them out).
 */
final class HistorySample {
    /** Unix seconds; -1 when the controller did not know the date. */
    final long time;
    final int gray;
    final int clean;
    /** °C, null when the sensor was broken. */
    final Float temperature;
    final boolean moving;
    final boolean tilted;

    HistorySample(long time, int gray, int clean, Float temperature, boolean moving, boolean tilted) {
        this.time = time;
        this.gray = gray;
        this.clean = clean;
        this.temperature = temperature;
        this.moving = moving;
        this.tilted = tilted;
    }

    HistorySample(long time, int gray, int clean, Float temperature) {
        this(time, gray, clean, temperature, false, false);
    }

    /** The levels can be trusted: the camper stood still and level. */
    boolean steady() {
        return !moving && !tilted;
    }
}
