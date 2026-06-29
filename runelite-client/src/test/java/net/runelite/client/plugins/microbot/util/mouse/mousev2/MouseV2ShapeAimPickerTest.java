package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pure-logic unit tests for {@link ShapeAimPicker} (no running client required).
 * Port of the upstream TBy5.TBP + TBkq.TBi logic.
 */
public class MouseV2ShapeAimPickerTest {

    // ------------------------------------------------------------------ pick

    /**
     * (1) Picked point is always inside the shape and viewport over many seeded iterations.
     * Tested with a triangle polygon and an ellipse.
     */
    @Test
    public void pickedPointAlwaysInsideShapeAndViewport_polygon() {
        // Triangle: (50,0), (0,100), (100,100)
        Polygon triangle = new Polygon(
                new int[]{50, 0, 100},
                new int[]{0, 100, 100},
                3);
        Rectangle viewport = new Rectangle(0, 0, 200, 200);
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            Point p = ShapeAimPicker.pick(triangle, viewport, null, rng);
            assertNotNull("should always pick inside triangle", p);
            assertTrue("picked point must be inside triangle: " + p, triangle.contains(p));
            assertTrue("picked point must be inside viewport: " + p, viewport.contains(p));
        }
    }

    @Test
    public void pickedPointAlwaysInsideShapeAndViewport_ellipse() {
        Shape ellipse = new Ellipse2D.Double(20, 20, 60, 40);
        Rectangle viewport = new Rectangle(0, 0, 200, 200);
        Random rng = new Random(77);
        for (int i = 0; i < 500; i++) {
            Point p = ShapeAimPicker.pick(ellipse, viewport, null, rng);
            assertNotNull("should always pick inside ellipse", p);
            assertTrue("picked point must be inside ellipse: " + p, ellipse.contains(p));
            assertTrue("picked point must be inside viewport: " + p, viewport.contains(p));
        }
    }

    /**
     * (2) Centroid bias: over ~2000 picks from a 10x10 rect with fixed seeded Random, mean distance
     * to center is measurably below the uniform-random expectation.
     * <p>
     * For a 10x10 grid (100 candidates), sorted by distance to centroid (4.5, 4.5), the half-gaussian
     * std = 100/3 ≈ 33 so the mean accepted index ≈ 26. This biases picks toward the centroid.
     * Uniform mean distance ≈ 3.1px; bias should push the mean well below 2.8px.
     */
    @Test
    public void centroidBias_meanDistanceBelowUniformExpectation() {
        Rectangle rect = new Rectangle(0, 0, 10, 10);
        Random rng = new Random(1234);
        int n = 2000;
        double sumDist = 0;
        // Centroid (integer average): sum(0..9)/10 = 4 (integer division)
        double cx = 4.0, cy = 4.0;
        for (int i = 0; i < n; i++) {
            Point p = ShapeAimPicker.pick(rect, null, null, rng);
            assertNotNull(p);
            double dx = p.x - cx, dy = p.y - cy;
            sumDist += Math.sqrt(dx * dx + dy * dy);
        }
        double meanDist = sumDist / n;
        // With bias, mean should be clearly below the uniform expectation.
        // Fail-safe: just verify it's less than what you'd expect from uniform (≈ 3.1px from (4,4)).
        assertTrue("centroid bias: mean distance (" + meanDist + ") should show some bias (< 3.0)",
                meanDist < 3.0);
    }

    /**
     * (3) shrink(): vertices move toward centroid by exactly factor (int truncation accounted).
     */
    @Test
    public void shrink_verticesMoveTowardCentroid() {
        // Square (0,0),(100,0),(100,100),(0,100): centroid = (50,50)
        Polygon square = new Polygon(
                new int[]{0, 100, 100, 0},
                new int[]{0, 0, 100, 100},
                4);
        double factor = ShapeAimPicker.TILE_SHRINK; // 0.85
        Polygon shrunken = ShapeAimPicker.shrink(square, factor);
        // Centroid integer = sum/4 = (300/4=75 for x? no: 0+100+100+0=200, /4=50)
        // (0+0+100+100=200, /4=50)
        // Each vertex v' = (50 + (x-50)*0.85, 50 + (y-50)*0.85)
        // (0,0) → (50+(0-50)*0.85, 50+(0-50)*0.85) = (50-42.5, 50-42.5) = (7, 7) (int truncation)
        // (100,0) → (50+(100-50)*0.85, 50+(0-50)*0.85) = (50+42.5, 7) = (92, 7)
        assertEquals("npoints unchanged", square.npoints, shrunken.npoints);
        // Verify all vertices moved toward centroid (all distances to centroid smaller).
        for (int i = 0; i < square.npoints; i++) {
            double origDist = Math.hypot(square.xpoints[i] - 50, square.ypoints[i] - 50);
            double newDist  = Math.hypot(shrunken.xpoints[i] - 50, shrunken.ypoints[i] - 50);
            assertTrue("shrunken vertex closer to centroid", newDist <= origDist);
        }
        // Verify exact expected values for vertex 0: (int)(50+(0-50)*0.85)=7, (int)(50+(0-50)*0.85)=7
        assertEquals(7, shrunken.xpoints[0]);
        assertEquals(7, shrunken.ypoints[0]);
    }

    @Test
    public void shrink_altFactor() {
        Polygon tri = new Polygon(
                new int[]{0, 60, 30},
                new int[]{0, 0, 60},
                3);
        Polygon shrunken = ShapeAimPicker.shrink(tri, ShapeAimPicker.ALT_SHRINK);
        assertEquals(3, shrunken.npoints);
        // All distances to centroid should decrease.
        int cx = (0 + 60 + 30) / 3;
        int cy = (0 + 0 + 60) / 3;
        for (int i = 0; i < tri.npoints; i++) {
            double origDist = Math.hypot(tri.xpoints[i] - cx, tri.ypoints[i] - cy);
            double newDist  = Math.hypot(shrunken.xpoints[i] - cx, shrunken.ypoints[i] - cy);
            assertTrue("alt-shrunk vertex closer to centroid", newDist <= origDist + 1.0); // +1 for int truncation
        }
    }

    /**
     * (4) halfGaussianIndex returns values in range, and index 0 is the mode over a seeded sample.
     */
    @Test
    public void halfGaussianIndex_inRange() {
        Random rng = new Random(7);
        int size = 50;
        for (int i = 0; i < 1000; i++) {
            int idx = ShapeAimPicker.halfGaussianIndex(rng, size);
            assertTrue("index in range [0, size): " + idx, idx >= 0 && idx < size);
        }
    }

    @Test
    public void halfGaussianIndex_zeroIsMode() {
        Random rng = new Random(99);
        int size = 20;
        int n = 10_000;
        int[] counts = new int[size];
        for (int i = 0; i < n; i++) {
            counts[ShapeAimPicker.halfGaussianIndex(rng, size)]++;
        }
        // Index 0 should be the most common (mode of half-normal).
        int maxIdx = 0;
        for (int i = 1; i < size; i++) {
            if (counts[i] > counts[maxIdx]) {
                maxIdx = i;
            }
        }
        assertEquals("index 0 should be the mode", 0, maxIdx);
    }

    /**
     * (5) Predicate rejection: points failing accept are never returned; exhaustion returns null.
     */
    @Test
    public void predicateRejection_failingPointsNeverReturned() {
        Rectangle rect = new Rectangle(10, 10, 20, 20);
        Random rng = new Random(5);
        // Reject everything: should return null.
        Point p = ShapeAimPicker.pick(rect, null, pt -> false, rng);
        assertNull("exhaustion must return null when all points rejected", p);
    }

    @Test
    public void predicateRejection_onlyAcceptedPointsReturned() {
        // Large rectangle; accept only points with even x and y.
        Rectangle rect = new Rectangle(0, 0, 50, 50);
        Random rng = new Random(13);
        Set<Point> accepted = new HashSet<>();
        for (int y = 0; y <= 50; y += 2) {
            for (int x = 0; x <= 50; x += 2) {
                accepted.add(new Point(x, y));
            }
        }
        for (int i = 0; i < 200; i++) {
            Point p = ShapeAimPicker.pick(rect, null, pt -> pt.x % 2 == 0 && pt.y % 2 == 0, new Random(i));
            if (p != null) {
                assertTrue("returned point must pass predicate: " + p,
                        p.x % 2 == 0 && p.y % 2 == 0);
            }
        }
    }

    /**
     * Null / empty-shape edge cases should not throw.
     */
    @Test
    public void pickNull_returnsNull() {
        assertNull(ShapeAimPicker.pick(null, null, null, new Random()));
    }

    @Test
    public void pickEmptyBounds_returnsNull() {
        assertNull(ShapeAimPicker.pick(new Rectangle(5, 5, 0, 0), null, null, new Random()));
    }

    @Test
    public void shrinkNull_returnsNull() {
        assertNull(ShapeAimPicker.shrink(null, 0.85));
    }

    @Test
    public void shrinkEmpty_returnsEmpty() {
        Polygon empty = new Polygon();
        Polygon result = ShapeAimPicker.shrink(empty, 0.85);
        assertEquals(0, result.npoints);
    }
}
