package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Lognormal pre-move pause model. Loads {@code micro-pauses.json} from disk when present;
 * returns 0 (no pause) when the file is absent — so the default engine behaviour is unchanged.
 * <p>
 * JSON schema:
 * <pre>
 * { "version":1, "byDistanceBucket": {
 *   "short":  { "mu": 3.8, "sigma": 0.6 },
 *   "medium": { "mu": 4.0, "sigma": 0.6 },
 *   "long":   { "mu": 4.2, "sigma": 0.6 }
 * }}
 * </pre>
 */
public final class MicroPauseModel {

    private static volatile MicroPauseModel INSTANCE;

    /** Per-distance-bucket lognormal parameters. {@code null} = no data file loaded. */
    private final Map<String, double[]> byBucket; // bucket → [mu, sigma]

    private MicroPauseModel(Map<String, double[]> byBucket) {
        this.byBucket = byBucket;
    }

    public static MicroPauseModel getInstance() {
        MicroPauseModel m = INSTANCE;
        if (m == null) {
            synchronized (MicroPauseModel.class) {
                m = INSTANCE;
                if (m == null) {
                    m = empty();
                    INSTANCE = m;
                }
            }
        }
        return m;
    }

    public static void reload(Path diskPath) {
        synchronized (MicroPauseModel.class) {
            INSTANCE = loadFromDisk(diskPath);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} iff the data file was loaded (non-zero pauses possible). */
    public boolean hasData() {
        return !byBucket.isEmpty();
    }

    /**
     * Draws a pre-move pause duration in milliseconds from the lognormal distribution
     * appropriate for the given distance. Returns 0 when no data is present (file absent).
     *
     * @param distPx euclidean move distance in pixels
     * @param rng    caller's random source
     * @return sleep duration in milliseconds (≥ 0)
     */
    public long sampleDelayMs(double distPx, Random rng) {
        if (byBucket.isEmpty()) {
            return 0L;
        }
        String bucket = FragmentLibrary.distBucket(distPx);
        double[] ms   = byBucket.get(bucket);
        if (ms == null) {
            return 0L;
        }
        double mu    = ms[0];
        double sigma = ms[1];
        double delay = Math.exp(mu + sigma * rng.nextGaussian());
        return Math.max(0L, Math.round(delay));
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static MicroPauseModel empty() {
        return new MicroPauseModel(new HashMap<>());
    }

    private static MicroPauseModel loadFromDisk(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return empty();
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            @SuppressWarnings("deprecation")
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            JsonObject byDist = root.has("byDistanceBucket")
                    ? root.getAsJsonObject("byDistanceBucket") : new JsonObject();
            Map<String, double[]> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : byDist.entrySet()) {
                JsonObject dist = e.getValue().getAsJsonObject();
                double mu    = dist.has("mu")    ? dist.get("mu").getAsDouble()    : 4.0;
                double sigma = dist.has("sigma") ? dist.get("sigma").getAsDouble() : 0.6;
                map.put(e.getKey(), new double[]{mu, sigma});
            }
            return new MicroPauseModel(map);
        } catch (Exception ex) {
            return empty();
        }
    }
}
