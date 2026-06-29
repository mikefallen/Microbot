package net.runelite.client.plugins.devtools;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the bot's emitted mouse path in the upstream overlay style: a glowing cyan marching-ants line
 * through evenly-spaced nodes, fading toward the tail, with a pulsing reticle at the start of the
 * stroke and a pulsing marker at the last click. Reads the shared emitted-point trail from {@link
 * Microbot#getMouse()}, so it visualises whichever engine is active — including the MouseV2 spline.
 */
public class MicrobotMouseOverlay extends Overlay {
    /** Spacing (px) between nodes along the resampled path. */
    private static final double DOT_SPACING = 14.0;

    private final Client client;
    private final DevToolsPlugin plugin;
    private int frame;

    @Inject
    MicrobotMouseOverlay(Client client, DevToolsPlugin plugin) {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!plugin.getMouseMovement().isActive()) {
            return null;
        }

        if (!Microbot.getMouse().getTimer().isRunning()) {
            Microbot.getMouse().getTimer().start();
        }

        frame++;
        MouseV2Paint.hints(g);

        // Snapshot the trail to avoid concurrent modification while resampling.
        Point[] trail = Microbot.getMouse().getPoints().toArray(new Point[0]);
        List<double[]> nodes = resampleByArcLength(trail, DOT_SPACING);

        // Glowing marching-ants line through the resampled nodes.
        for (int i = 1; i < nodes.size(); i++) {
            double[] a = nodes.get(i - 1);
            double[] b = nodes.get(i);
            MouseV2Paint.glowSegment(g, (int) Math.round(a[0]), (int) Math.round(a[1]),
                    (int) Math.round(b[0]), (int) Math.round(b[1]), MouseV2Paint.PATH, frame);
        }
        // Node dots, fading toward the tail (oldest).
        int n = nodes.size();
        for (int i = 0; i < n; i++) {
            double frac = n <= 1 ? 1.0 : i / (double) (n - 1);
            int alpha = (int) (60 + 160 * frac);
            double[] d = nodes.get(i);
            MouseV2Paint.node(g, (int) Math.round(d[0]), (int) Math.round(d[1]), MouseV2Paint.PATH, alpha);
        }

        // Reticle at the oldest (tail) end of the stroke.
        Point start = firstNonNull(trail);
        if (start != null) {
            MouseV2Paint.pulsingReticle(g, start.getX(), start.getY(), MouseV2Paint.PATH, frame);
        }

        // Last click target.
        Point lastClick = Microbot.getMouse().getLastClick();
        if (lastClick != null && lastClick.getX() >= 0 && lastClick.getY() >= 0) {
            MouseV2Paint.pulsingRing(g, lastClick.getX(), lastClick.getY(), MouseV2Paint.TARGET, frame);
        }

        return null;
    }

    private static Point firstNonNull(Point[] pts) {
        for (Point p : pts) {
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * Resamples the polyline through {@code pts} into points spaced evenly by {@code spacing} along
     * the arc length, so the nodes are uniform regardless of the (power-law) timing density of the
     * underlying emitted points.
     */
    private static List<double[]> resampleByArcLength(Point[] pts, double spacing) {
        List<double[]> out = new ArrayList<>();

        List<Point> p = new ArrayList<>(pts.length);
        for (Point q : pts) {
            if (q != null) {
                p.add(q);
            }
        }
        if (p.isEmpty()) {
            return out;
        }

        double prevX = p.get(0).getX();
        double prevY = p.get(0).getY();
        out.add(new double[]{prevX, prevY});

        double acc = 0.0; // distance accumulated since the last placed node
        for (int i = 1; i < p.size(); i++) {
            double cx = p.get(i).getX();
            double cy = p.get(i).getY();
            double segLen = Math.hypot(cx - prevX, cy - prevY);
            if (segLen < 1e-6) {
                continue;
            }

            double sx = prevX;
            double sy = prevY;
            double remain = segLen;
            while (acc + remain >= spacing) {
                double need = spacing - acc;
                double f = need / remain;
                double nx = sx + (cx - sx) * f;
                double ny = sy + (cy - sy) * f;
                out.add(new double[]{nx, ny});
                sx = nx;
                sy = ny;
                remain -= need;
                acc = 0.0;
            }
            acc += remain;
            prevX = cx;
            prevY = cy;
        }
        return out;
    }
}
