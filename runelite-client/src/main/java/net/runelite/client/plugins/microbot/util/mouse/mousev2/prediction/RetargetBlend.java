package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.Vec2;

import java.util.List;

/**
 * Blends a target shift into the remaining points of an in-flight path so the cursor curves toward
 * the new target instead of snapping to it. The positional delta is distributed across the tail
 * with a smoothstep weight ({@code 3t^2 - 2t^3}): zero at the current point (so motion stays
 * continuous) growing to one at the final point (so the path now ends exactly on the new target).
 */
public final class RetargetBlend {
    private RetargetBlend() {
    }

    /**
     * @param path      the path being walked (mutated in place)
     * @param fromIndex index of the next point about to be emitted; points before it are untouched
     * @param delta     how far the target has moved since the path was built
     */
    public static void blend(List<Vec2> path, int fromIndex, Vec2 delta) {
        int n = path.size();
        if (fromIndex < 0 || fromIndex >= n) {
            return;
        }
        int rem = n - fromIndex;
        for (int j = fromIndex; j < n; j++) {
            double t = rem <= 1 ? 1.0 : (j - fromIndex) / (double) (rem - 1);
            double w = t * t * (3.0 - 2.0 * t); // smoothstep
            path.set(j, path.get(j).add(delta.scale(w)));
        }
    }
}
