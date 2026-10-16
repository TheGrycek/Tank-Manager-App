package com.example.tank_manager;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistics from the level history (pure Java). A jump of at least STEP % between two samples is a
 * refill of the clean tank or an emptying of the gray tank; the rest is use (clean) or inflow (gray).
 * Samples taken while driving or standing tilted are left out: their levels are off.
 */
final class HistoryStats {

    /** Change between two consecutive samples that counts as filling or emptying, in %. */
    static final int STEP = 10;
    /** A longer pause between samples (controller off) is left out of the rates. */
    static final long GAP_S = 3600;
    /** Steady time needed before a rate is given. */
    static final long MIN_SPAN_S = 12 * 3600;
    /** Slower changes are treated as none (no forecast). */
    static final float MIN_RATE = 0.5f;

    private HistoryStats() { }

    /** Clean water used per day in %, without the refills; null without enough data. */
    static Float cleanUsePerDay(List<HistorySample> samples) {
        return rate(samples, true);
    }

    /** Gray water inflow per day in %, without the emptyings; null without enough data. */
    static Float grayFillPerDay(List<HistorySample> samples) {
        return rate(samples, false);
    }

    private static Float rate(List<HistorySample> samples, boolean clean) {
        double change = 0;
        long span = 0;
        for (int i = 1; i < samples.size(); i++) {
            HistorySample a = samples.get(i - 1);
            HistorySample b = samples.get(i);
            long dt = b.time - a.time;
            if (dt <= 0 || dt > GAP_S || !a.steady() || !b.steady()) {
                continue;
            }
            int d = clean ? a.clean - b.clean : b.gray - a.gray;
            if (d <= -STEP) {
                continue; // a refill of the clean tank or an emptying of the gray tank
            }
            // Noise cancels out in the sum of the signed changes
            change += d;
            span += dt;
        }
        if (span < MIN_SPAN_S) {
            return null;
        }
        return (float) Math.max(0, change * 86400 / span);
    }

    /** Times (Unix s) when the clean tank was refilled, oldest first. */
    static List<Long> cleanRefills(List<HistorySample> samples) {
        return jumps(samples, true);
    }

    /** Times (Unix s) when the gray tank was emptied, oldest first. */
    static List<Long> grayEmptyings(List<HistorySample> samples) {
        return jumps(samples, false);
    }

    /**
     * Compares each steady sample with the previous steady one, also across a drive (the water does
     * not change on the road), but not across a pause of the controller (unknown what happened).
     */
    private static List<Long> jumps(List<HistorySample> samples, boolean clean) {
        List<Long> out = new ArrayList<>();
        boolean inJump = false;
        HistorySample previous = null;   // last steady sample
        boolean pause = false;           // a pause since it
        for (int i = 0; i < samples.size(); i++) {
            HistorySample b = samples.get(i);
            if (i > 0 && b.time - samples.get(i - 1).time > GAP_S) {
                pause = true;
            }
            if (!b.steady()) {
                continue;
            }
            boolean jump = false;
            if (previous != null && !pause) {
                int d = clean ? b.clean - previous.clean : previous.gray - b.gray;
                jump = d >= STEP;
                // A refill or an emptying spread over several samples is one event, at its start
                if (jump && !inJump) {
                    out.add(previous.time);
                }
            }
            inJump = jump;
            previous = b;
            pause = false;
        }
        return out;
    }

    /**
     * Days until the remaining % are used up (clean: the level; gray: threshold minus level) at the
     * given rate in % per day; null when the rate is unknown or too slow, 0 when nothing remains.
     */
    static Float daysUntil(int remaining, Float ratePerDay) {
        if (remaining <= 0) {
            return 0f;
        }
        if (ratePerDay == null || ratePerDay < MIN_RATE) {
            return null;
        }
        return remaining / ratePerDay;
    }
}
