package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.api.Actor;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;

/**
 * Always-on MouseV2 paint, matching the upstream target painter (TBm1) and red-X cursor (TBus.TBc).
 * The mouse trail lives only in the DevTools overlay; this overlay handles the target hull,
 * linked-cursor indicator, prediction markers, and click pulse.
 */
public class MouseV2DebugOverlay extends Overlay {
    private static final Color CYAN       = new Color(0, 229, 255);
    private static final Color CYAN_160   = new Color(0, 229, 255, 160);
    private static final Color CYAN_80    = new Color(0, 229, 255, 80);
    private static final Color RED_POINTER = Color.RED;
    private static final Color HULL_GLOW  = new Color(0, 229, 255, 40);
    private static final Color HULL_LINE  = new Color(0, 229, 255, 180);
    private static final Color RAW_MAGENTA = new Color(255, 60, 200, 220);
    private static final Color PREDICTION_ORANGE = new Color(255, 140, 40);

    private static final BasicStroke HULL_GLOW_STROKE =
            new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke HULL_LINE_STROKE =
            new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    @Inject
    public MouseV2DebugOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D g) {
        MouseV2Debug.setEnabled(true);
        setOverlayHints(g);

        // Red X cursor: drawn unconditionally at the actual mouse position every frame (TBus.TBc).
        Point cursor = currentMousePoint();
        if (cursor != null) {
            drawRedCross(g, cursor.x, cursor.y);
        }

        // Target state — only paint when a target is active.
        Shape targetShape = resolveTargetShape();
        Point raw       = MouseV2Debug.getRawTarget();
        Point predicted = MouseV2Debug.getPredictedTarget();
        boolean hasTarget = targetShape != null || raw != null || predicted != null;
        if (!hasTarget) {
            return null;
        }

        // Hull paint: no fill, double-stroke (glow then core).
        if (targetShape != null) {
            drawHull(g, targetShape);
        }

        int frame = animationFrame();

        // Tracked target point: predicted if non-null, else raw.
        Point tracked = predicted != null ? predicted : raw;

        // Prediction markers: only while prediction active AND both raw and predicted exist.
        if (MouseV2Debug.isPredictiveActive() && raw != null && predicted != null) {
            if (!raw.equals(predicted)) {
                drawPredictionConnector(g, raw, predicted);
            }
            drawRawTarget(g, raw);
        }

        // Linked cursor at the tracked target point (NOT at the cursor).
        if (tracked != null) {
            drawLinkedCursor(g, tracked.x, tracked.y, frame);
        }

        // Orange click pulse (ONE-SHOT, 15 frames of 20ms each = 300ms window).
        long clickAtMs = MouseV2Debug.getClickAtMs();
        if (clickAtMs > 0) {
            long ageMs = System.currentTimeMillis() - clickAtMs;
            int age = (int) (ageMs / 20L); // 20ms per "frame"
            if (age >= 15) {
                // Pulse window over — clear stale target paint.
                MouseV2Debug.clearPostClick();
            } else if (age >= 0) {
                Point anchor = MouseV2Debug.getClickAnchor();
                if (anchor != null) {
                    drawClickPulse(g, anchor, tracked, age);
                }
            }
        }

        return null;
    }

    private static void setOverlayHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /**
     * Resolves the shape to paint: prefers the sampled targetShape published by ShapeAimPicker,
     * then the live hull, then the clickbox rect. Returns null when nothing is available.
     */
    private static Shape resolveTargetShape() {
        // 1. Exact shape from aim picker (e.g. shrunk tile poly).
        Shape picked = MouseV2Debug.getTargetShape();
        if (picked != null) {
            return picked;
        }
        // 2. Live hull from the target entity.
        Shape live = targetHull();
        if (live != null) {
            return live;
        }
        // 3. Fallback: bounds rectangle from clickbox.
        Rectangle box = MouseV2Debug.getClickbox();
        if (box != null && box.width > 0 && box.height > 0) {
            return box;
        }
        return null;
    }

    /** The current target entity's clickbox/hull shape, recomputed live, or {@code null}. */
    private static Shape targetHull() {
        Object t = MouseV2Debug.getTargetEntity();
        try {
            if (t instanceof Actor) {
                return ((Actor) t).getConvexHull();
            }
            if (t instanceof TileObject) {
                return ((TileObject) t).getClickbox();
            }
        } catch (Exception ignored) {
            // entity gone / not renderable this frame
        }
        return null;
    }

    /** Hull paint: NO fill; glow stroke then core stroke (TBm1). */
    private static void drawHull(Graphics2D g, Shape shape) {
        g.setColor(HULL_GLOW);
        g.setStroke(HULL_GLOW_STROKE);
        g.draw(shape);
        g.setColor(HULL_LINE);
        g.setStroke(HULL_LINE_STROKE);
        g.draw(shape);
    }

    /** Red X at the actual cursor (TBus.TBc): plain 1px stroke, drawn every frame. */
    private static void drawRedCross(Graphics2D g, int x, int y) {
        g.setColor(RED_POINTER);
        g.setStroke(new BasicStroke(1.0f));
        g.drawLine(x - 5, y, x + 5, y);
        g.drawLine(x, y - 5, x, y + 5);
    }

    /**
     * Linked cursor at the tracked target point (TBm1): pulsing outer ring, fixed middle ring,
     * four tick marks, solid center dot.
     */
    private static void drawLinkedCursor(Graphics2D g, float x, float y, int frame) {
        float pulse = (float) (Math.sin(frame * 0.18) * 0.5 + 0.5);
        float radius = 8.0f + pulse * 5.0f;
        int alpha = clamp((int) (110.0f - pulse * 70.0f), 20, 255);

        // Outer pulsing ring.
        g.setColor(new Color(0, 229, 255, alpha));
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new Ellipse2D.Float(x - radius, y - radius, radius * 2.0f, radius * 2.0f));

        // Middle ring (12px diameter).
        g.setColor(CYAN_160);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Float(x - 6.0f, y - 6.0f, 12.0f, 12.0f));

        // Tick marks from ±6 to ±10 on each axis.
        g.setColor(CYAN_80);
        g.setStroke(new BasicStroke(1.0f));
        g.drawLine((int) (x - 10.0f), (int) y, (int) (x - 6.0f), (int) y);
        g.drawLine((int) (x + 6.0f),  (int) y, (int) (x + 10.0f), (int) y);
        g.drawLine((int) x, (int) (y - 10.0f), (int) x, (int) (y - 6.0f));
        g.drawLine((int) x, (int) (y + 6.0f),  (int) x, (int) (y + 10.0f));

        // Filled center dot (3px diameter).
        g.setColor(CYAN);
        g.fill(new Ellipse2D.Float(x - 1.5f, y - 1.5f, 3.0f, 3.0f));
    }

    /** Magenta raw-target dot (TBm1 prediction markers). */
    private static void drawRawTarget(Graphics2D g, Point p) {
        g.setColor(RAW_MAGENTA);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Float(p.x - 4.0f, p.y - 4.0f, 8.0f, 8.0f));
        g.fill(new Ellipse2D.Float(p.x - 0.75f, p.y - 0.75f, 1.5f, 1.5f));
    }

    /** Gradient connector line from raw (magenta) → predicted (cyan). */
    private static void drawPredictionConnector(Graphics2D g, Point from, Point to) {
        Paint oldPaint   = g.getPaint();
        Stroke oldStroke = g.getStroke();

        GradientPaint glow = new GradientPaint(
                from.x, from.y, new Color(255, 60, 200, 50),
                to.x,   to.y,   new Color(0, 229, 255, 50));
        GradientPaint line = new GradientPaint(
                from.x, from.y, new Color(255, 60, 200, 200),
                to.x,   to.y,   new Color(0, 229, 255, 200));

        g.setPaint(glow);
        g.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(from.x, from.y, to.x, to.y);

        g.setPaint(line);
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(from.x, from.y, to.x, to.y);

        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
    }

    /**
     * Orange one-shot click pulse (TBm1): expanding ring at the click anchor + line to current tracked
     * point. {@code age} is in "frames" of 20ms, drawn while 0 ≤ age < 15.
     */
    private static void drawClickPulse(Graphics2D g, Point anchor, Point currentTracked, int age) {
        float progress = age / 15.0f;
        int ringAlpha  = clamp((int) ((1.0f - progress) * 220.0f), 0, 255);
        float radius   = 4.0f + progress * 12.0f;

        Color ringColor = new Color(
                PREDICTION_ORANGE.getRed(),
                PREDICTION_ORANGE.getGreen(),
                PREDICTION_ORANGE.getBlue(),
                ringAlpha);

        g.setColor(ringColor);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(
                Math.round(anchor.x - radius),
                Math.round(anchor.y - radius),
                Math.round(radius * 2.0f),
                Math.round(radius * 2.0f));

        // Line from anchor to current tracked point (skip if equal or no tracked point).
        if (currentTracked != null && !anchor.equals(currentTracked)) {
            int lineAlpha = clamp((int) (0.7f * ringAlpha), 0, 255);
            g.setColor(new Color(
                    PREDICTION_ORANGE.getRed(),
                    PREDICTION_ORANGE.getGreen(),
                    PREDICTION_ORANGE.getBlue(),
                    lineAlpha));
            g.setStroke(new BasicStroke(1.0f));
            g.drawLine(anchor.x, anchor.y, currentTracked.x, currentTracked.y);
        }
    }

    private static Point currentMousePoint() {
        try {
            java.awt.Point p = Microbot.getMouse().getMousePosition();
            return p == null ? null : new Point(p.x, p.y);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int animationFrame() {
        return (int) ((System.currentTimeMillis() / 50L) & 0x7fffffff);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
