package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CalibrationModel}.
 * <p>
 * Critical: uniform draw must produce byte-for-byte identical results to the legacy
 * {@code MouseProfile.dbl} formula so existing tests remain green.
 */
public class CalibrationModelTest {

    @Before
    public void resetToDefault() {
        // Ensure the bundled default is loaded (no disk file interference from other tests)
        CalibrationModel.resetToDefault();
    }

    // ---- uniform draw equality with legacy formula ----

    @Test
    public void uniformDrawEqualsLegacyDblForBaseTimeMs() {
        String seed = "account-123";
        String key  = "baseTimeMs";
        double min  = 100.0;
        double max  = 200.0;

        // Legacy formula (MouseProfile.dbl):
        double expected = min + (max - min) * new Random(CalibrationModel.mix(seed, key)).nextDouble();

        double actual = CalibrationModel.getInstance().draw(seed, key);

        assertEquals("uniform draw must match legacy dbl formula for baseTimeMs",
                expected, actual, 0.0);
    }

    @Test
    public void uniformDrawEqualsLegacyDblForCurveBias() {
        String seed = "test-seed-999";
        String key  = "curveBias";
        double min  = -1.0;
        double max  =  1.0;

        double expected = min + (max - min) * new Random(CalibrationModel.mix(seed, key)).nextDouble();
        double actual   = CalibrationModel.getInstance().draw(seed, key);

        assertEquals("uniform draw must match legacy dbl formula for curveBias",
                expected, actual, 0.0);
    }

    @Test
    public void uniformDrawEqualsLegacyDblForVelocityPeak() {
        String seed = "default";
        String key  = "velocityPeak";
        double min  = 0.3;
        double max  = 0.5;

        double expected = min + (max - min) * new Random(CalibrationModel.mix(seed, key)).nextDouble();
        double actual   = CalibrationModel.getInstance().draw(seed, key);

        assertEquals("uniform draw must match legacy dbl for velocityPeak",
                expected, actual, 0.0);
    }

    // ---- clamp behaviour ----

    @Test
    public void uniformDrawStaysWithinClampRange() {
        CalibrationModel model = CalibrationModel.getInstance();
        // The bundled default has clampLo/Hi outside the min/max; the draw should be within [min,max]
        for (String seed : new String[]{"a", "bb", "123", "default", "abcdef"}) {
            double v = model.draw(seed, "baseTimeMs");
            assertTrue("draw >= 100: " + v, v >= 100.0);
            assertTrue("draw <= 200: " + v, v <= 200.0);
        }
    }

    // ---- missing key → NaN ----

    @Test
    public void missingKeyReturnsNaN() {
        double v = CalibrationModel.getInstance().draw("seed", "nonExistentKey_xyz");
        assertTrue("missing key should return NaN", Double.isNaN(v));
    }

    // ---- mix function reproducibility ----

    @Test
    public void mixIsDeterministic() {
        long a = CalibrationModel.mix("seed1", "key1");
        long b = CalibrationModel.mix("seed1", "key1");
        assertEquals("mix must be deterministic", a, b);
    }

    @Test
    public void mixDiffersForDifferentInputs() {
        long a = CalibrationModel.mix("seed1", "key1");
        long b = CalibrationModel.mix("seed2", "key1");
        long c = CalibrationModel.mix("seed1", "key2");
        assertNotEquals("seed change should alter mix", a, b);
        assertNotEquals("key change should alter mix", a, c);
    }

    // ---- reload / resetToDefault ----

    @Test
    public void resetToDefaultRestoresInstance() {
        CalibrationModel.resetToDefault();
        CalibrationModel model = CalibrationModel.getInstance();
        assertNotNull("getInstance must return non-null after resetToDefault", model);
        // Should still draw the same bundled-default value
        double v = model.draw("x", "baseTimeMs");
        assertFalse("should produce a real value after reset", Double.isNaN(v));
    }
}
