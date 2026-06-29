package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

/**
 * A normalised motion fragment derived from one recorded acquisition.
 * <p>
 * <b>Noise fragment</b> — perpendicular residual offsets of the cruise stroke after subtracting
 * the straight start→end line, sampled at {@code N} normalised arc positions and scaled to unit
 * path length, so it can be replayed onto any move by {@code offset * dist}.
 * <p>
 * <b>Correction fragment</b> — settle-wobble offsets relative to the target, normalised by the
 * recorded click-box size.
 */
public final class Fragment {
    /** Normalised arc positions in [0,1] (same length as {@link #offsets}). */
    public final double[] arcFrac;
    /** Perpendicular offsets at each arc position, normalised to unit path length. */
    public final double[] offsets;
    /** Human-readable bucket key this fragment was classified into (e.g. {@code "short_slow"}). */
    public final String bucketKey;

    public Fragment(double[] arcFrac, double[] offsets, String bucketKey) {
        this.arcFrac   = arcFrac;
        this.offsets   = offsets;
        this.bucketKey = bucketKey;
    }

    /**
     * Resamples this fragment to {@code outLen} evenly-spaced arc positions using linear
     * interpolation. Returns a new {@code double[]} of length {@code outLen}.
     */
    public double[] resample(int outLen) {
        if (outLen <= 0) {
            return new double[0];
        }
        if (arcFrac.length == 0) {
            return new double[outLen];
        }
        double[] out = new double[outLen];
        for (int i = 0; i < outLen; i++) {
            double t = outLen == 1 ? 0.0 : i / (double) (outLen - 1);
            out[i] = interpolate(t);
        }
        return out;
    }

    private double interpolate(double t) {
        if (arcFrac.length == 1) {
            return offsets[0];
        }
        // clamp
        if (t <= arcFrac[0]) {
            return offsets[0];
        }
        if (t >= arcFrac[arcFrac.length - 1]) {
            return offsets[arcFrac.length - 1];
        }
        // binary search
        int lo = 0, hi = arcFrac.length - 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arcFrac[mid + 1] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        double span = arcFrac[lo + 1] - arcFrac[lo];
        if (span < 1e-12) {
            return offsets[lo];
        }
        double frac = (t - arcFrac[lo]) / span;
        return offsets[lo] + frac * (offsets[lo + 1] - offsets[lo]);
    }
}
