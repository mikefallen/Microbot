package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Loads calibration.json (disk file if present, else bundled resource) and provides per-parameter
 * seeded draws. Thread-safe via volatile singleton replacement on reload.
 * <p>
 * Critical: for {@code uniform} distributions, {@link #draw(String, String)} computes
 * {@code min + (max-min) * new Random(mix(seed,key)).nextDouble()} — byte-for-byte identical to
 * the legacy {@code MouseProfile.dbl} formula so existing tests remain green.
 */
public final class CalibrationModel {

    private static final String BUNDLED_RESOURCE =
            "/net/runelite/client/plugins/microbot/util/mouse/mousev2/calibration-default.json";

    /** Volatile singleton — replaced atomically on reload. */
    private static volatile CalibrationModel INSTANCE;

    /** Per-parameter distribution descriptors. */
    private final Map<String, Dist> params;

    private CalibrationModel(Map<String, Dist> params) {
        this.params = params;
    }

    // -------------------------------------------------------------------------
    // Singleton access
    // -------------------------------------------------------------------------

    public static CalibrationModel getInstance() {
        CalibrationModel m = INSTANCE;
        if (m == null) {
            synchronized (CalibrationModel.class) {
                m = INSTANCE;
                if (m == null) {
                    m = loadDefault();
                    INSTANCE = m;
                }
            }
        }
        return m;
    }

    /**
     * Re-reads the calibration from disk (if present) or falls back to the bundled resource.
     * Called after Process &amp; Save or Delete My Data.
     */
    public static void reload(Path diskPath) {
        synchronized (CalibrationModel.class) {
            INSTANCE = loadFromDiskOrBundled(diskPath);
        }
    }

    /** Forces the singleton back to the bundled default (for tests or delete-data). */
    public static void resetToDefault() {
        synchronized (CalibrationModel.class) {
            INSTANCE = loadDefault();
        }
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /**
     * Draws a value for {@code key} seeded by {@code seed}. Returns {@link Double#NaN} when the
     * key is absent (caller should fall back to its hardcoded literal range).
     */
    public double draw(String seed, String key) {
        Dist d = params.get(key);
        if (d == null) {
            return Double.NaN;
        }
        Random rng = new Random(mix(seed, key));
        switch (d.distribution) {
            case "uniform": {
                double min = d.params.getOrDefault("min", 0.0);
                double max = d.params.getOrDefault("max", 1.0);
                return clamp(min + (max - min) * rng.nextDouble(), d.clampLo, d.clampHi);
            }
            case "normal": {
                double mean = d.params.getOrDefault("mean", 0.0);
                double std  = d.params.getOrDefault("std", 1.0);
                return clamp(mean + std * rng.nextGaussian(), d.clampLo, d.clampHi);
            }
            case "lognormal": {
                double mu    = d.params.getOrDefault("mu", 0.0);
                double sigma = d.params.getOrDefault("sigma", 1.0);
                return clamp(Math.exp(mu + sigma * rng.nextGaussian()), d.clampLo, d.clampHi);
            }
            default:
                return Double.NaN;
        }
    }

    // -------------------------------------------------------------------------
    // Loading helpers
    // -------------------------------------------------------------------------

    private static CalibrationModel loadDefault() {
        try (InputStream is = CalibrationModel.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (is == null) {
                return new CalibrationModel(new HashMap<>());
            }
            try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return parse(r);
            }
        } catch (Exception e) {
            return new CalibrationModel(new HashMap<>());
        }
    }

    private static CalibrationModel loadFromDiskOrBundled(Path diskPath) {
        if (diskPath != null && Files.isRegularFile(diskPath)) {
            try (Reader r = Files.newBufferedReader(diskPath, StandardCharsets.UTF_8)) {
                return parse(r);
            } catch (Exception e) {
                // fall through to bundled
            }
        }
        return loadDefault();
    }

    /** Parses the calibration JSON schema into a model. */
    @SuppressWarnings("deprecation")
    private static CalibrationModel parse(Reader reader) {
        JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
        JsonObject parameters = root.has("parameters") ? root.getAsJsonObject("parameters") : new JsonObject();
        Map<String, Dist> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
            String key = entry.getKey();
            JsonObject obj = entry.getValue().getAsJsonObject();
            String dist = obj.has("distribution") ? obj.get("distribution").getAsString() : "uniform";
            double clampLo = obj.has("clampLo") ? obj.get("clampLo").getAsDouble() : Double.NEGATIVE_INFINITY;
            double clampHi = obj.has("clampHi") ? obj.get("clampHi").getAsDouble() : Double.POSITIVE_INFINITY;
            Map<String, Double> paramVals = new HashMap<>();
            if (obj.has("params")) {
                for (Map.Entry<String, JsonElement> pe : obj.getAsJsonObject("params").entrySet()) {
                    paramVals.put(pe.getKey(), pe.getValue().getAsDouble());
                }
            }
            map.put(key, new Dist(dist, paramVals, clampLo, clampHi));
        }
        return new CalibrationModel(map);
    }

    // -------------------------------------------------------------------------
    // Utilities — match MouseProfile exactly
    // -------------------------------------------------------------------------

    /**
     * Stable 64-bit mix of the seed string and a parameter key. MUST be byte-for-byte identical
     * to {@code MouseProfile.mix} so the same seed+key produces the same {@link Random} state.
     */
    public static long mix(String seed, String key) {
        long h = 1125899906842597L; // prime
        String s = seed + ':' + key;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    /** Descriptor for one parameter's distribution. */
    public static final class Dist {
        public final String distribution;
        public final Map<String, Double> params;
        public final double clampLo;
        public final double clampHi;

        Dist(String distribution, Map<String, Double> params, double clampLo, double clampHi) {
            this.distribution = distribution;
            this.params       = params;
            this.clampLo      = clampLo;
            this.clampHi      = clampHi;
        }
    }
}
