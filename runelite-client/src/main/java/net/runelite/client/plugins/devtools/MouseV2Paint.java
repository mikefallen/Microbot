package net.runelite.client.plugins.devtools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Reusable MouseV2 debug-paint primitives inspired by the upstream overlay style: glowing multi-layer
 * lines with animated marching-ants dashes, pulsing target reticles, and a polished rounded HUD
 * panel. Pure rendering — feed it screen coordinates and a per-render frame counter for animation.
 */
final class MouseV2Paint {
    static final Color PATH = new Color(0, 229, 255);       // cyan
    static final Color TARGET = new Color(255, 180, 60);    // orange
    static final Color CURRENT = new Color(120, 255, 120);  // green
    static final Color ACCENT = new Color(255, 60, 200);    // pink

    private static final Color PANEL_BG = new Color(8, 14, 22, 200);
    private static final Color PANEL_BORDER = new Color(0, 229, 255, 160);
    private static final Color LABEL = new Color(180, 200, 215);

    private MouseV2Paint() {
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** Three-layer glowing segment with an animated marching-ants dash on top (shows direction). */
    static void glowSegment(Graphics2D g, int x1, int y1, int x2, int y2, Color color, int frame) {
        g.setColor(withAlpha(color, 50));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);

        g.setColor(withAlpha(color, 170));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x1, y1, x2, y2);

        float phase = -(frame * 0.6f % 16f);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{6f, 4f}, phase));
        g.drawLine(x1, y1, x2, y2);
    }

    /** Small filled node dot used along a path. */
    static void node(Graphics2D g, int x, int y, Color color, int alpha) {
        g.setColor(withAlpha(color, alpha));
        g.fillOval(x - 2, y - 2, 4, 4);
    }

    /** Pulsing ring + crosshair ticks (the target reticle). */
    static void pulsingReticle(Graphics2D g, int cx, int cy, Color color, int frame) {
        float f = (float) (Math.sin(frame * 0.22) * 0.5 + 0.5);
        float r = 6f + f * 4f;
        int a = (int) (180f - f * 80f);
        g.setColor(withAlpha(color, Math.max(60, Math.min(255, a))));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));

        g.setColor(color);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(cx - 9, cy, cx - 4, cy);
        g.drawLine(cx + 4, cy, cx + 9, cy);
        g.drawLine(cx, cy - 9, cx, cy - 4);
        g.drawLine(cx, cy + 4, cx, cy + 9);
    }

    /** Pulsing ring only (no ticks) — for a secondary/current marker. */
    static void pulsingRing(Graphics2D g, int cx, int cy, Color color, int frame) {
        float f = (float) (Math.sin(frame * 0.18) * 0.5 + 0.5);
        float r = 8f + f * 6f;
        int a = (int) (140f - f * 80f);
        g.setColor(withAlpha(color, Math.max(40, Math.min(255, a))));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));
    }

    /**
     * HUD: rounded translucent panel with a gradient border, cyan corner accents, and
     * two-column rows. Row 0 is rendered as a header (cyan label / orange value); the rest as
     * light-label / white-value. Returns nothing; draws at the top-left.
     */
    static void hudPanel(Graphics2D g, List<String[]> rows) {
        Font labelFont = new Font("SansSerif", Font.BOLD, 11);
        Font valueFont = new Font("SansSerif", Font.PLAIN, 11);
        Font headerFont = new Font("SansSerif", Font.BOLD, 12);

        g.setFont(valueFont);
        FontMetrics fm = g.getFontMetrics();
        int labelW = 0;
        int valueW = 0;
        for (String[] row : rows) {
            labelW = Math.max(labelW, g.getFontMetrics(labelFont).stringWidth(row[0]));
            valueW = Math.max(valueW, fm.stringWidth(row[1]));
        }

        int padX = 10;
        int padY = 8;
        int gap = 12;
        int lineH = fm.getHeight() + 2;
        int w = padX * 2 + labelW + gap + valueW;
        int h = padY * 2 + lineH * rows.size() + 4;
        float x = 10f;
        float y = 10f;

        g.setColor(PANEL_BG);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 8f, 8f));
        g.setPaint(new GradientPaint(x, y, PANEL_BORDER, x + w, y, withAlpha(ACCENT, 140)));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 8f, 8f));

        g.setColor(new Color(0, 229, 255, 180));
        g.drawLine((int) (x + 6), (int) (y + 4), (int) (x + 14), (int) (y + 4));
        g.drawLine((int) (x + w - 14), (int) (y + 4), (int) (x + w - 6), (int) (y + 4));

        float ty = y + padY + fm.getAscent();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (i == 0) {
                g.setFont(headerFont);
                g.setColor(PATH);
                g.drawString(row[0], x + padX, ty);
                g.setColor(TARGET);
                g.drawString(row[1], x + padX + labelW + gap, ty);
            } else {
                g.setFont(labelFont);
                g.setColor(LABEL);
                g.drawString(row[0], x + padX, ty);
                g.setFont(valueFont);
                g.setColor(Color.WHITE);
                g.drawString(row[1], x + padX + labelW + gap, ty);
            }
            ty += lineH;
        }
    }
}
