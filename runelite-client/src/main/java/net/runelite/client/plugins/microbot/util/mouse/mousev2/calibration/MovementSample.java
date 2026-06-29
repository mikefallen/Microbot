package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

/**
 * One raw cursor sample captured during a recording acquisition.
 */
public final class MovementSample {
    /** Canvas X coordinate (pixels). */
    public final double x;
    /** Canvas Y coordinate (pixels). */
    public final double y;
    /** Wall-clock timestamp in milliseconds ({@link System#currentTimeMillis()}). */
    public final long tMs;

    public MovementSample(double x, double y, long tMs) {
        this.x   = x;
        this.y   = y;
        this.tMs = tMs;
    }
}
