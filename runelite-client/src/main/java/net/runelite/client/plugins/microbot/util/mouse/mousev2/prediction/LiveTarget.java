package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import java.awt.Point;
import java.util.function.Supplier;

/**
 * A continuously re-evaluated screen target. Each call to {@link #get()} returns the target's
 * <em>current</em> canvas position (or {@code null} if it has despawned / left the screen), so the
 * MouseV2 follow loop can track a target that is moving on screen while the cursor travels toward
 * it.
 */
@FunctionalInterface
public interface LiveTarget extends Supplier<Point> {
    /** @return the target's current canvas point, or {@code null} if it is no longer clickable. */
    @Override
    Point get();
}
