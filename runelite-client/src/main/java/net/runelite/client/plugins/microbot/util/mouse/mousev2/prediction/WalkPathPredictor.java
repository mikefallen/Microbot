package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.Microbot;

import java.awt.Point;

/**
 * Physics-based screen-position predictor for a target that drifts because the <em>player</em> is
 * walking. Most on-screen target motion while travelling comes from the scene shifting under a
 * moving player, and that motion is fully known: the player's destination tile and run state are
 * exposed by the client, movement is quantised to one (walk) or two (run) tiles per 600ms tick,
 * and a tile is {@link Perspective#LOCAL_TILE_SIZE} local units.
 * <p>
 * Given the cursor's estimated travel time, this advances the player along its destination path,
 * shifts the target by the negative of that displacement (the scene moves opposite the player),
 * and reprojects. Unlike sample-based extrapolation it has no lag, no noise, and naturally stops
 * leading once the player reaches its destination. The lead is scaled by a commit factor (&lt;1) so
 * a mispredicted path only ever half-commits.
 */
public final class WalkPathPredictor {
    /** Local units the player covers per game tick when walking (one tile). */
    public static final int WALK_UNITS_PER_TICK = Perspective.LOCAL_TILE_SIZE;
    /** Local units per tick when running (two tiles). */
    public static final int RUN_UNITS_PER_TICK = 2 * Perspective.LOCAL_TILE_SIZE;
    /** Game tick duration in milliseconds. */
    public static final double TICK_MS = 600.0;
    /** Fraction of the predicted displacement actually applied (hedge against misprediction). */
    public static final double DEFAULT_COMMIT = 0.5;
    /** RuneLite VarPlayer for run-enabled state. */
    public static final int VARP_IS_RUNNING = 173;

    private WalkPathPredictor() {
    }

    /**
     * Pure core: the local-coordinate displacement (dx, dy) the player accumulates by walking from
     * {@code (playerX, playerY)} straight toward {@code (destX, destY)} for {@code travelMs}, capped
     * so it never overshoots the destination, then scaled by {@code commit}.
     *
     * @return a two-element array {@code [dx, dy]} in local units
     */
    public static double[] playerDisplacement(int playerX, int playerY, int destX, int destY,
                                              boolean running, double travelMs, double commit) {
        double dx = destX - playerX;
        double dy = destY - playerY;
        double dist = Math.hypot(dx, dy);
        if (dist < 1.0 || travelMs <= 0.0) {
            return new double[]{0.0, 0.0};
        }
        double unitsPerMs = (running ? RUN_UNITS_PER_TICK : WALK_UNITS_PER_TICK) / TICK_MS;
        double moved = Math.min(unitsPerMs * travelMs, dist); // cannot pass the destination
        double ux = dx / dist;
        double uy = dy / dist;
        return new double[]{ux * moved * commit, uy * moved * commit};
    }

    /**
     * Two-segment player displacement (faithful port of {@code TBr4.TBM}): the player walks the
     * travel budget first along player→target, then along target→destination, accumulating local
     * displacement. The result is scaled by {@code commit} and damped for short total paths
     * ({@code commit * min(1, totalLen/256)}). Used when the click target does not lie on the direct
     * player→destination line.
     *
     * @return local displacement {@code [dx, dy]}
     */
    public static double[] playerDisplacementTwoSegment(int playerX, int playerY,
                                                        int targetX, int targetY,
                                                        int destX, int destY, boolean hasDest,
                                                        boolean running, double travelMs, double commit) {
        int unitsPerTick = running ? RUN_UNITS_PER_TICK : WALK_UNITS_PER_TICK;
        double unitsPerMs = unitsPerTick / TICK_MS;

        // segment 1: player -> target
        double s1x = targetX - playerX;
        double s1y = targetY - playerY;
        double seg1 = Math.hypot(s1x, s1y);

        // segment 2: target -> destination
        double s2x = 0.0;
        double s2y = 0.0;
        double seg2 = 0.0;
        if (hasDest) {
            s2x = destX - targetX;
            s2y = destY - targetY;
            seg2 = Math.hypot(s2x, s2y);
        }

        if ((seg1 < 1.0 && seg2 < 1.0) || travelMs <= 0.0) {
            return new double[]{0.0, 0.0};
        }

        double dx = 0.0;
        double dy = 0.0;
        double remaining = travelMs;

        if (seg1 >= 1.0) {
            double timeForSeg1 = seg1 / unitsPerMs;
            double ux = s1x / seg1;
            double uy = s1y / seg1;
            double t = Math.min(remaining, timeForSeg1);
            double moved = Math.min(unitsPerMs * t, seg1);
            dx += ux * moved;
            dy += uy * moved;
            remaining = Math.max(remaining - timeForSeg1, 0.0);
        }
        if (remaining > 0.0 && seg2 >= 1.0) {
            double ux = s2x / seg2;
            double uy = s2y / seg2;
            double ticks = remaining / TICK_MS;
            double moved = Math.min(unitsPerTick * ticks, seg2);
            dx += ux * moved;
            dy += uy * moved;
        }

        double totalLen = seg1 + seg2;
        double scale = commit * Math.min(1.0, totalLen / 256.0);
        dx *= scale;
        dy *= scale;
        if (Math.hypot(dx, dy) < 1.0) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{dx, dy};
    }

    /**
     * Projects where {@code targetLocal} will appear on the canvas once the player has walked toward
     * its current destination for {@code travelMs}. Falls back to the unleaded projection when there
     * is no active destination (player not walking). Must be called on the client thread.
     *
     * @return the predicted canvas point, or {@code null} if the target is off-screen
     */
    public static Point leadCanvasPoint(LocalPoint targetLocal, double travelMs, double commit) {
        return leadCanvasPoint(targetLocal, travelMs, commit, 0);
    }

    /**
     * As {@link #leadCanvasPoint(LocalPoint, double, double)} but projects at a vertical offset
     * {@code zOffset} (e.g. half an actor's logical height) so the aim lands on the body rather than
     * the feet tile. Must be called on the client thread.
     */
    public static Point leadCanvasPoint(LocalPoint targetLocal, double travelMs, double commit, int zOffset) {
        Client client = Microbot.getClient();
        if (client == null || targetLocal == null) {
            return null;
        }
        WorldView wv = client.getTopLevelWorldView();
        int plane = wv == null ? client.getPlane() : wv.getPlane();

        LocalPoint dest = client.getLocalDestinationLocation();
        Player player = client.getLocalPlayer();
        LocalPoint playerLoc = player == null ? null : player.getLocalLocation();

        LocalPoint projectFrom = targetLocal;
        if (dest != null && playerLoc != null) {
            boolean running = client.getVarpValue(VARP_IS_RUNNING) == 1;
            // Two-segment walk (player->target->dest), matching TBr4.TBM.
            double[] disp = playerDisplacementTwoSegment(
                    playerLoc.getX(), playerLoc.getY(),
                    targetLocal.getX(), targetLocal.getY(),
                    dest.getX(), dest.getY(), true, running, travelMs, commit);
            // scene shifts opposite the player's motion -> subtract the displacement
            projectFrom = new LocalPoint(
                    targetLocal.getX() - (int) Math.round(disp[0]),
                    targetLocal.getY() - (int) Math.round(disp[1]),
                    wv);
        }
        net.runelite.api.Point cv = zOffset == 0
                ? Perspective.localToCanvas(client, projectFrom, plane)
                : Perspective.localToCanvas(client, projectFrom, plane, zOffset);
        return cv == null ? null : new Point(cv.getX(), cv.getY());
    }
}
