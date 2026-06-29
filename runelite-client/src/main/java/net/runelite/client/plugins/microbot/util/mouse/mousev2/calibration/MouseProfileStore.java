package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.client.RuneLite;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All disk I/O for the mouse-profile data: calibration.json, per-bucket fragment pools and
 * micro-pauses.json. Uses {@link Gson} for serialisation (pretty-printed).
 * <p>
 * Base directory: {@code RuneLite.RUNELITE_DIR/microbot/mouse-profiles/}
 */
public final class MouseProfileStore {

    /** Base directory under the RuneLite user dir. */
    public static final Path BASE_DIR =
            RuneLite.RUNELITE_DIR.toPath().resolve("microbot").resolve("mouse-profiles");

    /** Path to the merged calibration parameter file. */
    public static final Path CALIBRATION_PATH = BASE_DIR.resolve("calibration.json");

    /** Directory for noise-texture fragment pools. */
    public static final Path NOISE_DIR = BASE_DIR.resolve("noise-textures");

    /** Directory for correction-template fragment pools. */
    public static final Path CORRECTION_DIR = BASE_DIR.resolve("correction-templates");

    /** Path to the micro-pauses lognormal model. */
    public static final Path MICRO_PAUSES_PATH = BASE_DIR.resolve("micro-pauses.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MouseProfileStore() {}

    // -------------------------------------------------------------------------
    // Calibration JSON
    // -------------------------------------------------------------------------

    /**
     * Loads the on-disk calibration file, or returns an empty {@link CalibrationData} if absent /
     * unreadable.
     */
    public static CalibrationData loadCalibration() {
        if (!Files.isRegularFile(CALIBRATION_PATH)) {
            return new CalibrationData();
        }
        try (Reader r = Files.newBufferedReader(CALIBRATION_PATH, StandardCharsets.UTF_8)) {
            CalibrationData data = GSON.fromJson(r, CalibrationData.class);
            if (data == null) {
                return new CalibrationData();
            }
            if (data.parameters == null) {
                data.parameters = new CalibrationData.ParamMap();
            }
            if (data.parameters.data == null) {
                data.parameters.data = new HashMap<>();
            }
            return data;
        } catch (Exception e) {
            return new CalibrationData();
        }
    }

    /**
     * Writes {@code data} to {@link #CALIBRATION_PATH}, creating parent directories as needed.
     */
    public static void saveCalibration(CalibrationData data) throws IOException {
        Files.createDirectories(BASE_DIR);
        try (Writer w = Files.newBufferedWriter(CALIBRATION_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(data, w);
        }
    }

    // -------------------------------------------------------------------------
    // Fragment pools
    // -------------------------------------------------------------------------

    /**
     * Appends (or creates) the JSON array for a noise-texture bucket.
     *
     * @param bucketKey e.g. {@code "short_slow"}
     * @param fragments list of fragments to store
     */
    public static void saveNoiseFragments(String bucketKey, List<Fragment> fragments)
            throws IOException {
        Files.createDirectories(NOISE_DIR);
        Path file = NOISE_DIR.resolve(bucketKey + ".json");
        saveFragmentPool(file, fragments);
    }

    /**
     * Appends (or creates) the JSON array for a correction-template bucket.
     *
     * @param bucketKey e.g. {@code "small"}
     * @param fragments list of fragments to store
     */
    public static void saveCorrectionFragments(String bucketKey, List<Fragment> fragments)
            throws IOException {
        Files.createDirectories(CORRECTION_DIR);
        Path file = CORRECTION_DIR.resolve(bucketKey + ".json");
        saveFragmentPool(file, fragments);
    }

    private static void saveFragmentPool(Path file, List<Fragment> fragments) throws IOException {
        // Load existing array if present, then append
        JsonArray arr = new JsonArray();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonArray existing = GSON.fromJson(r, JsonArray.class);
                if (existing != null) {
                    arr = existing;
                }
            } catch (Exception ignored) {
                arr = new JsonArray();
            }
        }
        for (Fragment frag : fragments) {
            JsonObject obj = new JsonObject();
            obj.addProperty("bucketKey", frag.bucketKey);
            JsonArray arcArr = new JsonArray();
            JsonArray offArr = new JsonArray();
            for (int i = 0; i < frag.arcFrac.length; i++) {
                arcArr.add(frag.arcFrac[i]);
                offArr.add(frag.offsets[i]);
            }
            obj.add("arcFrac", arcArr);
            obj.add("offsets", offArr);
            arr.add(obj);
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(arr, w);
        }
    }

    // -------------------------------------------------------------------------
    // Micro-pauses JSON
    // -------------------------------------------------------------------------

    /**
     * Writes (or merges) micro-pause lognormal parameters. {@code byBucket} maps distance-bucket
     * name to {@code [mu, sigma]}.
     */
    public static void saveMicroPauses(Map<String, double[]> byBucket) throws IOException {
        Files.createDirectories(BASE_DIR);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject body = new JsonObject();
        for (Map.Entry<String, double[]> e : byBucket.entrySet()) {
            JsonObject dist = new JsonObject();
            dist.addProperty("mu",    e.getValue()[0]);
            dist.addProperty("sigma", e.getValue()[1]);
            body.add(e.getKey(), dist);
        }
        root.add("byDistanceBucket", body);
        try (Writer w = Files.newBufferedWriter(MICRO_PAUSES_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(root, w);
        }
    }

    // -------------------------------------------------------------------------
    // Delete all
    // -------------------------------------------------------------------------

    /**
     * Deletes all on-disk profile artifacts (calibration.json, fragment pools, micro-pauses.json).
     * Silently ignores missing files. Directory structure is left in place.
     */
    public static void deleteAll() {
        safeDelete(CALIBRATION_PATH);
        safeDelete(MICRO_PAUSES_PATH);
        for (String db : new String[]{"short", "medium", "long"}) {
            for (String sb : new String[]{"slow", "fast"}) {
                safeDelete(NOISE_DIR.resolve(db + "_" + sb + ".json"));
            }
        }
        for (String mb : new String[]{"small", "medium", "large"}) {
            safeDelete(CORRECTION_DIR.resolve(mb + ".json"));
        }
    }

    private static void safeDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
