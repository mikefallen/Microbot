package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A C2-continuous natural cubic spline through a list of points, built as a chain of
 * {@link CubicBezier} segments. Based on the upstream curve stack: the fit ({@link #fit}) is
 * {@code TBxb.TBT}'s tridiagonal/Thomas solve, and the arc-length lookup ({@link #sampleByArcFraction})
 * is {@code TBr0}'s evenly-spaced reparameterisation.
 * <p>
 * This is the curve family MouseV2 uses for the main move leg — <em>not</em> Catmull-Rom. The bow
 * comes from interpolating smoothly through perpendicular-offset control points (see
 * {@link SplineBuilder}).
 */
final class NaturalCubicSpline {
    /** Arc-length samples per Bézier segment for the lookup table (TBr0.TBa). */
    private static final int SAMPLES_PER_SEGMENT = 100;

    private final List<CubicBezier> segments;
    /** Cumulative arc length at each LUT sample. */
    private final double[] cumArc;
    /** Curve parameter (segmentIndex + localT, in [0, segments]) at each LUT sample. */
    private final double[] paramAt;
    private final double totalLength;

    private NaturalCubicSpline(List<CubicBezier> segments) {
        this.segments = segments;
        int count = segments.size() * SAMPLES_PER_SEGMENT + 1;
        this.cumArc = new double[count];
        this.paramAt = new double[count];
        int idx = 0;
        double acc = 0.0;
        cumArc[idx] = 0.0;
        paramAt[idx] = 0.0;
        idx++;
        for (int seg = 0; seg < segments.size(); seg++) {
            CubicBezier b = segments.get(seg);
            for (int j = 1; j <= SAMPLES_PER_SEGMENT; j++) {
                double t = (double) j / SAMPLES_PER_SEGMENT;
                double tPrev = (double) (j - 1) / SAMPLES_PER_SEGMENT;
                acc += b.arcLength(tPrev, t);
                paramAt[idx] = seg + t;
                cumArc[idx] = acc;
                idx++;
            }
        }
        this.totalLength = acc;
    }

    /**
     * Fits a natural cubic spline through {@code points} (size &ge; 2). Port of {@code TBxb.TBT}:
     * solves the tridiagonal system for the first control points then derives the seconds; a single
     * segment uses the classic 1/3, 2/3 control points.
     */
    static NaturalCubicSpline fit(List<Vec2> points) {
        int n = points.size() - 1; // number of segments
        if (n < 1) {
            throw new IllegalArgumentException("need at least 2 points, got " + points.size());
        }
        if (n == 1) {
            Vec2 p0 = points.get(0);
            Vec2 p1 = points.get(1);
            Vec2 c1 = p0.scale(2.0 / 3.0).add(p1.scale(1.0 / 3.0));
            Vec2 c2 = p0.scale(1.0 / 3.0).add(p1.scale(2.0 / 3.0));
            return new NaturalCubicSpline(Collections.singletonList(new CubicBezier(p0, c1, c2, p1)));
        }

        double[] a = new double[n]; // sub-diagonal
        double[] b = new double[n]; // diagonal
        double[] c = new double[n]; // super-diagonal
        double[] rx = new double[n];
        double[] ry = new double[n];

        b[0] = 2.0;
        c[0] = 1.0;
        rx[0] = points.get(0).x + 2.0 * points.get(1).x;
        ry[0] = points.get(0).y + 2.0 * points.get(1).y;
        for (int i = 1; i < n - 1; i++) {
            a[i] = 1.0;
            b[i] = 4.0;
            c[i] = 1.0;
            rx[i] = 4.0 * points.get(i).x + 2.0 * points.get(i + 1).x;
            ry[i] = 4.0 * points.get(i).y + 2.0 * points.get(i + 1).y;
        }
        a[n - 1] = 2.0;
        b[n - 1] = 7.0;
        c[n - 1] = 0.0;
        rx[n - 1] = 8.0 * points.get(n - 1).x + points.get(n).x;
        ry[n - 1] = 8.0 * points.get(n - 1).y + points.get(n).y;

        // Thomas forward elimination
        for (int i = 1; i < n; i++) {
            double m = a[i] / b[i - 1];
            b[i] -= m * c[i - 1];
            rx[i] -= m * rx[i - 1];
            ry[i] -= m * ry[i - 1];
        }

        // back substitution -> first control points
        double[] c1x = new double[n];
        double[] c1y = new double[n];
        c1x[n - 1] = rx[n - 1] / b[n - 1];
        c1y[n - 1] = ry[n - 1] / b[n - 1];
        for (int i = n - 2; i >= 0; i--) {
            c1x[i] = (rx[i] - c[i] * c1x[i + 1]) / b[i];
            c1y[i] = (ry[i] - c[i] * c1y[i + 1]) / b[i];
        }

        // derive second control points
        double[] c2x = new double[n];
        double[] c2y = new double[n];
        for (int i = 0; i < n - 1; i++) {
            c2x[i] = 2.0 * points.get(i + 1).x - c1x[i + 1];
            c2y[i] = 2.0 * points.get(i + 1).y - c1y[i + 1];
        }
        c2x[n - 1] = (c1x[n - 1] + points.get(n).x) / 2.0;
        c2y[n - 1] = (c1y[n - 1] + points.get(n).y) / 2.0;

        List<CubicBezier> segs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            segs.add(new CubicBezier(points.get(i),
                    new Vec2(c1x[i], c1y[i]),
                    new Vec2(c2x[i], c2y[i]),
                    points.get(i + 1)));
        }
        return new NaturalCubicSpline(segs);
    }

    double totalLength() {
        return totalLength;
    }

    /**
     * Point at normalised arc-length fraction {@code s} in [0,1] (TBr0.TBR): maps {@code s} to the
     * curve parameter via the LUT (linear interpolation between samples), then evaluates the segment.
     */
    Vec2 sampleByArcFraction(double s) {
        double target = clamp(s, 0.0, 1.0) * totalLength;
        double param = paramForArcLength(target);
        int seg = (int) param;
        if (seg < 0) {
            seg = 0;
        }
        if (seg > segments.size() - 1) {
            seg = segments.size() - 1;
        }
        double localT = param - seg;
        return segments.get(seg).point(localT);
    }

    /** Binary search the cumulative-arc table for {@code target}, linearly interpolating the param (TBr0.TBc). */
    private double paramForArcLength(double target) {
        if (target <= 0.0) {
            return 0.0;
        }
        if (target >= totalLength) {
            return segments.size();
        }
        int lo = 0;
        int hi = cumArc.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (cumArc[mid] < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double arcLo = cumArc[lo];
        double arcHi = cumArc[hi];
        double span = arcHi - arcLo;
        double f = span > 1e-12 ? (target - arcLo) / span : 0.0;
        return paramAt[lo] + f * (paramAt[hi] - paramAt[lo]);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
