package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies one recorded acquisition (list of {@link MovementSample}s plus target geometry) into
 * a cruise (noise) fragment and a correction (settle) fragment.
 * <p>
 * Pure computation — no Swing / IO / game-client access.
 */
public final class FragmentClassifier {

    private FragmentClassifier() {}

    /** Result of classifying one acquisition. */
    public static final class Result {
        /** Normalised noise/cruise fragment; may be {@code null} if the cruise was too short. */
        public final Fragment noiseFragment;
        /** Normalised correction/settle fragment; may be {@code null} if the settle was trivial. */
        public final Fragment correctionFragment;
        /** Distance bucket key for the noise fragment. */
        public final String distBucket;
        /** Speed bucket key for the noise fragment. */
        public final String speedBucket;
        /** Magnitude bucket key for the correction fragment. */
        public final String magBucket;

        Result(Fragment noiseFragment, Fragment correctionFragment,
               String distBucket, String speedBucket, String magBucket) {
            this.noiseFragment      = noiseFragment;
            this.correctionFragment = correctionFragment;
            this.distBucket         = distBucket;
            this.speedBucket        = speedBucket;
            this.magBucket          = magBucket;
        }
    }

    /**
     * Classifies the acquisition.
     *
     * @param samples      all samples recorded from motion-start to click
     * @param targetCx     target circle centre X
     * @param targetCy     target circle centre Y
     * @param targetRadius target circle radius (px)
     * @return classification result, or {@code null} if samples are insufficient
     */
    public static Result classify(List<MovementSample> samples,
                                  double targetCx, double targetCy, double targetRadius) {
        if (samples == null || samples.size() < 4) {
            return null;
        }

        // Find first sample inside the target circle
        int entryIdx = -1;
        for (int i = 0; i < samples.size(); i++) {
            MovementSample s = samples.get(i);
            double dx = s.x - targetCx;
            double dy = s.y - targetCy;
            if (dx * dx + dy * dy <= targetRadius * targetRadius) {
                entryIdx = i;
                break;
            }
        }

        List<MovementSample> cruise;
        List<MovementSample> settle;

        if (entryIdx < 0) {
            // Cursor never entered target — treat all samples as cruise
            cruise = samples;
            settle = new ArrayList<>();
        } else {
            cruise = samples.subList(0, entryIdx + 1);
            settle = samples.subList(entryIdx, samples.size());
        }

        // ----- Cruise (noise) fragment -----
        MovementSample start = samples.get(0);
        MovementSample end   = cruise.get(cruise.size() - 1);
        double dx   = end.x - start.x;
        double dy   = end.y - start.y;
        double dist = Math.hypot(dx, dy);

        String distBucket  = FragmentLibrary.distBucket(dist);
        double medianSpeed = medianCruiseSpeed(cruise);
        String speedBucket = FragmentLibrary.speedBucket(medianSpeed);

        Fragment noiseFragment = null;
        if (cruise.size() >= 3 && dist > 5.0) {
            noiseFragment = buildNoiseFragment(cruise, dist, distBucket + "_" + speedBucket);
        }

        // ----- Correction (settle) fragment -----
        String magBucket = FragmentLibrary.CORR_SMALL;
        Fragment correctionFragment = null;
        if (settle.size() >= 2) {
            double peakOffset = peakOffsetFromTarget(settle, targetCx, targetCy);
            magBucket = FragmentLibrary.correctionBucket(peakOffset);
            // Normalise by target radius (or 1 if zero)
            double norm = targetRadius > 0.5 ? targetRadius : 1.0;
            correctionFragment = buildCorrectionFragment(settle, targetCx, targetCy, norm, magBucket);
        }

        return new Result(noiseFragment, correctionFragment, distBucket, speedBucket, magBucket);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Perpendicular residual offsets along the straight start→end line. */
    private static Fragment buildNoiseFragment(List<MovementSample> cruise,
                                              double dist, String bucketKey) {
        MovementSample s0 = cruise.get(0);
        MovementSample sN = cruise.get(cruise.size() - 1);
        // unit direction vector
        double ux = (sN.x - s0.x) / dist;
        double uy = (sN.y - s0.y) / dist;
        // perpendicular
        double px = -uy;
        double py =  ux;

        // compute arc lengths
        double[] arcLen = new double[cruise.size()];
        arcLen[0] = 0.0;
        for (int i = 1; i < cruise.size(); i++) {
            double ddx = cruise.get(i).x - cruise.get(i - 1).x;
            double ddy = cruise.get(i).y - cruise.get(i - 1).y;
            arcLen[i] = arcLen[i - 1] + Math.hypot(ddx, ddy);
        }
        double totalArc = arcLen[arcLen.length - 1];
        if (totalArc < 1e-9) {
            totalArc = dist;
        }

        double[] arcFrac = new double[cruise.size()];
        double[] offsets = new double[cruise.size()];
        for (int i = 0; i < cruise.size(); i++) {
            MovementSample s = cruise.get(i);
            // perpendicular component of (s - s0) projected onto perp
            double rx = s.x - s0.x;
            double ry = s.y - s0.y;
            arcFrac[i] = arcLen[i] / totalArc;
            // normalise by dist so the fragment is scale-independent
            offsets[i] = (rx * px + ry * py) / (dist > 0 ? dist : 1.0);
        }

        return new Fragment(arcFrac, offsets, bucketKey);
    }

    private static Fragment buildCorrectionFragment(List<MovementSample> settle,
                                                    double targetCx, double targetCy,
                                                    double norm, String bucketKey) {
        // arc lengths within settle
        double[] arcLen = new double[settle.size()];
        arcLen[0] = 0.0;
        for (int i = 1; i < settle.size(); i++) {
            double ddx = settle.get(i).x - settle.get(i - 1).x;
            double ddy = settle.get(i).y - settle.get(i - 1).y;
            arcLen[i] = arcLen[i - 1] + Math.hypot(ddx, ddy);
        }
        double totalArc = arcLen[arcLen.length - 1];
        if (totalArc < 1e-9) {
            totalArc = 1.0;
        }

        double[] arcFrac = new double[settle.size()];
        double[] offsets = new double[settle.size()];
        for (int i = 0; i < settle.size(); i++) {
            MovementSample s = settle.get(i);
            arcFrac[i] = arcLen[i] / totalArc;
            double distToTarget = Math.hypot(s.x - targetCx, s.y - targetCy);
            offsets[i] = distToTarget / norm;
        }

        return new Fragment(arcFrac, offsets, bucketKey);
    }

    private static double medianCruiseSpeed(List<MovementSample> cruise) {
        List<Double> speeds = new ArrayList<>(cruise.size());
        for (int i = 1; i < cruise.size(); i++) {
            MovementSample a = cruise.get(i - 1);
            MovementSample b = cruise.get(i);
            double dt = b.tMs - a.tMs;
            if (dt > 0) {
                double d = Math.hypot(b.x - a.x, b.y - a.y);
                speeds.add(d / dt);
            }
        }
        if (speeds.isEmpty()) {
            return 0.0;
        }
        speeds.sort(null);
        int mid = speeds.size() / 2;
        return speeds.size() % 2 == 0
                ? (speeds.get(mid - 1) + speeds.get(mid)) / 2.0
                : speeds.get(mid);
    }

    private static double peakOffsetFromTarget(List<MovementSample> settle,
                                               double targetCx, double targetCy) {
        double peak = 0.0;
        for (MovementSample s : settle) {
            double d = Math.hypot(s.x - targetCx, s.y - targetCy);
            if (d > peak) {
                peak = d;
            }
        }
        return peak;
    }
}
