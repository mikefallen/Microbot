package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.util.Random;

/**
 * Speed tiers selected by {@code Mouse.setSpeed}, used only for naming the active tier
 * (debug overlay) and for shaping the click-settle micro-corrections. The speed value's effect on
 * movement <em>timing</em> is applied entirely through the URGENCY modifier in
 * {@link MouseContext#applyMouseSpeed} — this enum does not override the duration model or the path
 * density (those are driven by the seeded {@link MouseProfile}).
 */
enum MouseSpeedProfile {
    LOW_INTENSITY("LowIntensity"),
    FOCUSED("Focused"),
    HIGH_INTENSITY("HighIntensity"),
    HYPER_FAST("HyperFast");

    private final String displayName;

    MouseSpeedProfile(String displayName) {
        this.displayName = displayName;
    }

    static MouseSpeedProfile fromSpeed(int speed) {
        if (speed >= 300) {
            return HYPER_FAST;
        }
        if (speed >= 200) {
            return HIGH_INTENSITY;
        }
        if (speed >= 100) {
            return FOCUSED;
        }
        return LOW_INTENSITY;
    }

    int settleCorrections(Random rng) {
        switch (this) {
            case HYPER_FAST:
                return 0;
            case HIGH_INTENSITY:
                return rng.nextInt(2);
            case FOCUSED:
                return 1;
            case LOW_INTENSITY:
            default:
                return 1 + rng.nextInt(2);
        }
    }

    int settleDelayMs(Random rng) {
        switch (this) {
            case HYPER_FAST:
                return 0;
            case HIGH_INTENSITY:
                return 4 + rng.nextInt(7);
            case FOCUSED:
                return 8 + rng.nextInt(11);
            case LOW_INTENSITY:
            default:
                return 15 + rng.nextInt(21);
        }
    }

    String displayName() {
        return displayName;
    }
}
