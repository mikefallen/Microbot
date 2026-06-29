package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.DurationModel;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseContext;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseProfile;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.ShapeAimPicker;
import net.runelite.client.plugins.microbot.util.walker.Rs2MiniMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Factory for {@link LiveTarget}s over the common clickable kinds. Each supplier recomputes a stable
 * tracking point on demand; expensive full clickboxes are reserved for final click-point selection.
 * <p>
 * Provides the per-kind target sources used by predictive Enhanced Clicking (entities, tiles,
 * ground items, minimap).
 */
public final class LiveTargets {
    private LiveTargets() {
    }

    /** Follows an actor (NPC or player) by its current clickbox. */
    public static LiveTarget forActor(Actor actor) {
        return () -> {
            if (actor == null) {
                return null;
            }
            net.runelite.api.Point p = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                if (client == null || actor.getLocalLocation() == null) {
                    return null;
                }
                int plane = actor.getWorldLocation() == null
                        ? client.getPlane()
                        : actor.getWorldLocation().getPlane();
                int zOffset = actor.getLogicalHeight() / 2;
                return zOffset == 0
                        ? Perspective.localToCanvas(client, actor.getLocalLocation(), plane)
                        : Perspective.localToCanvas(client, actor.getLocalLocation(), plane, zOffset);
            }).orElse(null);
            return p == null ? null : new Point(p.getX(), p.getY());
        };
    }

    /** Follows a game/tile object by its current clickbox. */
    public static LiveTarget forTileObject(TileObject object) {
        return () -> {
            if (object == null) {
                return null;
            }
            net.runelite.api.Point p = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                if (client == null || object.getLocalLocation() == null) {
                    return null;
                }
                int plane = object.getWorldLocation() == null
                        ? client.getPlane()
                        : object.getWorldLocation().getPlane();
                return Perspective.localToCanvas(client, object.getLocalLocation(), plane);
            }).orElse(null);
            return p == null ? null : new Point(p.getX(), p.getY());
        };
    }

    /** Follows a scene tile by its projected centre box. */
    public static LiveTarget forTile(Tile tile) {
        return () -> {
            if (tile == null) {
                return null;
            }
            net.runelite.api.Point p = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                if (client == null) {
                    return null;
                }
                return Perspective.localToCanvas(client, tile.getLocalLocation(), client.getPlane());
            }).orElse(null);
            return p == null ? null : new Point(p.getX(), p.getY());
        };
    }

    /** Follows a world position projected onto the minimap (for minimap walking clicks). */
    public static LiveTarget forMinimap(WorldPoint worldPoint) {
        return () -> {
            if (worldPoint == null) {
                return null;
            }
            net.runelite.api.Point p = Rs2MiniMap.worldToMinimap(worldPoint);
            return p == null ? null : new Point(p.getX(), p.getY());
        };
    }

    /**
     * Physics-predicted target for an actor: aims at where the actor will appear once the player has
     * walked toward its destination for the cursor's estimated travel time (see
     * {@link WalkPathPredictor}). Falls back to the unleaded projection when the player is stationary.
     */
    public static LiveTarget predictedForActor(Actor actor, MouseProfile profile, MouseContext ctx) {
        // Project at half the actor's logical height so the aim lands on the body, not the feet
        // tile (matches TBhx.TBu). Self-motion is tracked by re-reading getLocalLocation() each frame.
        return predicted(
                () -> actor == null ? null : actor.getLocalLocation(),
                () -> actor == null ? 0 : actor.getLogicalHeight() / 2,
                profile, ctx);
    }

    /** Physics-predicted target for a game/tile object (see {@link #predictedForActor}). */
    public static LiveTarget predictedForTileObject(TileObject object, MouseProfile profile, MouseContext ctx) {
        return predicted(() -> object == null ? null : object.getLocalLocation(), () -> 0, profile, ctx);
    }

    private static LiveTarget predicted(Supplier<LocalPoint> localSupplier, IntSupplier zOffsetSupplier,
                                        MouseProfile profile, MouseContext ctx) {
        return () -> Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            LocalPoint local = localSupplier.get();
            if (client == null || local == null) {
                return null;
            }
            int zOffset = zOffsetSupplier.getAsInt();
            // current (unleaded) screen position, to estimate cursor travel time
            net.runelite.api.Point cur = zOffset == 0
                    ? Perspective.localToCanvas(client, local, client.getPlane())
                    : Perspective.localToCanvas(client, local, client.getPlane(), zOffset);
            java.awt.Point mouse = Microbot.getMouse().getMousePosition();
            double dist = (cur == null || mouse == null)
                    ? 0.0
                    : Math.hypot(mouse.x - cur.getX(), mouse.y - cur.getY());
            // travel-time estimate with the same +/-15% jitter the upstream implementation applies per frame
            double travelMs = DurationModel.duration(Math.max(dist, 1.0), profile, ctx)
                    * (0.85 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.30);
            return WalkPathPredictor.leadCanvasPoint(local, travelMs, WalkPathPredictor.DEFAULT_COMMIT, zOffset);
        }).orElse(null);
    }

    public static boolean isInsideClickbox(Rectangle clickbox, Point point) {
        return clickbox != null && point != null && clickbox.contains(point);
    }

    /**
     * Returns the canvas viewport as a {@link Rectangle}. Must be called from within a
     * client-thread hop (i.e. inside {@code runOnClientThreadOptional}).
     * Returns {@code null} on failure; {@link ShapeAimPicker#pick} accepts a null viewport.
     */
    private static Rectangle canvasViewport() {
        try {
            Client client = Microbot.getClient();
            if (client == null) {
                return null;
            }
            int w = client.getCanvasWidth();
            int h = client.getCanvasHeight();
            return (w > 0 && h > 0) ? new Rectangle(0, 0, w, h) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Wraps a live-target supplier with hull-exit re-pick: keeps the last picked aim point and only
     * re-picks via {@link ShapeAimPicker} when the current aim point is no longer inside the live
     * hull. Falls back to the raw center projection when no hull is available.
     * <p>
     * Based on the upstream Enhanced Clicking per-frame re-pick check (TBoe.TBz).
     * <p>
     * <b>Important:</b> {@code hullSupplier} must be a RAW client-API call such as
     * {@code actor::getConvexHull} — the client-thread hop is performed inside this wrapper.
     * Do NOT pass an Rs2UiHelper method that already hops to the client thread, as that would
     * cause a double-hop (blocked client-thread invocation nested inside another).
     *
     * @param hullSupplier supplies the current hull shape (raw client API, hop performed here)
     * @param fallback     raw center supplier used when shape pick fails
     * @return a {@link LiveTarget} that re-picks when the aim point leaves the hull
     */
    public static LiveTarget withHullExitRepick(Supplier<Shape> hullSupplier, LiveTarget fallback) {
        AtomicReference<Point> aimPoint = new AtomicReference<>();
        // false = the stored aimPoint came from a fallback center, not a real hull pick.
        // A fallback-derived point must NOT suppress the first real pick.
        AtomicBoolean fromRealPick = new AtomicBoolean(false);

        return () -> {
            // One client-thread hop: fetch hull + viewport together to avoid a double-hop.
            Object[] results = new Object[2]; // [0]=Shape hull, [1]=Rectangle viewport
            try {
                Microbot.getClientThread().runOnClientThreadOptional(() -> {
                    results[0] = hullSupplier.get();
                    results[1] = canvasViewport();
                    return null;
                });
            } catch (Exception ignored) {
                // hull not available this frame
            }
            Shape hull = (Shape) results[0];
            Rectangle viewport = (Rectangle) results[1];

            Point current = aimPoint.get();
            if (hull != null) {
                // Re-pick if:
                //  - no real pick is cached yet (fromRealPick=false means we only have a fallback), OR
                //  - the cached point is no longer inside the hull.
                boolean needsPick = !fromRealPick.get()
                        || current == null
                        || !hull.contains(current);
                if (needsPick) {
                    Random rng = ThreadLocalRandom.current();
                    Point picked = ShapeAimPicker.pick(hull, viewport, null, rng);
                    if (picked != null) {
                        aimPoint.set(picked);
                        fromRealPick.set(true);
                        return picked;
                    }
                    // Pick failed — do NOT set fromRealPick; fallback below without caching.
                }
                if (fromRealPick.get() && current != null) {
                    return current;
                }
            }
            // No hull or pick failed: delegate to fallback supplier, marking as NOT a real pick
            // so a future non-null hull still triggers a pick.
            Point raw = fallback != null ? fallback.get() : null;
            aimPoint.set(raw);
            fromRealPick.set(false);
            return raw;
        };
    }

    /**
     * Pair of {@link LiveTarget}s produced by {@link #predictedWithHullRepick}: a model target that
     * the mouse follows (leaded / predicted) and a settle target used for the final landing point.
     * Both views share the same {@link HullRepickState} so they see consistent rawPick/tracked values.
     */
    public static final class PredictedHullTarget {
        /** The leaded follow target: polled by the follow loop's TargetSampler. */
        public final LiveTarget modelTarget;
        /**
         * The settle target: returns the last in-hull rawPick (falling back to the unleaded center
         * projection when no hull pick has been made yet). Passed as {@code trueTarget} to
         * {@code doMoveLive}.
         */
        public final LiveTarget settleTarget;

        PredictedHullTarget(LiveTarget modelTarget, LiveTarget settleTarget) {
            this.modelTarget = modelTarget;
            this.settleTarget = settleTarget;
        }
    }

    /**
     * Produces a predicted follow target that persists the leaded projection until
     * the target's hull has moved enough that the cached aim point exits it, then re-picks.
     * <p>
     * Port of TBoe.TBz (BeforeRender per-frame callback) adapted for Microbot's 50ms sampler cadence.
     * <p>
     * <b>Re-pick condition (per sampler poll):</b> hull != null AND (no real pick cached, OR both
     * the tracked point AND the rawPick are null/outside the hull — the leaded tracked point
     * normally leads outside the hull, so its staleness alone must not re-trigger). On re-pick:
     * pick an in-hull point {@code p};
     * compute travelMs = DurationModel.duration(dist(mouse, p)) * rand(0.85, 1.15); leaded =
     * WalkPathPredictor.leadCanvasPoint(local, travelMs, 0.5, zOffset); tracked = leaded != null ?
     * leaded : p. Between re-picks, tracked is returned UNCHANGED (lead not recomputed — fixed
     * cadence matching the upstream implementation).
     * <p>
     * <b>Important:</b> {@code hullSupplier} and {@code localSupplier} must be RAW client-API calls.
     * Do NOT pass Rs2UiHelper wrappers that already hop to the client thread.
     *
     * @param hullSupplier   raw hull/clickbox supplier (e.g. {@code actor::getConvexHull})
     * @param localSupplier  raw local-position supplier (e.g. {@code actor::getLocalLocation})
     * @param zOffsetSupplier vertical canvas offset (half logical height for actors; 0 for objects)
     * @param profile        mouse profile (for DurationModel)
     * @param ctx            mouse context (for DurationModel)
     * @return holder with {@code modelTarget} (leaded follow) and {@code settleTarget} (in-hull raw)
     */
    public static PredictedHullTarget predictedWithHullRepick(
            Supplier<Shape> hullSupplier,
            Supplier<LocalPoint> localSupplier,
            IntSupplier zOffsetSupplier,
            MouseProfile profile,
            MouseContext ctx) {

        HullRepickState state = new HullRepickState();

        // The model target: polled by TargetSampler every 50ms.
        LiveTarget modelTarget = () -> {
            // One client-thread hop: fetch everything the state machine needs.
            HullRepickState.StepResult[] resultHolder = new HullRepickState.StepResult[1];

            Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                Shape hull = hullSupplier.get();
                Rectangle viewport = canvasViewport();
                LocalPoint local = localSupplier.get();

                if (client == null || local == null) {
                    resultHolder[0] = null;
                    return null;
                }

                int zOffset = zOffsetSupplier.getAsInt();
                int plane = client.getPlane();

                // Fallback: unleaded center projection (not cached as a real pick)
                Supplier<Point> fallbackFn = () -> {
                    net.runelite.api.Point cv = zOffset == 0
                            ? Perspective.localToCanvas(client, local, plane)
                            : Perspective.localToCanvas(client, local, plane, zOffset);
                    return cv == null ? null : new Point(cv.getX(), cv.getY());
                };

                // pickFn: centroid-biased in-hull pick (called only on re-pick)
                Random rng = ThreadLocalRandom.current();
                Supplier<Point> pickFn = () ->
                        ShapeAimPicker.pick(hull, viewport, null, rng);

                // leadFn: given the picked aim point, compute travelMs and the leaded projection.
                // Receives the picked point so dist estimation uses the actual pick, not a cached one.
                Function<Point, Point> leadFn = (pick) -> {
                    java.awt.Point mousePos = Microbot.getMouse().getMousePosition();
                    double dist = (mousePos == null)
                            ? 0.0
                            : Math.hypot(mousePos.x - pick.x, mousePos.y - pick.y);
                    double travelMs = DurationModel.duration(Math.max(dist, 1.0), profile, ctx)
                            * (0.85 + ThreadLocalRandom.current().nextDouble() * 0.30);
                    return WalkPathPredictor.leadCanvasPoint(local, travelMs,
                            WalkPathPredictor.DEFAULT_COMMIT, zOffset);
                };

                resultHolder[0] = state.step(hull, pickFn, leadFn, fallbackFn);
                return null;
            });

            HullRepickState.StepResult result = resultHolder[0];
            if (result == null) {
                return null;
            }

            if (MouseV2Debug.isEnabled()) {
                MouseV2Debug.setPrediction(result.rawPick, result.tracked, 0L, 0, null);
            }

            return result.tracked;
        };

        // The settle target: returns the last in-hull rawPick, falling back to unleaded center.
        // Runs on the motion thread; a client-thread hop is still needed for the projection fallback.
        LiveTarget settleTarget = () -> {
            Point raw = state.getRawPick();
            if (raw != null) {
                return raw;
            }
            // No real pick yet — return unleaded center projection as fallback
            return Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                LocalPoint local = localSupplier.get();
                if (client == null || local == null) {
                    return null;
                }
                int zOffset = zOffsetSupplier.getAsInt();
                int plane = client.getPlane();
                net.runelite.api.Point cv = zOffset == 0
                        ? Perspective.localToCanvas(client, local, plane)
                        : Perspective.localToCanvas(client, local, plane, zOffset);
                return cv == null ? null : new Point(cv.getX(), cv.getY());
            }).orElse(null);
        };

        return new PredictedHullTarget(modelTarget, settleTarget);
    }
}
