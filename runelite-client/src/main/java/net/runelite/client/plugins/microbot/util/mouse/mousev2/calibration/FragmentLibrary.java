package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Loads and serves noise-texture and correction-template {@link Fragment} pools from disk.
 * Thread-safe via volatile singleton replacement on {@link #reload(Path)}.
 * <p>
 * {@link #hasData()} returns {@code false} when no data files are present — this keeps the engine
 * on its synthetic AR(1) path and leaves all behaviour unchanged from the pre-tuning baseline.
 */
public final class FragmentLibrary {

    /** Distance buckets for noise textures. */
    public static final String BUCKET_SHORT  = "short";   // < 120 px
    public static final String BUCKET_MEDIUM = "medium";  // < 500 px
    public static final String BUCKET_LONG   = "long";    // >= 500 px

    /** Speed buckets. */
    public static final String SPEED_SLOW = "slow";
    public static final String SPEED_FAST = "fast";
    /** Tangential-speed split (px/ms). Cruises below this → slow, at or above → fast. */
    public static final double SPEED_SPLIT_PX_PER_MS = 0.8;

    /** Correction magnitude buckets (peak-offset px thresholds). */
    public static final String CORR_SMALL  = "small";   // < 8 px
    public static final String CORR_MEDIUM = "medium";  // < 20 px
    public static final String CORR_LARGE  = "large";   // >= 20 px

    private static volatile FragmentLibrary INSTANCE;

    /** Map from bucket key (e.g. "short_slow") to list of noise fragments. */
    private final Map<String, List<Fragment>> noiseFragments;
    /** Map from bucket key ("small"/"medium"/"large") to list of correction fragments. */
    private final Map<String, List<Fragment>> correctionFragments;
    private final boolean hasAnyData;

    private FragmentLibrary(Map<String, List<Fragment>> noise, Map<String, List<Fragment>> correction) {
        this.noiseFragments      = noise;
        this.correctionFragments = correction;
        boolean any = false;
        for (List<Fragment> l : noise.values()) {
            if (!l.isEmpty()) { any = true; break; }
        }
        if (!any) {
            for (List<Fragment> l : correction.values()) {
                if (!l.isEmpty()) { any = true; break; }
            }
        }
        this.hasAnyData = any;
    }

    public static FragmentLibrary getInstance() {
        FragmentLibrary lib = INSTANCE;
        if (lib == null) {
            synchronized (FragmentLibrary.class) {
                lib = INSTANCE;
                if (lib == null) {
                    lib = empty();
                    INSTANCE = lib;
                }
            }
        }
        return lib;
    }

    public static void reload(Path baseDir) {
        synchronized (FragmentLibrary.class) {
            INSTANCE = load(baseDir);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} iff at least one fragment pool is non-empty. */
    public boolean hasData() {
        return hasAnyData;
    }

    /**
     * Returns a perpendicular-offset curve of length {@code outLen}, resampled from a randomly
     * selected fragment in the pool for {@code distBucket + "_" + speedBucket}. Returns
     * {@code null} if the bucket is empty (caller should fall back to the synthetic AR(1)).
     */
    public double[] sampleNoise(String distBucket, String speedBucket, int outLen, Random rng) {
        String key = distBucket + "_" + speedBucket;
        List<Fragment> pool = noiseFragments.get(key);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        Fragment f = pool.get(rng.nextInt(pool.size()));
        return f.resample(outLen);
    }

    /**
     * Returns a randomly selected correction fragment for {@code magBucket} ({@code "small"} /
     * {@code "medium"} / {@code "large"}), or {@code null} if the bucket is empty.
     */
    public Fragment sampleCorrection(String magBucket, Random rng) {
        List<Fragment> pool = correctionFragments.get(magBucket);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    // -------------------------------------------------------------------------
    // Bucketing helpers (static, used by classifier and engine)
    // -------------------------------------------------------------------------

    public static String distBucket(double px) {
        if (px < 120.0) return BUCKET_SHORT;
        if (px < 500.0) return BUCKET_MEDIUM;
        return BUCKET_LONG;
    }

    public static String speedBucket(double medianPxPerMs) {
        return medianPxPerMs < SPEED_SPLIT_PX_PER_MS ? SPEED_SLOW : SPEED_FAST;
    }

    public static String correctionBucket(double peakOffsetPx) {
        if (peakOffsetPx < 8.0)  return CORR_SMALL;
        if (peakOffsetPx < 20.0) return CORR_MEDIUM;
        return CORR_LARGE;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static FragmentLibrary empty() {
        return new FragmentLibrary(new HashMap<>(), new HashMap<>());
    }

    private static FragmentLibrary load(Path baseDir) {
        Map<String, List<Fragment>> noise      = new HashMap<>();
        Map<String, List<Fragment>> correction = new HashMap<>();

        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return empty();
        }

        // noise-textures/<bucket>.json
        Path noiseDir = baseDir.resolve("noise-textures");
        if (Files.isDirectory(noiseDir)) {
            for (String db : new String[]{BUCKET_SHORT, BUCKET_MEDIUM, BUCKET_LONG}) {
                for (String sb : new String[]{SPEED_SLOW, SPEED_FAST}) {
                    String key = db + "_" + sb;
                    Path p = noiseDir.resolve(key + ".json");
                    if (Files.isRegularFile(p)) {
                        noise.put(key, loadFragmentPool(p, key));
                    }
                }
            }
        }

        // correction-templates/<bucket>.json
        Path corrDir = baseDir.resolve("correction-templates");
        if (Files.isDirectory(corrDir)) {
            for (String mb : new String[]{CORR_SMALL, CORR_MEDIUM, CORR_LARGE}) {
                Path p = corrDir.resolve(mb + ".json");
                if (Files.isRegularFile(p)) {
                    correction.put(mb, loadFragmentPool(p, mb));
                }
            }
        }

        return new FragmentLibrary(noise, correction);
    }

    @SuppressWarnings("deprecation")
    private static List<Fragment> loadFragmentPool(Path path, String expectedBucket) {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonArray arr = new JsonParser().parse(r).getAsJsonArray();
            List<Fragment> out = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                JsonArray arcArr = obj.getAsJsonArray("arcFrac");
                JsonArray offArr = obj.getAsJsonArray("offsets");
                String bucket    = obj.has("bucketKey") ? obj.get("bucketKey").getAsString() : expectedBucket;
                if (arcArr == null || offArr == null || arcArr.size() != offArr.size()) {
                    continue;
                }
                double[] arcFrac = new double[arcArr.size()];
                double[] offsets = new double[offArr.size()];
                for (int i = 0; i < arcArr.size(); i++) {
                    arcFrac[i] = arcArr.get(i).getAsDouble();
                    offsets[i] = offArr.get(i).getAsDouble();
                }
                out.add(new Fragment(arcFrac, offsets, bucket));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
