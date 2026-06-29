package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FragmentClassifier}.
 */
public class FragmentClassifierTest {

    // ---- bucket assignment ----

    @Test
    public void shortDistanceGoesToShortBucket() {
        // 50 px move → "short"
        List<MovementSample> samples = horizontalSamples(50, 10);
        double cx = 50, cy = 0;
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, cx, cy, 22);
        assertNotNull("should classify", r);
        assertEquals("short", r.distBucket);
    }

    @Test
    public void mediumDistanceGoesToMediumBucket() {
        // 300 px move → "medium"
        List<MovementSample> samples = horizontalSamples(300, 30);
        double cx = 300, cy = 0;
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, cx, cy, 22);
        assertNotNull(r);
        assertEquals("medium", r.distBucket);
    }

    @Test
    public void longDistanceGoesToLongBucket() {
        // 600 px move → "long"
        List<MovementSample> samples = horizontalSamples(600, 40);
        double cx = 600, cy = 0;
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, cx, cy, 22);
        assertNotNull(r);
        assertEquals("long", r.distBucket);
    }

    @Test
    public void speedBucketIsSlowForSlowMovement() {
        // Very slow movement: 50 px in 500 ms → speed = 0.1 px/ms → slow
        List<MovementSample> samples = new ArrayList<>();
        int n = 20;
        for (int i = 0; i < n; i++) {
            double x = i * (50.0 / (n - 1));
            long t = i * 25L; // 25 ms per step → 0.1 px/ms
            samples.add(new MovementSample(x, 0, t));
        }
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, 50, 0, 22);
        assertNotNull(r);
        assertEquals("slow", r.speedBucket);
    }

    @Test
    public void speedBucketIsFastForFastMovement() {
        // Fast movement: 300 px in 100 ms → speed = 3 px/ms → fast
        List<MovementSample> samples = new ArrayList<>();
        int n = 20;
        for (int i = 0; i < n; i++) {
            double x = i * (300.0 / (n - 1));
            long t = i * 5L; // 5 ms per step → ~3 px/ms
            samples.add(new MovementSample(x, 0, t));
        }
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, 300, 0, 22);
        assertNotNull(r);
        assertEquals("fast", r.speedBucket);
    }

    // ---- cruise / correction split ----

    @Test
    public void samplesInsideTargetBecomeCorrectionFragment() {
        // Samples: first 10 outside, last 5 inside the 22-px target
        double cx = 100, cy = 0;
        List<MovementSample> samples = new ArrayList<>();
        // cruise: approach the target from x=0 to x=78 (just outside)
        for (int i = 0; i < 10; i++) {
            double x = i * (78.0 / 9);
            samples.add(new MovementSample(x, 0, i * 20L));
        }
        // settle: inside the circle
        for (int i = 0; i < 5; i++) {
            samples.add(new MovementSample(100 + i * 2.0, 0, 200 + i * 20L));
        }
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, cx, cy, 22);
        assertNotNull(r);
        // correction fragment should exist (multiple samples inside circle)
        assertNotNull("correction fragment expected", r.correctionFragment);
    }

    @Test
    public void noiseFragmentHasNormalisedArcFractions() {
        List<MovementSample> samples = horizontalSamples(200, 20);
        FragmentClassifier.Result r = FragmentClassifier.classify(samples, 200, 0, 22);
        assertNotNull(r);
        if (r.noiseFragment != null) {
            double[] arc = r.noiseFragment.arcFrac;
            assertTrue("arcFrac starts near 0", arc[0] >= 0.0);
            assertTrue("arcFrac ends near 1", arc[arc.length - 1] <= 1.0 + 1e-9);
        }
    }

    @Test
    public void tooFewSamplesReturnsNull() {
        List<MovementSample> tiny = new ArrayList<>();
        tiny.add(new MovementSample(0, 0, 0));
        tiny.add(new MovementSample(10, 0, 10));
        assertNull(FragmentClassifier.classify(tiny, 10, 0, 22));
    }

    // ---- helpers ----

    /** Straight horizontal motion from x=0 to x=dist, n samples equally spaced in time. */
    private static List<MovementSample> horizontalSamples(double dist, int n) {
        List<MovementSample> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double x = dist * i / (n - 1);
            long t = i * 10L; // 10 ms per step
            list.add(new MovementSample(x, 0, t));
        }
        return list;
    }
}
