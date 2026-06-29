package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.api.Actor;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.LiveTarget;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.LiveTargets;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.RetargetBlend;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.ScreenPredictor;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration.MicroPauseModel;

import javax.inject.Inject;
import java.awt.Point;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * MouseV2 movement engine: moves the cursor to a target along a spline whose per-segment timing
 * obeys the two-thirds power law, shaped by a per-account seeded {@link MouseProfile} and the live
 * {@link MouseContext}, including predictive "Enhanced Clicking" via {@link #moveToPredicted} /
 * {@link #moveToLive}.
 * <p>
 * Like {@code NaturalMouse}, movement runs off the client thread (on a single-thread executor when
 * invoked from the client thread) and emits via {@link Microbot#getMouse()}.
 */
@Slf4j
public class MouseV2Engine {
    /**
     * Static singleton reference set in the constructor so the recorder frame (and other static
     * contexts) can reach the engine without a Guice injection chain. Volatile for safe
     * cross-thread visibility.
     */
    public static volatile MouseV2Engine INSTANCE;

    /**
     * Kill-switch for Tier-2 injection (noise textures, correction templates, micro-pauses).
     * {@code true} by default; set to {@code false} to force the engine to skip all recorded-data
     * overlays regardless of whether data files are present.
     */
    public static volatile boolean useRecordedTextures = true;

    private static final int SHORT_MOVE_THRESHOLD = 30;
    /** Residual (px) within which a follow leg is considered settled on the target. */
    private static final double RETARGET_THRESHOLD = 3.0;
    /** Target movement (px) beyond which an in-flight path is blended toward the new position (TBwr.TBw). */
    private static final double RETARGET_BLEND_THRESHOLD = 0.5;
    /** Target jump (px) beyond which the current leg is abandoned and rebuilt (TBwr: 40px / 1600 sq). */
    private static final double RETARGET_ABORT_SHIFT = 40.0;
    /** Modifier key for the transient urgency bump applied on a large mid-flight retarget. */
    private static final String RETARGET_URGENCY_KEY = "retarget";
    /** Safety cap on follow legs so a never-settling target cannot spin forever. */
    private static final int MAX_FOLLOW_LEGS = 12;
    /** Max predictive lead (px) so the implied cursor speed stays human. */
    private static final double MAX_LEAD_PX = 120.0;
    /** Plausible target screen drift cap while walking; running scales this by roughly 2x. */
    private static final double WALK_SCREEN_VELOCITY_CAP_PX_PER_MS = 0.6;
    /**
     * Cadence (ms) at which the background {@link TargetSampler} re-invokes the target supplier
     * (port of {@code TBwr.TBM}'s executor interval). The movement loop reads the sampled point with
     * a free atomic read every step, so the only client-thread round-trips happen on the sampler
     * thread at this cadence — independent of the (now dense) path-step rate.
     */
    private static final long TARGET_POLL_INTERVAL_MS = 50L;

    /** Script-set mouse speed: 100 = neutral, higher = more urgent/faster. */
    private static volatile int mouseSpeed = 100;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object motionLock = new Object();
    private final MouseV2IdleFidget idleFidget = new MouseV2IdleFidget();
    private volatile MouseProfile cachedProfile;
    private volatile String cachedSeed;

    @Inject
    public MouseV2Engine() {
        idleFidget.start();
        INSTANCE = this;
    }

    /**
     * Clears the cached {@link MouseProfile} so the next move re-derives it from the current
     * {@link net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration.CalibrationModel}.
     * Call this after writing new calibration data to disk and reloading the model.
     */
    public void invalidateProfileCache() {
        cachedProfile = null;
        cachedSeed = null;
    }

    /** Moves the cursor to {@code (x, y)} in canvas coordinates. Non-blocking on the client thread. */
    public void moveTo(int x, int y) {
        moveTo(x, y, 0, 0);
    }

    /**
     * Moves to {@code (x, y)}, sizing the duration's Fitts target term to the supplied clickbox
     * ({@code boxW}/{@code boxH} &gt; 0): bigger targets are acquired faster. Pass 0 for the default.
     */
    public void moveTo(int x, int y, int boxW, int boxH) {
        Point cur = Microbot.getMouse().getMousePosition();
        if (cur != null && cur.x == x && cur.y == y) {
            return;
        }
        boolean onClientThread;
        try {
            onClientThread = Microbot.getClient() != null && Microbot.getClient().isClientThread();
        } catch (Exception e) {
            onClientThread = false;
        }
        if (onClientThread) {
            executor.submit(() -> move(x, y, boxW, boxH));
        } else {
            move(x, y, boxW, boxH);
        }
    }

    private void move(int targetX, int targetY, int boxW, int boxH) {
        synchronized (motionLock) {
            try {
                Point curPoint = Microbot.getMouse().getMousePosition();
                Vec2 start = curPoint == null ? new Vec2(0, 0) : Vec2.of(curPoint);
                Vec2 end = new Vec2(targetX, targetY);
                double dist = start.distance(end);
                if (dist < 1.0) {
                    emit(targetX, targetY);
                    return;
                }

                MouseProfile profile = getProfile();
                MouseContext ctx = MouseContext.fromAntiban();
                applyTargetBox(ctx, boxW, boxH);
                Random rng = ThreadLocalRandom.current();
                ctx.applyProfileMultipliers(profile);
                ctx.applyMouseSpeed(mouseSpeed);
                ctx.applyPerMoveJitter(rng);

                if (MouseV2Debug.isEnabled()) {
                    MouseV2Debug.incMoves();
                    MouseV2Debug.setEngine("MouseV2");
                    publishProfileContext(profile, ctx);
                }

                // Short moves are a straight line with uniform timing (TBwr.TBo), not a spline.
                if (dist < SHORT_MOVE_THRESHOLD) {
                    straightMove(start, end, dist, profile, ctx);
                    emit(targetX, targetY);
                    return;
                }

                // Overshoot plan (TBlk): the main leg always misses the target by a small vector
                // (+ optional along-path extension); correction legs converge back onto it.
                OvershootPlan plan = OvershootPlan.compute(profile, ctx, dist, rng);
                if (MouseV2Debug.isEnabled()) {
                    MouseV2Debug.addOvershootLegs(plan.legs);
                }

                Vec2 dir = end.sub(start).normalized();
                Vec2 mainTarget = new Vec2(
                        end.x + plan.missX + dir.x * plan.extension,
                        end.y + plan.missY + dir.y * plan.extension);

                // Tier-2: micro-pause before the main leg (only when recorded data is present).
                if (useRecordedTextures) {
                    MicroPauseModel pauseModel = MicroPauseModel.getInstance();
                    if (pauseModel.hasData()) {
                        long pauseMs = pauseModel.sampleDelayMs(dist, rng);
                        if (pauseMs > 0) {
                            sleepUntil(System.nanoTime() + pauseMs * 1_000_000L);
                        }
                    }
                }

                long duration = DurationModel.duration(dist, profile, ctx);
                long mainTotal = (long) (duration * (plan.legs > 0 ? 0.75 : 1.0));
                walkPath(start, mainTarget, CurveStyle.BALLISTIC, mainTotal, profile, ctx, rng);

                // Correction legs: aim at the true target, splitting the reserved 25% time budget
                // across the remaining legs (TBdu.TBU: each leg gets remaining/(legsLeft)).
                double remaining = duration * 0.25;
                for (int k = 0; k < plan.legs; k++) {
                    Point now = Microbot.getMouse().getMousePosition();
                    Vec2 from = now == null ? mainTarget : Vec2.of(now);
                    double legDist = from.distance(end);
                    if (legDist < 1.0) {
                        break;
                    }
                    long legTotal = (long) Math.max(remaining / (plan.legs - k), 15.0);
                    walkPath(from, end, CurveStyle.CORRECTIVE, legTotal, profile, ctx, rng);
                    remaining -= legTotal;
                }

                // Guarantee we end exactly on target.
                emit(targetX, targetY);
            } catch (Exception e) {
                log.warn("MouseV2 move failed, falling back to direct set", e);
                emit(targetX, targetY);
            }
        }
    }

    /**
     * Short-move straight line with uniform per-step timing (port of {@code TBwr.TBo}): the duration
     * model still applies, split evenly over {@code max(5, min(dist*0.5, 30))} steps.
     */
    private void straightMove(Vec2 start, Vec2 end, double dist, MouseProfile profile, MouseContext ctx) {
        long total = DurationModel.duration(dist, profile, ctx);
        int steps = Math.max(5, Math.min((int) (dist * 0.5), 30));
        long stepMs = Math.max(1L, total / steps);
        if (MouseV2Debug.isEnabled()) {
            List<Vec2> line = new java.util.ArrayList<>(2);
            line.add(start);
            line.add(end);
            MouseV2Debug.setPlannedPath(toPoints(line), CurveStyle.CORRECTIVE.name(), total, dist);
        }
        long startNs = System.nanoTime();
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec2 p = start.lerp(end, t);
            sleepUntil(startNs + stepMs * i * 1_000_000L);
            Point rp = p.toRoundedPoint();
            emit(clampX(rp.x), clampY(rp.y));
        }
    }

    private void walkPath(Vec2 from, Vec2 to, CurveStyle style, long totalMs,
                          MouseProfile profile, MouseContext ctx, Random rng) {
        List<Vec2> path = SplineBuilder.build(from, to, style, profile, ctx, rng);
        long[] times = PowerLawTimer.timings(path, totalMs, profile, ctx, rng);
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.setPlannedPath(toPoints(path), style.name(), totalMs, from.distance(to));
        }
        // path[0] is the current position; emit subsequent points with the per-segment delays.
        long startNs = System.nanoTime();
        long elapsedMs = 0L;
        for (int i = 1; i < path.size(); i++) {
            long delay = i - 1 < times.length ? times[i - 1] : 1L;
            elapsedMs += Math.max(0L, delay);
            sleepUntil(startNs + elapsedMs * 1_000_000L);
            Point p = path.get(i).toRoundedPoint();
            emit(clampX(p.x), clampY(p.y));
        }
    }

    /**
     * Follows a live, possibly moving target: walks a spline toward the target's current position
     * and, each step, re-reads the target and blends any shift into the remaining path (no snap).
     *
     * @param target supplies the target's current canvas point each call, or {@code null} if gone
     * @param cancel optional abort signal checked between steps; may be {@code null}
     */
    public void moveToLive(Supplier<Point> target, BooleanSupplier cancel) {
        moveToLive(target, null, cancel, null);
    }

    private void moveToLive(Supplier<Point> target, Supplier<Point> trueTarget,
                            BooleanSupplier cancel, MouseContext ctx) {
        boolean onClientThread;
        try {
            onClientThread = Microbot.getClient() != null && Microbot.getClient().isClientThread();
        } catch (Exception e) {
            onClientThread = false;
        }
        if (onClientThread) {
            executor.submit(() -> doMoveLive(target, trueTarget, cancel, ctx));
        } else {
            doMoveLive(target, trueTarget, cancel, ctx);
        }
    }

    /**
     * Predictive "Enhanced Clicking": aims at where the target will be by the time the cursor
     * arrives, then follows it. Wraps the target in a {@link ScreenPredictor} (lead estimated from
     * the initial travel time) and drives {@link #moveToLive}. Aborts cleanly if the target
     * vanishes for several consecutive samples.
     */
    public void moveToPredicted(LiveTarget target) {
        if (target == null) {
            return;
        }
        Point initial = target.get();
        if (initial == null) {
            return;
        }
        Point cur = Microbot.getMouse().getMousePosition();
        double dist = cur == null ? 0.0 : Math.hypot(initial.x - cur.x, initial.y - cur.y);
        MouseProfile profile = getProfile();
        MouseContext ctx = MouseContext.fromAntiban();
        ctx.applyProfileMultipliers(profile);
        ctx.applyMouseSpeed(mouseSpeed);
        ctx.applyPerMoveJitter(ThreadLocalRandom.current());
        long initialDurationMs = DurationModel.duration(Math.max(dist, 1.0), profile, ctx);
        double cursorSpeedPxPerMs = cursorSpeedPxPerMs(dist, initialDurationMs);

        ScreenPredictor predictor = new ScreenPredictor(
                target,
                MAX_LEAD_PX,
                ScreenPredictor.DEFAULT_SAMPLE_INTERVAL_MS,
                screenVelocityCapPxPerMs());
        predictor.addSample(System.currentTimeMillis(), initial);
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.incPredictiveMoves();
        }
        Supplier<Point> predicted = () -> {
            Point raw = predictor.sampleNow();
            if (raw == null) {
                if (MouseV2Debug.isEnabled()) {
                    MouseV2Debug.setPrediction(null, null, 0L,
                            predictor.consecutiveMisses(), predictor.sampleSnapshot());
                }
                return null;
            }
            Point mouse = Microbot.getMouse().getMousePosition();
            double remainingDist = mouse == null
                    ? 0.0
                    : Math.hypot(raw.x - mouse.x, raw.y - mouse.y);
            long leadMs = leadMsForRemainingDistance(remainingDist, cursorSpeedPxPerMs);
            Point predictedPoint = predictor.predict(leadMs);
            if (MouseV2Debug.isEnabled()) {
                MouseV2Debug.setPrediction(raw, predictedPoint, leadMs,
                        predictor.consecutiveMisses(), predictor.sampleSnapshot());
            }
            return predictedPoint;
        };
        BooleanSupplier cancel = () -> {
            boolean abort = predictor.consecutiveMisses() >= 3;
            if (abort && MouseV2Debug.isEnabled()) {
                MouseV2Debug.incCancels();
            }
            return abort;
        };
        moveToLive(predicted, target, cancel, ctx);
    }

    /**
     * Physics-predicted follow for an actor: aims at the leaded, in-hull pick point (TBoe.TBz
     * semantics — pick persists until the hull moves past it, then re-picks with a fresh lead).
     * Settles on the in-hull rawPick (falling back to the unleaded actor-centre projection).
     * Preferred over the empirical predictor when on-screen drift is player-movement driven.
     */
    public void moveToActorPredicted(Actor actor) {
        if (actor == null) {
            return;
        }
        MouseContext ctx = MouseContext.fromAntiban();
        MouseProfile profile = getProfile();
        ctx.applyProfileMultipliers(profile);
        ctx.applyMouseSpeed(mouseSpeed);
        ctx.applyPerMoveJitter(ThreadLocalRandom.current());
        java.awt.Rectangle box = Rs2UiHelper.getActorClickbox(actor);
        if (box != null) {
            applyTargetBox(ctx, box.width, box.height);
        }
        // hull supplier: raw client API — hop is performed inside predictedWithHullRepick
        LiveTargets.PredictedHullTarget pht = LiveTargets.predictedWithHullRepick(
                actor::getConvexHull,
                actor::getLocalLocation,
                () -> actor.getLogicalHeight() / 2,
                profile, ctx);
        followModel(pht.modelTarget, pht.settleTarget, ctx);
    }

    /** Physics-predicted follow for a game/tile object (see {@link #moveToActorPredicted}). */
    public void moveToObjectPredicted(TileObject object) {
        if (object == null) {
            return;
        }
        MouseContext ctx = MouseContext.fromAntiban();
        MouseProfile profile = getProfile();
        ctx.applyProfileMultipliers(profile);
        ctx.applyMouseSpeed(mouseSpeed);
        ctx.applyPerMoveJitter(ThreadLocalRandom.current());
        java.awt.Rectangle box = Rs2UiHelper.getObjectClickbox(object);
        if (box != null) {
            applyTargetBox(ctx, box.width, box.height);
        }
        // hull supplier: raw client API — hop is performed inside predictedWithHullRepick
        LiveTargets.PredictedHullTarget pht = LiveTargets.predictedWithHullRepick(
                object::getClickbox,
                object::getLocalLocation,
                () -> 0,
                profile, ctx);
        followModel(pht.modelTarget, pht.settleTarget, ctx);
    }

    /**
     * Follows a model-predicted target (already lead-adjusted analytically, so no
     * {@link ScreenPredictor} is needed) while settling on the raw, unleaded projection.
     */
    private void followModel(LiveTarget modelTarget, LiveTarget rawTarget, MouseContext ctx) {
        Point initial = modelTarget.get();
        if (initial == null) {
            return;
        }
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.incPredictiveMoves();
        }
        int[] consecutiveNulls = {0};
        Supplier<Point> predicted = () -> {
            Point p = modelTarget.get();
            if (p == null) {
                consecutiveNulls[0]++;
            } else {
                consecutiveNulls[0] = 0;
            }
            return p;
        };
        BooleanSupplier cancel = () -> consecutiveNulls[0] >= 3;
        moveToLive(predicted, rawTarget, cancel, ctx);
    }

    private void doMoveLive(Supplier<Point> target, Supplier<Point> trueTarget,
                            BooleanSupplier cancel, MouseContext suppliedCtx) {
        // motionLock is held for the full duration of the follow, including sleeps. This is
        // intentional: callers expect moveTo/moveToLive to be synchronous (the cursor is at the
        // target when the call returns), so concurrent move requests must queue, not interleave.
        // This matches the legacy NaturalMouse contract (fully synchronized). A background
        // TargetSampler re-reads the (possibly client-thread-bound) target supplier at
        // TARGET_POLL_INTERVAL_MS; the leg loop and walkPathLive read its latest point via a free
        // atomic read, so dense paths never block the walk thread on a client-thread round-trip.
        MouseProfile profile = getProfile();
        MouseContext ctx = suppliedCtx == null ? MouseContext.fromAntiban() : suppliedCtx;
        synchronized (motionLock) {
            TargetSampler sampler = new TargetSampler(target, TARGET_POLL_INTERVAL_MS);
            sampler.start();
            try {
                Random rng = ThreadLocalRandom.current();
                if (suppliedCtx == null) {
                    ctx.applyProfileMultipliers(profile);
                    ctx.applyMouseSpeed(mouseSpeed);
                    ctx.applyPerMoveJitter(rng);
                }
                // settle position: the true (unleaded) target when supplied, else the sampled one
                Supplier<Point> settleTarget = trueTarget == null ? sampler::latest : trueTarget;

                for (int leg = 0; leg < MAX_FOLLOW_LEGS; leg++) {
                    if (cancel != null && cancel.getAsBoolean()) {
                        return;
                    }
                    Point curPoint = Microbot.getMouse().getMousePosition();
                    Vec2 start = curPoint == null ? new Vec2(0, 0) : Vec2.of(curPoint);
                    Point tp = sampler.latest();
                    if (tp == null) {
                        return; // target despawned / off-screen
                    }
                    Vec2 end = Vec2.of(tp);
                    double dist = start.distance(end);
                    if (dist < 1.0) {
                        emit(tp.x, tp.y);
                        return;
                    }
                    CurveStyle style = dist <= SHORT_MOVE_THRESHOLD ? CurveStyle.CORRECTIVE : CurveStyle.BALLISTIC;
                    long total = DurationModel.duration(dist, profile, ctx);
                    walkPathLive(start, end, sampler, style, total, profile, ctx, rng, cancel);

                    // Has the target settled close to the cursor? If so we are done.
                    Point now = Microbot.getMouse().getMousePosition();
                    Point settled = settleTarget.get();
                    if (settled == null) {
                        return;
                    }
                    double residual = (now == null ? end : Vec2.of(now)).distance(Vec2.of(settled));
                    if (residual <= RETARGET_THRESHOLD) {
                        emit(settled.x, settled.y);
                        return;
                    }
                }
                Point finalTarget = settleTarget.get();
                if (finalTarget != null) {
                    emit(finalTarget.x, finalTarget.y);
                }
            } catch (Exception e) {
                log.warn("MouseV2 live move failed", e);
            } finally {
                sampler.stop();
                // Clear any transient retarget urgency bump pushed by walkPathLive (TBwr.TBh finally).
                ctx.popModifier("urgency", RETARGET_URGENCY_KEY);
            }
        }
    }

    private void walkPathLive(Vec2 from, Vec2 to, TargetSampler sampler, CurveStyle style,
                              long totalMs, MouseProfile profile, MouseContext ctx, Random rng,
                              BooleanSupplier cancel) {
        List<Vec2> path = SplineBuilder.build(from, to, style, profile, ctx, rng);
        long[] times = PowerLawTimer.timings(path, totalMs, profile, ctx, rng);
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.setPlannedPath(toPoints(path), style.name(), totalMs, from.distance(to));
        }
        long startNs = System.nanoTime();
        long elapsedMs = 0L;
        boolean blendedOnce = false;
        for (int i = 1; i < path.size(); i++) {
            if (cancel != null && cancel.getAsBoolean()) {
                return;
            }
            long delay = i - 1 < times.length ? times[i - 1] : 1L;
            elapsedMs += Math.max(0L, delay);
            sleepUntil(startNs + elapsedMs * 1_000_000L);

            // Free atomic read of the latest sampled target every step (no client-thread hop;
            // the sampler thread is the sole caller of the supplier, at TARGET_POLL_INTERVAL_MS).
            Point tp = sampler.latest();
            if (tp != null) {
                Vec2 newEnd = new Vec2(tp.x, tp.y);
                Vec2 curEnd = path.get(path.size() - 1);
                double shift = newEnd.distance(curEnd);
                if (shift > RETARGET_ABORT_SHIFT) {
                    // Large jump: abandon this leg, bump urgency, let doMoveLive rebuild from the
                    // new cursor/target (TBwr.TBh: urgency += 0.15 + 0.2*rand on retarget).
                    ctx.pushModifier("urgency", RETARGET_URGENCY_KEY,
                            0.15 + rng.nextDouble() * 0.2);
                    return;
                }
                if (shift > RETARGET_BLEND_THRESHOLD) {
                    RetargetBlend.blend(path, i, newEnd.sub(curEnd));
                    // Geometry shifted: re-time the remaining segments off the new curvature.
                    // On the first blend of a leg, also shrink the remaining budget slightly
                    // (TBwr.TBw: remaining *= 0.85 + 0.07*rand).
                    long[] retimed = PowerLawTimer.timings(path, totalMs, profile, ctx, rng);
                    double shrink = blendedOnce ? 1.0 : (0.85 + rng.nextDouble() * 0.07);
                    for (int j = i; j < times.length && j < retimed.length; j++) {
                        times[j] = Math.max(1L, Math.round(retimed[j] * shrink));
                    }
                    blendedOnce = true;
                }
            }
            Point p = path.get(i).toRoundedPoint();
            emit(clampX(p.x), clampY(p.y));
        }
    }

    private void emit(int x, int y) {
        Microbot.getMouse().move(x, y);
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.addTrailPoint(new Point(x, y));
        }
    }

    private static List<Point> toPoints(List<Vec2> path) {
        List<Point> out = new java.util.ArrayList<>(path.size());
        for (Vec2 v : path) {
            out.add(v.toRoundedPoint());
        }
        return out;
    }

    private static void sleepUntil(long deadlineNs) {
        while (!Thread.currentThread().isInterrupted()) {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0L) {
                return;
            }
            if (remainingNs > 2_000_000L) {
                LockSupport.parkNanos(Math.min(remainingNs - 1_000_000L, 2_000_000L));
            } else if (remainingNs > 250_000L) {
                LockSupport.parkNanos(remainingNs);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static void publishProfileContext(MouseProfile profile, MouseContext ctx) {
        MouseSpeedProfile speedProfile = MouseSpeedProfile.fromSpeed(ctx.getMouseSpeed());
        MouseV2Debug.setProfile(String.format(
                "seed=%s over=%.2f bias=%+.2f curve=%.2f peak=%.2f",
                profile.getSeed(), profile.overshootTendency, profile.curveBias,
                profile.curveScale, profile.velocityPeak));
        MouseV2Debug.setContext(String.format(
                "speed=%d %s urg=%.2f foc=%.2f fat=%.2f",
                ctx.getMouseSpeed(), speedProfile.displayName(),
                ctx.getUrgency(), ctx.getFocus(), ctx.getFatigue()));
    }

    private MouseProfile getProfile() {
        String seed = resolveSeed();
        MouseProfile p = cachedProfile;
        if (p != null && seed.equals(cachedSeed)) {
            return p;
        }
        p = MouseProfile.fromSeed(seed);
        cachedProfile = p;
        cachedSeed = seed;
        return p;
    }

    /** Stable per-account seed. Uses the RuneLite account hash; falls back to a fixed default. */
    private String resolveSeed() {
        try {
            long hash = Microbot.getClient().getAccountHash();
            if (hash > 0) {
                return Long.toString(hash);
            }
        } catch (Exception ignored) {
            // not in a client context
        }
        return "default";
    }

    private int clampX(int x) {
        try {
            int w = Microbot.getClient().getCanvasWidth();
            return w > 0 ? Math.max(0, Math.min(w - 1, x)) : x;
        } catch (Exception e) {
            return x;
        }
    }

    private int clampY(int y) {
        try {
            int h = Microbot.getClient().getCanvasHeight();
            return h > 0 ? Math.max(0, Math.min(h - 1, y)) : y;
        } catch (Exception e) {
            return y;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static long leadMsForRemainingDistance(double remainingDist, double cursorSpeedPxPerMs) {
        if (remainingDist <= RETARGET_THRESHOLD || cursorSpeedPxPerMs <= 1e-9) {
            return 0L;
        }
        return Math.max(0L, Math.round(remainingDist / cursorSpeedPxPerMs));
    }

    private static double cursorSpeedPxPerMs(double distancePx, long durationMs) {
        if (distancePx <= 0.0 || durationMs <= 0L) {
            return 0.05;
        }
        return Math.max(0.05, distancePx / durationMs);
    }

    private static double screenVelocityCapPxPerMs() {
        boolean running;
        try {
            running = Rs2Player.isRunEnabled();
        } catch (Exception e) {
            running = false;
        }
        return WALK_SCREEN_VELOCITY_CAP_PX_PER_MS * (running ? 2.0 : 1.0);
    }

    /** Largest box dimension (px) used for settle jitter, so a huge clickbox doesn't scatter wildly. */
    private static final int MAX_SETTLE_BOX = 30;

    /**
     * Pure: a jittered point inside a box centred on {@code (cx, cy)}. The offset is gaussian with
     * std = box/4, clamped to +/- box/2, so the result stays within the box but clusters near centre.
     */
    public static Point jitterInBox(Random rng, int cx, int cy, int boxW, int boxH) {
        double jx = clamp(rng.nextGaussian() * boxW / 4.0, -boxW / 2.0, boxW / 2.0);
        double jy = clamp(rng.nextGaussian() * boxH / 4.0, -boxH / 2.0, boxH / 2.0);
        return new Point((int) Math.round(cx + jx), (int) Math.round(cy + jy));
    }

    /**
     * Settles onto a target with a few jittery micro-corrections instead of landing on an exact
     * pixel, and returns the final point to click. Real clicks rarely hit a precise coordinate; the
     * cursor wobbles into the clickbox. Box dimensions are capped by {@link #MAX_SETTLE_BOX}.
     */
    public Point settleInBox(int cx, int cy, int boxW, int boxH) {
        int w = Math.max(2, Math.min(boxW, MAX_SETTLE_BOX));
        int h = Math.max(2, Math.min(boxH, MAX_SETTLE_BOX));
        Random rng = ThreadLocalRandom.current();
        MouseSpeedProfile speedProfile = MouseSpeedProfile.fromSpeed(mouseSpeed);
        int settles = speedProfile.settleCorrections(rng);
        Point last = new Point(cx, cy);
        for (int k = 0; k < settles; k++) {
            last = jitterInBox(rng, cx, cy, w, h);
            emit(clampX(last.x), clampY(last.y));
            int delay = speedProfile.settleDelayMs(rng);
            if (delay > 0) {
                sleepUntil(System.nanoTime() + delay * 1_000_000L);
            }
        }
        if (settles == 0) {
            emit(clampX(last.x), clampY(last.y));
        }
        return last;
    }

    /**
     * Mouse-button press-hold duration (ms): {@code max(0, round(gaussian(mean=4, std=6)))}, a
     * faithful port of the upstream {@code TBqr.TBp(4.0, 6.0)} used in its click dispatch. Short by
     * design — most holds are a few ms, never negative.
     */
    public static long pressHoldMs(Random rng) {
        return Math.max(0L, Math.round(4.0 + rng.nextGaussian() * 6.0));
    }

    /**
     * Sets the mouse speed ({@code Mouse.setSpeed}). 100 is neutral; higher values add
     * URGENCY (shorter, snappier moves), lower values calm it. Clamped to [0, 400]. Applied to every
     * subsequent move via the {@code "api-mouse-speed"} urgency modifier.
     */
    public static void setMouseSpeed(int speed) {
        mouseSpeed = Math.max(0, Math.min(400, speed));
    }

    public static int getMouseSpeed() {
        return mouseSpeed;
    }

    /** Sizes the context's Fitts target box to a real clickbox when known (positive dimensions). */
    private static void applyTargetBox(MouseContext ctx, int boxW, int boxH) {
        if (boxW > 0) {
            ctx.setBoxWidth(Math.max(1, boxW));
        }
        if (boxH > 0) {
            ctx.setBoxHeight(Math.max(1, boxH));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        idleFidget.stop();
    }

    /**
     * Background poller that re-invokes a target supplier at a fixed cadence and publishes the
     * latest point through an {@link AtomicReference}. Port of {@code TBwr.TBM}'s single-thread
     * executor: it moves the (potentially client-thread-blocking) supplier call off the movement
     * loop, so each path step reads the latest target with a free atomic read instead of a
     * client-thread round-trip. One sampler lives for the duration of a single follow.
     */
    private static final class TargetSampler {
        private final Supplier<Point> supplier;
        private final long cadenceMs;
        private final AtomicReference<Point> latest = new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile Thread thread;

        TargetSampler(Supplier<Point> supplier, long cadenceMs) {
            this.supplier = supplier;
            this.cadenceMs = cadenceMs;
        }

        /** Primes the first value synchronously, then starts the daemon poller. */
        void start() {
            try {
                latest.set(supplier.get());
            } catch (Exception ignored) {
                // leave null; the follow loop treats a null first read as "target gone"
            }
            running.set(true);
            Thread t = new Thread(this::run, "MouseV2-target-sampler");
            t.setDaemon(true);
            thread = t;
            t.start();
        }

        private void run() {
            while (running.get()) {
                try {
                    Thread.sleep(cadenceMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) {
                    return;
                }
                try {
                    latest.set(supplier.get());
                } catch (Exception ignored) {
                    // a transient supplier failure shouldn't kill the sampler; keep the last value
                }
            }
        }

        /** The most recently sampled target point, or {@code null} if none/gone. Free to call. */
        Point latest() {
            return latest.get();
        }

        void stop() {
            running.set(false);
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
