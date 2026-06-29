package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FragmentLibrary}.
 */
public class FragmentLibraryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Gson GSON = new GsonBuilder().create();

    // ---- hasData on empty library ----

    @Test
    public void emptyLibraryHasNoData() {
        FragmentLibrary.reload(null); // null path → empty
        assertFalse("empty library should report hasData=false",
                FragmentLibrary.getInstance().hasData());
    }

    // ---- JSON round-trip ----

    @Test
    public void jsonRoundTripLoadsFragmentsCorrectly() throws Exception {
        Path base = tmp.newFolder("profiles").toPath();
        Path noiseDir = base.resolve("noise-textures");
        Files.createDirectories(noiseDir);

        // Write a single-fragment JSON for short_slow
        JsonArray arr = new JsonArray();
        JsonObject frag = new JsonObject();
        frag.addProperty("bucketKey", "short_slow");
        JsonArray arc = new JsonArray();
        JsonArray off = new JsonArray();
        arc.add(0.0); arc.add(0.5); arc.add(1.0);
        off.add(0.0); off.add(0.1); off.add(0.0);
        frag.add("arcFrac", arc);
        frag.add("offsets", off);
        arr.add(frag);

        try (Writer w = Files.newBufferedWriter(noiseDir.resolve("short_slow.json"),
                StandardCharsets.UTF_8)) {
            GSON.toJson(arr, w);
        }

        FragmentLibrary.reload(base);
        FragmentLibrary lib = FragmentLibrary.getInstance();
        assertTrue("library should have data", lib.hasData());
    }

    // ---- sampleNoise returns correct length ----

    @Test
    public void sampleNoiseReturnsRequestedLength() throws Exception {
        Path base = tmp.newFolder("profiles2").toPath();
        Path noiseDir = base.resolve("noise-textures");
        Files.createDirectories(noiseDir);
        writeMinimalFragment(noiseDir.resolve("medium_slow.json"), "medium_slow");

        FragmentLibrary.reload(base);
        FragmentLibrary lib = FragmentLibrary.getInstance();

        double[] curve = lib.sampleNoise("medium", "slow", 15, new Random(42));
        assertNotNull("should return a curve", curve);
        assertEquals("curve length must match requested outLen", 15, curve.length);
    }

    // ---- empty bucket → null ----

    @Test
    public void emptyBucketReturnsNull() throws Exception {
        // Load a library that only has short_slow; query long_fast → null
        Path base = tmp.newFolder("profiles3").toPath();
        Path noiseDir = base.resolve("noise-textures");
        Files.createDirectories(noiseDir);
        writeMinimalFragment(noiseDir.resolve("short_slow.json"), "short_slow");

        FragmentLibrary.reload(base);
        FragmentLibrary lib = FragmentLibrary.getInstance();

        double[] curve = lib.sampleNoise("long", "fast", 10, new Random(1));
        assertNull("empty bucket should return null (fallback path)", curve);
    }

    // ---- correction template ----

    @Test
    public void correctionFragmentRoundTrip() throws Exception {
        Path base = tmp.newFolder("profiles4").toPath();
        Path corrDir = base.resolve("correction-templates");
        Files.createDirectories(corrDir);
        writeMinimalFragment(corrDir.resolve("small.json"), "small");

        FragmentLibrary.reload(base);
        Fragment f = FragmentLibrary.getInstance().sampleCorrection("small", new Random(7));
        assertNotNull("should return a correction fragment", f);
        assertEquals("small", f.bucketKey);
    }

    // ---- helpers ----

    private static void writeMinimalFragment(Path file, String bucketKey) throws Exception {
        JsonArray arr = new JsonArray();
        JsonObject frag = new JsonObject();
        frag.addProperty("bucketKey", bucketKey);
        JsonArray arc = new JsonArray();
        JsonArray off = new JsonArray();
        for (double v : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            arc.add(v);
            off.add(v * 0.05);
        }
        frag.add("arcFrac", arc);
        frag.add("offsets", off);
        arr.add(frag);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(arr, w);
        }
    }
}
