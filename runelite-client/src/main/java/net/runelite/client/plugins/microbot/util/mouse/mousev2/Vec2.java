package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.awt.Point;

/**
 * Minimal immutable 2D vector backed by doubles. Used throughout the MouseV2 engine for spline
 * control points, sampled path points and curvature math, where sub-pixel precision matters before
 * the final {@link #toRoundedPoint()} rounding to integer screen coordinates.
 */
public final class Vec2 {
    public final double x;
    public final double y;

    public Vec2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public static Vec2 of(Point p) {
        return new Vec2(p.x, p.y);
    }

    public Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, y + o.y);
    }

    public Vec2 sub(Vec2 o) {
        return new Vec2(x - o.x, y - o.y);
    }

    public Vec2 scale(double s) {
        return new Vec2(x * s, y * s);
    }

    public double length() {
        return Math.hypot(x, y);
    }

    public double distance(Vec2 o) {
        return Math.hypot(x - o.x, y - o.y);
    }

    /**
     * Perpendicular vector (rotated +90 degrees). Used to offset spline control points sideways
     * from the straight-line travel direction to produce a bow.
     */
    public Vec2 perp() {
        return new Vec2(-y, x);
    }

    /** Unit-length copy, or {@code (0,0)} for a zero-length vector. */
    public Vec2 normalized() {
        double len = length();
        return len < 1e-9 ? new Vec2(0, 0) : new Vec2(x / len, y / len);
    }

    /** Linear interpolation: {@code this} at {@code t=0}, {@code o} at {@code t=1}. */
    public Vec2 lerp(Vec2 o, double t) {
        return new Vec2(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    public Point toRoundedPoint() {
        return new Point((int) Math.round(x), (int) Math.round(y));
    }

    @Override
    public String toString() {
        return "Vec2(" + x + ", " + y + ")";
    }
}
