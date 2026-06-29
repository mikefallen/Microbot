package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration.CalibrationModel;

import java.util.Random;

/**
 * A deterministic, per-account "mouse personality". Every parameter is derived from a seed string
 * via an independent seeded {@link Random}, so the same seed always reproduces the exact same
 * profile while different seeds produce distinct, recognisable behaviour.
 * <p>
 * Each value is a stable hash of {@code (seed, paramKey)} mapped into a fixed range. When a
 * {@link CalibrationModel} is loaded (disk or bundled default) the draw is delegated to it;
 * with the bundled default (uniform distributions at the original ranges) the result is
 * byte-for-byte identical to the previous hardcoded formula.
 */
public final class MouseProfile {
    /** Base time component (ms) feeding the duration model (TBoz.TBf / TBb). Range [100, 200]. */
    public final double baseTimeMs;
    /** Log-distance time scale (ms) feeding the duration model (TBoz.TBy / TBj). Range [80, 140]. */
    public final double logScaleMs;
    /** Control-point sideways offset scale used by the spline builder (TBoz.TBI / TBN). Range [0.5, 2.0]. */
    public final double curveScale;
    /** Signed sideways curve preference; sign picks the bow direction (TBoz.TBt / TBZ). Range [-1, 1]. */
    public final double curveBias;
    /** Secondary directional bias (TBoz.TBM / TBm). Range [-1, 1]. */
    public final double dirBias;
    /** Minimum number of overshoot/correction legs (TBoz.TBU first). Range [1, 2]. */
    public final int overshootMin;
    /** Maximum number of overshoot/correction legs (TBoz.TBU last). Range [2, 4]. */
    public final int overshootMax;
    /**
     * Per-leg geometric-decay fraction for overshoot magnitudes (TBoz.TBh / TBE). Range [0.1, 0.4].
     * <p>
     * Kept for struct parity only: in the upstream implementation this feeds {@code TBlk.TBQ}'s
     * fractions list stored in {@code TB_v.TBJ}, which is a <em>dead field</em> — verified by
     * grepping every {@code invokevirtual} on the decompiled helper, only the count/miss/extension getters
     * are ever read, never the list. Correction legs split time equally (TBdu.TBU), not by this. Not
     * wired into the active path here.
     */
    public final double overshootDecayFrac;
    /** Base tangential speed for the two-thirds power law, px/ms-ish (TBoz.TBi / TBJ). Range [50, 200]. */
    public final double powerLawBaseSpeed;
    /** Velocity-envelope peak position along the path (TBoz.TBs / TBO). Range [0.3, 0.5]. */
    public final double velocityPeak;
    /** Overshoot miss-vector + AR(1) wander amplitude (TBoz.TBr / TBP). Range [0.5, 3.0]. */
    public final double overshootOffsetAmp;
    /** Final-approach speed multiplier (TBoz.TBu / TBv). Range [0.3, 0.8]. */
    public final double approachMul;
    /** Probability-like overshoot tendency (TBoz.TBd / TBt). Range [0, 0.3]. */
    public final double overshootTendency;
    /** Along-path overshoot extension fraction (TBoz.TBk / TBM). Range [0.05, 0.2]. */
    public final double extensionFrac;
    /** Steady per-account multiplier delta on the FOCUS context field. Range [-0.05, 0.05]. */
    public final double focusMul;
    /** Steady per-account multiplier delta on the ACCURACY context field. Range [-0.05, 0.05]. */
    public final double accuracyMul;
    /** Steady per-account multiplier delta on the URGENCY context field. Range [-0.1, 0.5]. */
    public final double urgencyMul;
    /** Steady per-account multiplier delta on the FATIGUE context field. Range [-0.02, 0.02]. */
    public final double fatigueMul;

    // ---- New Tier-1 fields (calibration-driven) ----

    /** Hook curvature magnitude near end of path. Range [0, 0.8]. */
    public final double hookTendency;
    /** AR(1) motor noise bandwidth (decay factor). Range [0.2, 0.9]. */
    public final double motorNoiseBandwidth;
    /** Sub-movement settle leg length fraction. Range [0.1, 0.4]. */
    public final double subMovementScale;
    /** Log-normal reaction-time mean (ln-ms). Range [5.0, 6.2]. */
    public final double reactionTimeMu;
    /** Log-normal reaction-time sigma (ln-ms). Range [0.2, 0.7]. */
    public final double reactionTimeSigma;

    private final String seed;

    private MouseProfile(String seed) {
        this.seed = seed;
        this.baseTimeMs          = drawOr(seed, "baseTimeMs",          "baseTime",          100.0, 200.0);
        this.logScaleMs          = drawOr(seed, "logScaleMs",          "logScale",          80.0, 140.0);
        this.curveScale          = drawOr(seed, "curveScale",          "curveScale",        0.5, 2.0);
        this.curveBias           = drawOr(seed, "curveBias",           "curveBias",         -1.0, 1.0);
        this.dirBias             = drawOr(seed, "dirBias",             "dirBias",           -1.0, 1.0);
        this.overshootMin        = intRange(seed, "overshootMin",      1, 2);
        this.overshootMax        = intRange(seed, "overshootMax",      2, 4);
        this.overshootDecayFrac  = dbl(seed, "overshootDecayFrac",     0.1, 0.4);
        this.powerLawBaseSpeed   = drawOr(seed, "powerLawBaseSpeed",   "powerLawBaseSpeed", 50.0, 200.0);
        this.velocityPeak        = drawOr(seed, "velocityPeak",        "velocityPeak",      0.3, 0.5);
        this.overshootOffsetAmp  = drawOr(seed, "overshootOffsetAmp",  "overshootOffsetAmp",0.5, 3.0);
        this.approachMul         = dbl(seed, "approachMul",            0.3, 0.8);
        this.overshootTendency   = drawOr(seed, "overshootTendency",   "overshootTendency", 0.0, 0.3);
        this.extensionFrac       = drawOr(seed, "extensionFrac",       "extensionFrac",     0.05, 0.2);
        this.focusMul            = dbl(seed, "focusMul",               -0.05, 0.05);
        this.accuracyMul         = dbl(seed, "accuracyMul",            -0.05, 0.05);
        this.urgencyMul          = dbl(seed, "urgencyMul",             -0.1, 0.5);
        this.fatigueMul          = dbl(seed, "fatigueMul",             -0.02, 0.02);

        // New Tier-1 fields — only present in calibration model, not legacy fallback keys
        this.hookTendency        = drawOrDefault(seed, "hookTendency",       0.0, 0.8, 0.4);
        this.motorNoiseBandwidth = drawOrDefault(seed, "motorNoiseBandwidth",0.2, 0.9, 0.55);
        this.subMovementScale    = drawOrDefault(seed, "subMovementScale",   0.1, 0.4, 0.25);
        this.reactionTimeMu      = drawOrDefault(seed, "reactionTimeMu",     5.0, 6.2, 5.6);
        this.reactionTimeSigma   = drawOrDefault(seed, "reactionTimeSigma",  0.2, 0.7, 0.45);
    }

    /**
     * Builds a profile from a seed string (e.g. an account display name). Null/blank seeds fall
     * back to a fixed default so behaviour is still deterministic.
     */
    public static MouseProfile fromSeed(String seed) {
        return new MouseProfile(seed == null || seed.isEmpty() ? "default" : seed);
    }

    public String getSeed() {
        return seed;
    }

    /** Samples an overshoot count in {@code [overshootMin, overshootMax]} using the supplied rng. */
    public int sampleOvershoots(Random rng) {
        if (overshootMax <= overshootMin) {
            return overshootMin;
        }
        return overshootMin + rng.nextInt(overshootMax - overshootMin + 1);
    }

    // -------------------------------------------------------------------------
    // Draw helpers
    // -------------------------------------------------------------------------

    /**
     * Draws from CalibrationModel using {@code calibKey}; if the key is absent (NaN) falls back
     * to {@link #dbl} with the legacy {@code legacyKey}. This preserves determinism even if the
     * calibration file fails to load.
     */
    private static double drawOr(String seed, String calibKey, String legacyKey, double lo, double hi) {
        try {
            double v = CalibrationModel.getInstance().draw(seed, calibKey);
            if (!Double.isNaN(v)) {
                return v;
            }
        } catch (Exception ignored) {
        }
        return dbl(seed, legacyKey, lo, hi);
    }

    /**
     * Draws from CalibrationModel for a new field that has no legacy fallback key. Falls back to
     * the midpoint of {@code [lo, hi]} if the calibration key is absent, so these new fields are
     * always meaningful even without recorded data.
     */
    private static double drawOrDefault(String seed, String calibKey, double lo, double hi, double defaultVal) {
        try {
            double v = CalibrationModel.getInstance().draw(seed, calibKey);
            if (!Double.isNaN(v)) {
                return v;
            }
        } catch (Exception ignored) {
        }
        // Use the uniform range draw to be consistent even without calibration data
        return dbl(seed, calibKey, lo, hi);
    }

    static double dbl(String seed, String key, double lo, double hi) {
        return lo + (hi - lo) * new Random(mix(seed, key)).nextDouble();
    }

    private static int intRange(String seed, String key, int lo, int hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + new Random(mix(seed, key)).nextInt(hi - lo + 1);
    }

    /** Stable 64-bit mix of the seed string and a parameter key, independent per key. */
    static long mix(String seed, String key) {
        long h = 1125899906842597L; // prime
        String s = seed + ':' + key;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        // splitmix64 finalizer for good bit dispersion across nearby seeds/keys
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
