package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Aim-point picker based on the upstream {@code TBy5.TBP} and {@code TBkq.TBi}.
 * Pure logic — no client access; takes {@link Shape}/{@link Rectangle}/{@link Point}, testable headless.
 */
public final class ShapeAimPicker {

    /** Shrink factor used for tile polygons (TBkq.TBi path 1). */
    public static final double TILE_SHRINK = 0.85;
    /** Shrink factor used in the alternative path (TBkq.TBi path 2). */
    public static final double ALT_SHRINK  = 0.93;

    /** Maximum candidate pixel count before stride sampling kicks in. */
    private static final int MAX_CANDIDATES = 1_000_000;

    private ShapeAimPicker() {
    }

    /**
     * Pick a random aim point inside {@code shape} biased toward the centroid, optionally constrained
     * to {@code viewport}, with optional acceptance filter.
     * <p>
     * Based on the upstream {@code TBy5.TBP}:
     * <ol>
     *   <li>Rasterize candidates (all pixels inside shape ∩ viewport).</li>
     *   <li>If empty, return {@code null}.</li>
     *   <li>Sort ascending by distance to centroid.</li>
     *   <li>Pick via {@link #halfGaussianIndex} (biased toward index 0 = closest to centroid).</li>
     *   <li>If {@code accept} rejects it, try the next; exhaust → {@code null}.</li>
     * </ol>
     *
     * @param shape    the clickable shape (non-null)
     * @param viewport canvas bounds to clip to, or {@code null} for no clipping
     * @param accept   additional acceptance filter, or {@code null} to accept all
     * @param rng      random source
     * @return a picked point, or {@code null} if none could be picked
     */
    public static Point pick(Shape shape, Rectangle viewport, Predicate<Point> accept, Random rng) {
        if (shape == null) {
            return null;
        }

        Rectangle bounds = shape.getBounds();
        if (bounds.isEmpty()) {
            return null;
        }

        // Clip bounds to viewport when provided.
        Rectangle scan = viewport != null ? bounds.intersection(viewport) : bounds;
        if (scan.isEmpty()) {
            return null;
        }

        // Determine stride for OOM guard.
        int stride = 1;
        long area = (long) scan.width * scan.height;
        if (area > MAX_CANDIDATES) {
            stride = (int) Math.ceil(Math.sqrt((double) area / MAX_CANDIDATES));
        }

        // Rasterize candidates.
        List<Point> candidates = new ArrayList<>();
        for (int dy = 0; dy < scan.height; dy += stride) {
            for (int dx = 0; dx < scan.width; dx += stride) {
                Point p = new Point(scan.x + dx, scan.y + dy);
                if (shape.contains(p) && (viewport == null || viewport.contains(p))) {
                    candidates.add(p);
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Centroid (integer division, matching the upstream implementation).
        long sumX = 0, sumY = 0;
        for (Point p : candidates) {
            sumX += p.x;
            sumY += p.y;
        }
        final double cx = (double) (sumX / candidates.size());
        final double cy = (double) (sumY / candidates.size());

        // Sort ascending by distance to centroid.
        candidates.sort((a, b) -> {
            double da = dist2(a, cx, cy);
            double db = dist2(b, cx, cy);
            return Double.compare(da, db);
        });

        // Pick with half-gaussian index bias, applying accept filter.
        while (!candidates.isEmpty()) {
            int idx;
            if (candidates.size() == 1) {
                idx = 0;
            } else {
                idx = halfGaussianIndex(rng, candidates.size());
            }
            Point p = candidates.remove(idx);
            if (accept == null || accept.test(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Based on the upstream {@code TBo(0, size−1, mean=0, std=size/3)}: repeatedly draw a gaussian with
     * std={@code size/3} until it lands in {@code [0, size−1]}. Negative draws are rejected, giving
     * a half-normal distribution biased toward index 0 (closest to centroid). Safety cap: 1000 draws.
     */
    public static int halfGaussianIndex(Random rng, int size) {
        if (size <= 1) {
            return 0;
        }
        double std = size / 3.0;
        for (int i = 0; i < 1000; i++) {
            int v = (int) (rng.nextGaussian() * std + 0.0);
            if (v >= 0 && v < size) {
                return v;
            }
        }
        return 0;
    }

    /**
     * Shrinks a polygon toward its centroid by {@code factor}. Based on the upstream {@code TBkq.TBi}.
     * {@code factor < 1.0} shrinks; {@code factor > 1.0} grows.
     *
     * @param poly   source polygon
     * @param factor scale factor (e.g. {@link #TILE_SHRINK} = 0.85)
     * @return new shrunken polygon, or the original if {@code poly} has no points
     */
    public static Polygon shrink(Polygon poly, double factor) {
        if (poly == null || poly.npoints == 0) {
            return poly;
        }
        double cxD = 0.0, cyD = 0.0;
        for (int i = 0; i < poly.npoints; i++) {
            cxD += poly.xpoints[i];
            cyD += poly.ypoints[i];
        }
        // The upstream implementation uses the true double average of the vertices, truncating only the
        // final scaled coordinates.
        double cx = cxD / poly.npoints;
        double cy = cyD / poly.npoints;

        int[] nx = new int[poly.npoints];
        int[] ny = new int[poly.npoints];
        for (int i = 0; i < poly.npoints; i++) {
            nx[i] = (int) (cx + (poly.xpoints[i] - cx) * factor);
            ny[i] = (int) (cy + (poly.ypoints[i] - cy) * factor);
        }
        return new Polygon(nx, ny, poly.npoints);
    }

    // -- helpers --

    private static double dist2(Point p, double cx, double cy) {
        double dx = p.x - cx, dy = p.y - cy;
        return dx * dx + dy * dy;
    }
}
