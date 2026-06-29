package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import com.google.gson.Gson;
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
 * Unit tests for {@link MicroPauseModel}.
 */
public class MicroPauseModelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Gson GSON = new Gson();

    // ---- absent file → 0 ----

    @Test
    public void absentFileReturnsZero() {
        MicroPauseModel.reload(null);
        long delay = MicroPauseModel.getInstance().sampleDelayMs(300, new Random(1));
        assertEquals("absent file should return 0", 0L, delay);
    }

    @Test
    public void nonExistentPathReturnsZero() throws Exception {
        Path ghost = tmp.newFolder("empty").toPath().resolve("micro-pauses.json");
        MicroPauseModel.reload(ghost);
        long delay = MicroPauseModel.getInstance().sampleDelayMs(300, new Random(2));
        assertEquals("non-existent path should return 0", 0L, delay);
    }

    // ---- lognormal draw in plausible range ----

    @Test
    public void lognormalDrawIsInPlausibleRange() throws Exception {
        Path file = writeMicroPausesJson();
        MicroPauseModel.reload(file);
        MicroPauseModel model = MicroPauseModel.getInstance();
        assertTrue("hasData after loading file", model.hasData());

        // Sample 20 values for medium distance (300 px); all should be positive and < 10000 ms
        Random rng = new Random(42);
        for (int i = 0; i < 20; i++) {
            long delay = model.sampleDelayMs(300, rng);
            assertTrue("delay should be >= 0", delay >= 0L);
            // exp(mu + 3*sigma) = exp(4.0 + 3*0.6) = exp(5.8) ≈ 330 ms → generous upper bound
            assertTrue("delay should be < 10000 ms (got " + delay + ")", delay < 10_000L);
        }
    }

    @Test
    public void shortDistanceUsesShortBucket() throws Exception {
        Path file = writeMicroPausesJson();
        MicroPauseModel.reload(file);
        // Just verify no exception and non-negative for short distance
        long delay = MicroPauseModel.getInstance().sampleDelayMs(50, new Random(7));
        assertTrue(delay >= 0L);
    }

    @Test
    public void longDistanceUsesLongBucket() throws Exception {
        Path file = writeMicroPausesJson();
        MicroPauseModel.reload(file);
        long delay = MicroPauseModel.getInstance().sampleDelayMs(700, new Random(9));
        assertTrue(delay >= 0L);
    }

    // ---- helpers ----

    private Path writeMicroPausesJson() throws Exception {
        Path file = tmp.newFile("micro-pauses.json").toPath();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject by = new JsonObject();
        for (String bucket : new String[]{"short", "medium", "long"}) {
            JsonObject d = new JsonObject();
            d.addProperty("mu",    3.8 + ("long".equals(bucket) ? 0.4 : "medium".equals(bucket) ? 0.2 : 0.0));
            d.addProperty("sigma", 0.6);
            by.add(bucket, d);
        }
        root.add("byDistanceBucket", by);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
        return file;
    }
}
