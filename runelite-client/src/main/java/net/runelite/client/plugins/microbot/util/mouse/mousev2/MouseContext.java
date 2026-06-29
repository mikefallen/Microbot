package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Live, mutable context that shapes an individual mouse movement based on what the bot is currently
 * doing. A movement made while rushing a valuable drop ({@code urgency} high) differs from one made
 * while casually banking.
 * <p>
 * Each scalar has a base value, plus an optional map of named modifiers that callers can push/pop to
 * temporarily bias behaviour for a specific action. The effective value is the base scaled by the
 * product of its modifiers, clamped to a sane range by consumers.
 */
public final class MouseContext {
    public static final double DEFAULT_FOCUS = 0.55;
    public static final double DEFAULT_URGENCY = 0.70;
    public static final double DEFAULT_ACCURACY = 0.35;
    public static final double DEFAULT_FATIGUE = 0.0;
    /** Default target-box dimensions (px). Also serve as the Fitts target-size term in timing. */
    public static final double DEFAULT_BOX_WIDTH = 50.0;
    public static final double DEFAULT_BOX_HEIGHT = 50.0;

    private volatile double focus = DEFAULT_FOCUS;
    private volatile double urgency = DEFAULT_URGENCY;
    private volatile double accuracy = DEFAULT_ACCURACY;
    private volatile double fatigue = DEFAULT_FATIGUE;
    private volatile double boxWidth = DEFAULT_BOX_WIDTH;
    private volatile double boxHeight = DEFAULT_BOX_HEIGHT;
    private volatile int mouseSpeed = 100;

    private final ConcurrentHashMap<String, Double> focusMods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> urgencyMods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> accuracyMods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> fatigueMods = new ConcurrentHashMap<>();

    public double getFocus() {
        return apply(focus, focusMods);
    }

    public double getUrgency() {
        return apply(urgency, urgencyMods);
    }

    public double getAccuracy() {
        return apply(accuracy, accuracyMods);
    }

    public double getFatigue() {
        return apply(fatigue, fatigueMods);
    }

    public double getBoxWidth() {
        return boxWidth;
    }

    public double getBoxHeight() {
        return boxHeight;
    }

    public int getMouseSpeed() {
        return mouseSpeed;
    }

    public void setFocus(double v) {
        this.focus = v;
    }

    public void setUrgency(double v) {
        this.urgency = v;
    }

    public void setAccuracy(double v) {
        this.accuracy = v;
    }

    public void setFatigue(double v) {
        this.fatigue = v;
    }

    public void setBoxWidth(double v) {
        this.boxWidth = v;
    }

    public void setBoxHeight(double v) {
        this.boxHeight = v;
    }

    /**
     * Per-move focus jitter: each individual movement varies in deliberateness. Returns the base
     * focus shifted by a gaussian (std 0.2) and clamped to [0.1, 1.0].
     */
    public static double jitteredFocus(double baseFocus, double gaussianSample) {
        double f = baseFocus + gaussianSample * 0.2;
        return f < 0.1 ? 0.1 : (f > 1.0 ? 1.0 : f);
    }

    /**
     * Applies a fresh per-move focus jitter to this context. Call once per move so timing and curve
     * vary move-to-move (matches MouseV2 setting FOCUS per move from a plan factor).
     */
    public double applyPerMoveJitter(java.util.Random rng) {
        double f = jitteredFocus(getFocus(), rng.nextGaussian());
        setFocus(f);
        return f;
    }

    /** Modifier key for the script-set mouse-speed urgency contribution. */
    public static final String SPEED_URGENCY_KEY = "api-mouse-speed";

    /**
     * The URGENCY multiplier delta a given mouse speed contributes, matching the upstream
     * {@code Mouse.setSpeed} curve: speed 100 is neutral (0), &ge;200 gives the max +0.5 boost,
     * and 0 gives the max -0.25 calming.
     */
    public static double speedToUrgencyDelta(int speed) {
        if (speed >= 100) {
            return clamp01((speed - 100) / 100.0) * 0.5;       // 0 .. +0.5
        }
        return -0.25 * (1.0 - clamp01(speed / 100.0));         // -0.25 .. 0
    }

    /** Applies the mouse-speed urgency modifier (source {@code "api-mouse-speed"}); removes it at neutral. */
    public void applyMouseSpeed(int speed) {
        int clampedSpeed = Math.max(0, Math.min(400, speed));
        mouseSpeed = clampedSpeed;
        double delta = speedToUrgencyDelta(clampedSpeed);
        if (delta == 0.0) {
            urgencyMods.remove(SPEED_URGENCY_KEY);
        } else {
            urgencyMods.put(SPEED_URGENCY_KEY, delta);
        }
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /**
     * Pushes the seeded profile's steady per-field multipliers (source {@code "profile"}). Mirrors
     * the MouseV2 context, where the account profile contributes an additive multiplier to each
     * behavioural field (e.g. a more urgent or more careful account).
     */
    public void applyProfileMultipliers(MouseProfile profile) {
        focusMods.put("profile", profile.focusMul);
        accuracyMods.put("profile", profile.accuracyMul);
        urgencyMods.put("profile", profile.urgencyMul);
        fatigueMods.put("profile", profile.fatigueMul);
    }

    /** Push a named additive modifier delta (e.g. +0.25 URGENCY for a high-value action). */
    public void pushModifier(String channel, String key, double delta) {
        modMap(channel).put(key, delta);
    }

    /** Remove a previously pushed modifier. */
    public void popModifier(String channel, String key) {
        modMap(channel).remove(key);
    }

    private ConcurrentHashMap<String, Double> modMap(String channel) {
        switch (channel) {
            case "focus":
                return focusMods;
            case "urgency":
                return urgencyMods;
            case "accuracy":
                return accuracyMods;
            case "fatigue":
                return fatigueMods;
            default:
                throw new IllegalArgumentException("Unknown context channel: " + channel);
        }
    }

    /**
     * Effective value = base * (1 + sum of multiplier deltas). Modifiers are additive deltas (e.g.
     * +0.030, -0.007) summed then applied as a single factor, matching MouseV2's context model.
     * Values are not clamped (urgency can exceed 1.0).
     */
    private static double apply(double base, ConcurrentHashMap<String, Double> mods) {
        double sum = 0.0;
        for (double m : mods.values()) {
            sum += m;
        }
        return base * (1.0 + sum);
    }

    /**
     * Snapshots the antiban subsystem into a fresh context: urgency rises with activity intensity,
     * fatigue is engaged when fatigue simulation is on. Safe to call outside a running client
     * (falls back to defaults).
     */
    public static MouseContext fromAntiban() {
        MouseContext ctx = new MouseContext();
        try {
            ActivityIntensity intensity = Rs2Antiban.getActivityIntensity();
            if (intensity != null) {
                // VERY_LOW(0) .. EXTREME(4) -> urgency 0.2 .. 0.8
                int ord = intensity.ordinal();
                int max = ActivityIntensity.values().length - 1;
                double frac = max <= 0 ? 0.0 : ord / (double) max;
                ctx.urgency = max <= 0 ? DEFAULT_URGENCY : 0.2 + 0.6 * frac;
                // higher intensity slightly reduces deliberate focus
                ctx.focus = 0.65 - 0.15 * frac;
            }
        } catch (Exception ignored) {
            // not in a client context (e.g. unit tests) - keep defaults
        }
        try {
            if (Rs2AntibanSettings.simulateFatigue) {
                ctx.fatigue = 0.3;
            }
        } catch (Exception ignored) {
            // keep default
        }
        return ctx;
    }
}
