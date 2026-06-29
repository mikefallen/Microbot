package net.runelite.client.plugins.devtools;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Validation paint for the MouseV2 engine, styled after the upstream overlay: the planned spline is a
 * glowing cyan marching-ants line, the predicted lead point a pulsing orange reticle, and the state
 * readout a polished HUD panel. Reads only the published {@link MouseV2Debug} snapshot (never live
 * engine state); the DevTools toggle controls this HUD, not MouseV2's always-on paint state.
 */
public class MouseV2DebugOverlay extends Overlay {
    private final DevToolsPlugin plugin;
    private int frame;

    @Inject
    MouseV2DebugOverlay(DevToolsPlugin plugin) {
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D g) {
        MouseV2Debug.setEnabled(true);
        if (!plugin.getMouseV2Debug().isActive()) {
            return null;
        }

        frame++;
        MouseV2Paint.hints(g);

        drawPlannedSpline(g);
        drawSampleHistory(g);
        drawPrediction(g);
        drawClick(g);
        drawHud(g);
        return null;
    }

    private void drawPlannedSpline(Graphics2D g) {
        List<Point> path = MouseV2Debug.getPlannedPath();
        if (path.size() < 2) {
            return;
        }
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            MouseV2Paint.glowSegment(g, a.x, a.y, b.x, b.y, MouseV2Paint.PATH, frame);
        }
        for (Point p : path) {
            MouseV2Paint.node(g, p.x, p.y, MouseV2Paint.PATH, 220);
        }
    }

    private void drawSampleHistory(Graphics2D g) {
        List<Point> samples = MouseV2Debug.getSampleHistory();
        if (samples.isEmpty()) {
            return;
        }
        for (Point p : samples) {
            MouseV2Paint.node(g, p.x, p.y, Color.WHITE, 130);
        }
        if (samples.size() >= 2) {
            Point first = samples.get(0);
            Point last = samples.get(samples.size() - 1);
            g.setColor(new Color(255, 255, 255, 110));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(first.x, first.y, last.x, last.y);
        }
    }

    private void drawPrediction(Graphics2D g) {
        if (!MouseV2Debug.isPredictiveActive()) {
            return;
        }
        Point raw = MouseV2Debug.getRawTarget();
        Point predicted = MouseV2Debug.getPredictedTarget();

        if (raw != null) {
            MouseV2Paint.pulsingRing(g, raw.x, raw.y, MouseV2Paint.CURRENT, frame);
        }
        if (raw != null && predicted != null) {
            // The lead vector from the current target to where MouseV2 is aiming.
            MouseV2Paint.glowSegment(g, raw.x, raw.y, predicted.x, predicted.y, MouseV2Paint.TARGET, frame);
        }
        if (predicted != null) {
            MouseV2Paint.pulsingReticle(g, predicted.x, predicted.y, MouseV2Paint.TARGET, frame);
        }
    }

    private void drawClick(Graphics2D g) {
        Rectangle box = MouseV2Debug.getClickbox();
        if (box != null) {
            g.setColor(MouseV2Paint.withAlpha(MouseV2Paint.ACCENT, 50));
            g.setStroke(new BasicStroke(3.5f));
            g.drawRect(box.x, box.y, box.width, box.height);
            g.setColor(MouseV2Paint.withAlpha(MouseV2Paint.ACCENT, 220));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(box.x, box.y, box.width, box.height);
        }
        Point cp = MouseV2Debug.getClickPoint();
        if (cp != null) {
            g.setColor(MouseV2Paint.ACCENT);
            g.fillOval(cp.x - 3, cp.y - 3, 6, 6);
        }
    }

    private void drawHud(Graphics2D g) {
        int misses = MouseV2Debug.getConsecutiveMisses();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"MOUSEV2", MouseV2Debug.getEngine()});
        rows.add(new String[]{"Predict",
                (MouseV2Debug.isPredictiveActive() ? "ON" : "off") + " (" + MouseV2Debug.getMotionReason() + ")"});
        rows.add(new String[]{"Lead / miss",
                String.format("%dms / %d%s", MouseV2Debug.getLeadMs(), misses, misses >= 3 ? "  CANCEL" : "")});
        rows.add(new String[]{"Last move",
                String.format("%.0fpx  %dms  %s",
                        MouseV2Debug.getLastMoveDist(), MouseV2Debug.getLastMoveMs(), MouseV2Debug.getCurveStyle())});
        rows.add(new String[]{"Profile", MouseV2Debug.getProfileText()});
        rows.add(new String[]{"Context", MouseV2Debug.getContextText()});
        rows.add(new String[]{"Counters",
                String.format("mv %d  pr %d  ov %d  cx %d",
                        MouseV2Debug.getMoves(), MouseV2Debug.getPredictiveMoves(),
                        MouseV2Debug.getOvershootLegs(), MouseV2Debug.getCancels())});
        if (MouseV2Debug.getClickPoint() != null) {
            rows.add(new String[]{"Click inside", String.valueOf(MouseV2Debug.isClickedInsideNoRemove())});
        }
        MouseV2Paint.hudPanel(g, rows);
    }
}
