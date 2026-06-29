package net.runelite.client.plugins.microbot.util.mouse.mousev2;

/**
 * The shape family of a mouse stroke, selecting which geometry {@link SplineBuilder} produces.
 * <ul>
 *   <li>{@link #BALLISTIC} - the main committed move: a natural cubic spline through
 *       perpendicular-offset control points (the upstream {@code TBrz.TBV}).</li>
 *   <li>{@link #CORRECTIVE} - short overshoot-correction legs: a near-straight line with a single
 *       small {@code 4t(1-t)} bow (the upstream {@code TBrz.TBd}).</li>
 * </ul>
 * This MouseV2 variant has no separate "hook" style; the curved approach is inherent to the main
 * spline's late-biased control points.
 */
public enum CurveStyle {
    BALLISTIC,
    CORRECTIVE
}
