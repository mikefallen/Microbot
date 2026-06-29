package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.mouse.VirtualMouse;

import java.awt.Point;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Small idle hand movements. Real users never hold the cursor perfectly still: while not actively
 * clicking, the hand drifts slightly. This nudges the cursor a short random distance when it has
 * been idle past a threshold, with a probability that ramps up the longer it stays idle.
 * <p>
 * Runs on its own scheduler and is gated by {@link Rs2AntibanSettings#useMouseV2}; nudges are short,
 * linearly interpolated polar movements emitted through the normal mouse path (so they themselves
 * count as activity and reset the idle timer, spreading fidgets out over a long idle).
 */
@Slf4j
public final class MouseV2IdleFidget {
    /** Minimum idle time before any fidget can fire. */
    public static final long IDLE_THRESHOLD_MS = 1_000L;
    /** Idle seconds after which the fidget probability starts ramping up. */
    public static final double RAMP_AFTER_SEC = 3.0;
    public static final double BASE_PROB = 0.04;
    public static final double PROB_GROWTH_PER_SEC = 0.04;
    public static final double MAX_PROB = 0.35;
    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 9;

    private static final long CHECK_INTERVAL_MS = 700L;

    private ScheduledExecutorService scheduler;
    private volatile FidgetParams cachedParams;
    private volatile String cachedSeed;

    /**
     * Per-account idle-fidget personality, derived deterministically from a seed. Formulas are
     * ported directly from the decompiled engine ({@code TBsc.TBR}).
     */
    public static final class FidgetParams {
        public final double baseAngle;   // radians; directional bias (not uniform)
        public final double angleStd;
        public final int minRadius;
        public final int maxRadius;
        public final double baseProb;
        public final double growthPerSec;
        public final double maxProb;
        public final int stepsMin;
        public final int stepsMax;
        public final long delayMin;
        public final long delayMax;

        private FidgetParams(String seed) {
            Random r = new Random((seed + ":fidget").hashCode());
            this.baseAngle = Math.PI / 2.0 + r.nextGaussian() * 0.4;
            this.angleStd = 0.25 + r.nextDouble() * 0.3;
            double d4 = r.nextDouble();
            this.minRadius = 2 + (int) (d4 * 4.0);
            this.maxRadius = 8 + (int) (d4 * 10.0);
            this.baseProb = 0.06 + r.nextDouble() * 0.14;
            this.growthPerSec = 0.01 + r.nextDouble() * 0.02;
            this.maxProb = 0.55 + r.nextDouble() * 0.25;
            int s = 2 + (int) (d4 * 1.5);
            this.stepsMin = s;
            this.stepsMax = s + 1 + r.nextInt(2);
            long l = 4L + (long) (r.nextDouble() * 4.0);
            this.delayMin = l;
            this.delayMax = l + 4L;
        }

        public static FidgetParams fromSeed(String seed) {
            return new FidgetParams(seed == null || seed.isEmpty() ? "default" : seed);
        }
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mousev2-idle-fidget");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tick() {
        try {
            if (!Rs2AntibanSettings.useMouseV2 || !loggedIn()) {
                return;
            }
            FidgetParams p = params();
            Random rng = ThreadLocalRandom.current();
            if (!shouldFidget(VirtualMouse.idleMillis(), rng, p.baseProb, p.growthPerSec, p.maxProb)) {
                return;
            }
            Point cur = Microbot.getMouse().getMousePosition();
            if (cur == null) {
                return;
            }
            int[] off = polarOffset(rng, p.baseAngle, p.angleStd, p.minRadius, p.maxRadius);
            nudge(cur.x, cur.y, off[0], off[1], rng, p);
        } catch (Exception e) {
            log.debug("idle fidget tick failed", e);
        }
    }

    private FidgetParams params() {
        String seed = resolveSeed();
        FidgetParams p = cachedParams;
        if (p != null && seed.equals(cachedSeed)) {
            return p;
        }
        p = FidgetParams.fromSeed(seed);
        cachedParams = p;
        cachedSeed = seed;
        return p;
    }

    private static String resolveSeed() {
        try {
            long hash = Microbot.getClient().getAccountHash();
            if (hash > 0) {
                return Long.toString(hash);
            }
        } catch (Exception ignored) {
            // not logged in
        }
        return "default";
    }

    /**
     * Pure decision: whether to fidget given how long the cursor has been idle. Below the idle
     * threshold never fires; the probability ramps linearly once past {@link #RAMP_AFTER_SEC}.
     */
    public static boolean shouldFidget(long idleMs, Random rng, double baseProb,
                                       double growthPerSec, double maxProb) {
        if (idleMs < IDLE_THRESHOLD_MS) {
            return false;
        }
        double idleSec = idleMs / 1000.0;
        double prob = baseProb;
        if (idleSec > RAMP_AFTER_SEC) {
            prob += growthPerSec * (idleSec - RAMP_AFTER_SEC);
        }
        prob = Math.min(prob, maxProb);
        return rng.nextDouble() < prob;
    }

    /** Pure: a random short displacement in a uniformly random direction. */
    public static int[] polarOffset(Random rng, int minRadius, int maxRadius) {
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        return polarOffsetAt(rng, angle, minRadius, maxRadius);
    }

    /**
     * Pure: a short displacement biased toward {@code baseAngle} (gaussian std {@code angleStd}),
     * matching the directional fidget of the decompiled engine (TBu3).
     */
    public static int[] polarOffset(Random rng, double baseAngle, double angleStd, int minRadius, int maxRadius) {
        double angle = baseAngle + rng.nextGaussian() * angleStd;
        return polarOffsetAt(rng, angle, minRadius, maxRadius);
    }

    private static int[] polarOffsetAt(Random rng, double angle, int minRadius, int maxRadius) {
        int radius = maxRadius <= minRadius ? minRadius : minRadius + rng.nextInt(maxRadius - minRadius + 1);
        return new int[]{
                (int) Math.round(radius * Math.cos(angle)),
                (int) Math.round(radius * Math.sin(angle))
        };
    }

    private void nudge(int startX, int startY, int dx, int dy, Random rng, FidgetParams p) {
        if (dx == 0 && dy == 0) {
            return;
        }
        int steps = p.stepsMax <= p.stepsMin ? p.stepsMin : p.stepsMin + rng.nextInt(p.stepsMax - p.stepsMin + 1);
        long delaySpan = Math.max(1L, p.delayMax - p.delayMin + 1L);
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            int x = clampX(startX + (int) Math.round(dx * f));
            int y = clampY(startY + (int) Math.round(dy * f));
            Microbot.getMouse().move(x, y);
            Global.sleep((int) (p.delayMin + rng.nextInt((int) delaySpan)));
        }
    }

    private static boolean loggedIn() {
        try {
            return Microbot.getClient() != null
                    && Microbot.getClient().getGameState() == GameState.LOGGED_IN;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clampX(int x) {
        try {
            int w = Microbot.getClient().getCanvasWidth();
            return w > 0 ? Math.max(0, Math.min(w - 1, x)) : x;
        } catch (Exception e) {
            return x;
        }
    }

    private static int clampY(int y) {
        try {
            int h = Microbot.getClient().getCanvasHeight();
            return h > 0 ? Math.max(0, Math.min(h - 1, y)) : y;
        } catch (Exception e) {
            return y;
        }
    }
}
