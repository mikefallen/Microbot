package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates per-acquisition metrics and produces updated calibration parameter estimates via
 * a Welford / running-sum approach. Pure computation — no Swing / IO.
 */
public final class ProfileAggregator {

    // ---- per-acquisition accumulators ----
    private int count = 0;

    // Fitts regression: duration vs log2(2*dist/box + 1)
    private double sumX   = 0, sumY   = 0, sumXX = 0, sumXY = 0; // for OLS
    // speed
    private final List<Double> cruiseSpeeds = new ArrayList<>();
    // velocity peak
    private double sumPeakPos = 0;
    private int    countPeakPos = 0;
    // curve scale
    private double sumCurveScale = 0;
    // curveBias / dirBias
    private double sumCurveSide = 0;
    // overshoot
    private int overshootCount = 0;
    private double sumExtFrac  = 0;
    private double sumCorrStd  = 0;
    private int minCorr  = Integer.MAX_VALUE;
    private int maxCorr  = Integer.MIN_VALUE;
    // hook
    private double sumHook = 0;
    // motor noise bandwidth
    private double sumBandwidth = 0;
    // sub-movement scale
    private double sumSubMove = 0;
    // reaction time
    private final List<Double> reactionTimes = new ArrayList<>();
    // fragment pools (separate from CalibrationModel — held for Save step)
    private final List<FragmentClassifier.Result> fragments = new ArrayList<>();

    public ProfileAggregator() {}

    /**
     * Adds one acquisition's measurements to the accumulators.
     *
     * @param samples     full motion samples from first motion to click
     * @param targetCx    target centre X
     * @param targetCy    target centre Y
     * @param targetRadius target radius (px)
     * @param boxSize     target "width" for Fitts model (px); use 2*radius
     * @param targetSpawnMs wall-clock ms when the target was spawned (for reaction time)
     */
    public void addAcquisition(List<MovementSample> samples,
                                double targetCx, double targetCy, double targetRadius,
                                double boxSize, long targetSpawnMs) {
        if (samples == null || samples.size() < 4) {
            return;
        }

        MovementSample first = samples.get(0);
        MovementSample last  = samples.get(samples.size() - 1);

        double dist     = Math.hypot(last.x - targetCx - (first.x - targetCx),
                                     last.y - targetCy - (first.y - targetCy));
        // straight-line start→end distance
        double straightDist = Math.hypot(last.x - first.x, last.y - first.y);

        // ---- Fitts OLS ----
        long   durationMs = last.tMs - first.tMs;
        double bsafe      = Math.max(boxSize, 1.0);
        double fittsX     = Math.log(2.0 * straightDist / bsafe + 1.0) / Math.log(2.0);
        double fittsY     = durationMs;
        sumX  += fittsX;
        sumY  += fittsY;
        sumXX += fittsX * fittsX;
        sumXY += fittsX * fittsY;

        // ---- cruise speed ----
        double medSpeed = medianSpeed(samples);
        cruiseSpeeds.add(medSpeed);

        // ---- velocity peak position (normalised arc at max tangential speed) ----
        double peakArc = peakArcPosition(samples);
        sumPeakPos += peakArc;
        countPeakPos++;

        // ---- curve scale (peak perp deviation / dist) ----
        double perpMax = maxPerpDev(samples, targetCx, targetCy, straightDist);
        if (straightDist > 5) {
            sumCurveScale += perpMax / straightDist;
        }

        // ---- curveBias / dirBias (signed mean of perp deviation) ----
        double signedPerp = signedMeanPerpDev(samples, straightDist);
        sumCurveSide += Math.signum(signedPerp);

        // ---- overshoot ----
        boolean didOvershoot = crossedPastTarget(samples, targetCx, targetCy, targetRadius);
        if (didOvershoot) {
            overshootCount++;
        }

        // ---- reaction time ----
        long reactionMs = first.tMs - targetSpawnMs;
        if (reactionMs > 0 && reactionMs < 10000) {
            reactionTimes.add(Math.log((double) reactionMs));
        }

        // ---- fragment classification ----
        FragmentClassifier.Result fr = FragmentClassifier.classify(samples, targetCx, targetCy, targetRadius);
        if (fr != null) {
            fragments.add(fr);
        }

        count++;
    }

    /** Returns all classified fragments collected so far (for the fragment store). */
    public List<FragmentClassifier.Result> getFragments() {
        return fragments;
    }

    /** Returns the number of acquisitions accumulated. */
    public int getCount() {
        return count;
    }

    /**
     * Merges this session's estimates with an existing calibration JSON object, producing an
     * updated parameter map. Uses Welford-style weighted merge for prior {@code sampleCount}.
     *
     * @param existing   the existing calibration data (may be null or empty)
     * @return new parameter map for serialisation by {@link MouseProfileStore}
     */
    public CalibrationData.ParamMap fitInto(CalibrationData existing) {
        if (count == 0) {
            return existing != null ? existing.parameters : new CalibrationData.ParamMap();
        }

        int prior = existing != null ? existing.sampleCount : 0;
        int total = prior + count;
        double w  = (double) count / total; // new-session weight

        CalibrationData.ParamMap result = existing != null
                ? existing.parameters.deepCopy()
                : new CalibrationData.ParamMap();

        // ---- baseTimeMs / logScaleMs (Fitts OLS) ----
        if (count >= 2) {
            double n    = count;
            double denom = n * sumXX - sumX * sumX;
            if (Math.abs(denom) > 1e-9) {
                double slope     = (n * sumXY - sumX * sumY) / denom; // logScaleMs estimate
                double intercept = (sumY - slope * sumX) / n;          // baseTimeMs estimate
                result.mergeUniform("baseTimeMs", intercept, w, 100.0, 200.0);
                result.mergeUniform("logScaleMs",  slope,    w, 80.0,  140.0);
            }
        }

        // ---- powerLawBaseSpeed ----
        result.mergeUniform("powerLawBaseSpeed", median(cruiseSpeeds), w, 50.0, 200.0);

        // ---- velocityPeak ----
        if (countPeakPos > 0) {
            result.mergeUniform("velocityPeak", sumPeakPos / countPeakPos, w, 0.3, 0.5);
        }

        // ---- curveScale ----
        if (count > 0) {
            result.mergeUniform("curveScale", sumCurveScale / count, w, 0.5, 2.0);
        }

        // ---- curveBias / dirBias ----
        if (count > 0) {
            double biasMean = sumCurveSide / count;
            result.mergeUniform("curveBias", biasMean, w, -1.0, 1.0);
            result.mergeUniform("dirBias",   biasMean, w, -1.0, 1.0);
        }

        // ---- overshootTendency ----
        if (count > 0) {
            result.mergeUniform("overshootTendency", (double) overshootCount / count, w, 0.0, 0.3);
        }

        // ---- reactionTimeMu / Sigma ----
        if (reactionTimes.size() >= 2) {
            double muEst    = mean(reactionTimes);
            double sigmaEst = std(reactionTimes, muEst);
            result.mergeUniform("reactionTimeMu",    muEst,    w, 5.0, 6.2);
            result.mergeUniform("reactionTimeSigma", sigmaEst, w, 0.2, 0.7);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Computation helpers
    // -------------------------------------------------------------------------

    private static double medianSpeed(List<MovementSample> samples) {
        List<Double> speeds = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            MovementSample a = samples.get(i - 1);
            MovementSample b = samples.get(i);
            double dt = b.tMs - a.tMs;
            if (dt > 0) {
                speeds.add(Math.hypot(b.x - a.x, b.y - a.y) / dt);
            }
        }
        return median(speeds);
    }

    private static double peakArcPosition(List<MovementSample> samples) {
        double totalArc = 0;
        double[] arcAt  = new double[samples.size()];
        double[] speed  = new double[samples.size()];
        arcAt[0] = 0;
        speed[0] = 0;
        for (int i = 1; i < samples.size(); i++) {
            MovementSample a = samples.get(i - 1);
            MovementSample b = samples.get(i);
            double d  = Math.hypot(b.x - a.x, b.y - a.y);
            double dt = b.tMs - a.tMs;
            arcAt[i]   = arcAt[i - 1] + d;
            totalArc   += d;
            speed[i]   = dt > 0 ? d / dt : 0;
        }
        int peakIdx = 0;
        double peakSp = 0;
        for (int i = 0; i < speed.length; i++) {
            if (speed[i] > peakSp) { peakSp = speed[i]; peakIdx = i; }
        }
        return totalArc > 0 ? arcAt[peakIdx] / totalArc : 0.5;
    }

    private static double maxPerpDev(List<MovementSample> samples,
                                     double targetCx, double targetCy, double dist) {
        if (dist < 1e-9 || samples.size() < 2) {
            return 0.0;
        }
        MovementSample s0 = samples.get(0);
        MovementSample sN = samples.get(samples.size() - 1);
        double ux = (sN.x - s0.x) / dist;
        double uy = (sN.y - s0.y) / dist;
        double px = -uy, py = ux;
        double maxPerp = 0;
        for (MovementSample s : samples) {
            double rx = s.x - s0.x;
            double ry = s.y - s0.y;
            double perp = Math.abs(rx * px + ry * py);
            if (perp > maxPerp) maxPerp = perp;
        }
        return maxPerp;
    }

    private static double signedMeanPerpDev(List<MovementSample> samples, double dist) {
        if (dist < 1e-9 || samples.size() < 2) return 0.0;
        MovementSample s0 = samples.get(0);
        MovementSample sN = samples.get(samples.size() - 1);
        double ux = (sN.x - s0.x) / dist;
        double uy = (sN.y - s0.y) / dist;
        double px = -uy, py = ux;
        double sum = 0;
        for (MovementSample s : samples) {
            double rx = s.x - s0.x;
            double ry = s.y - s0.y;
            sum += rx * px + ry * py;
        }
        return sum / samples.size();
    }

    private static boolean crossedPastTarget(List<MovementSample> samples,
                                             double targetCx, double targetCy, double targetRadius) {
        boolean wasInside = false;
        boolean crossedPast = false;
        for (MovementSample s : samples) {
            double d = Math.hypot(s.x - targetCx, s.y - targetCy);
            if (d <= targetRadius) {
                wasInside = true;
            } else if (wasInside) {
                crossedPast = true;
                break;
            }
        }
        return crossedPast;
    }

    private static double median(List<Double> vals) {
        if (vals.isEmpty()) return 0.0;
        List<Double> copy = new ArrayList<>(vals);
        copy.sort(null);
        int mid = copy.size() / 2;
        return copy.size() % 2 == 0 ? (copy.get(mid - 1) + copy.get(mid)) / 2.0 : copy.get(mid);
    }

    private static double mean(List<Double> vals) {
        if (vals.isEmpty()) return 0.0;
        double s = 0;
        for (double v : vals) s += v;
        return s / vals.size();
    }

    private static double std(List<Double> vals, double mu) {
        if (vals.size() < 2) return 0.0;
        double s = 0;
        for (double v : vals) s += (v - mu) * (v - mu);
        return Math.sqrt(s / (vals.size() - 1));
    }
}
