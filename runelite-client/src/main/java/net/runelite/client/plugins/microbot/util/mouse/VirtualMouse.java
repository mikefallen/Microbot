package net.runelite.client.plugins.microbot.util.mouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.ShapeAimPicker;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.LiveTarget;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.LiveTargets;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

@Slf4j
public class VirtualMouse extends Mouse {

    private final ScheduledExecutorService scheduledExecutorService;

    /** Wall-clock time of the last mouse activity (move or click), for idle detection. */
    private static volatile long lastActivityMs = System.currentTimeMillis();

    @Inject
    public VirtualMouse() {
        super();
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    /** Milliseconds since the cursor last moved or clicked. */
    public static long idleMillis() {
        return System.currentTimeMillis() - lastActivityMs;
    }

    private static void markActivity() {
        lastActivityMs = System.currentTimeMillis();
    }

    public void setLastClick(Point point) {
        lastClick2 = lastClick;
        lastClick = point;
        markActivity();
    }

	public void setLastMove(Point point) {
		lastMove = point;
		points.add(point);
		if (points.size() > MAX_POINTS) {
			points.pollFirst();
		}
		markActivity();
	}

    private int[] scaleForDispatch(int x, int y) {
        Client c;
        try {
            c = Microbot.getClient();
        } catch (Exception ex) {
            return new int[]{x, y};
        }
        if (c == null || !c.isStretchedEnabled()) {
            return new int[]{x, y};
        }
        Dimension stretched = c.getStretchedDimensions();
        Dimension real = c.getRealDimensions();
        if (stretched == null || real == null || real.width == 0 || real.height == 0) {
            return new int[]{x, y};
        }
        return new int[]{
                (int) ((long) x * stretched.width / real.width),
                (int) ((long) y * stretched.height / real.height)
        };
    }

    private void dispatchMouse(int id, Point point, int button, int clickCount) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), 0,
                s[0], s[1], clickCount, false, button);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchMouseMove(int id, Point point) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), 0,
                s[0], s[1], 0, false);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchWheel(Point point, int wheelRotation, int unitsToScroll) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, s[0], s[1], 0, false, 0, unitsToScroll, wheelRotation);
        dispatchWithoutFocusGrab(canvas, event);
    }

    // Jagex's MOUSE_PRESSED listener calls canvas.requestFocus() when the event source is the
    // Canvas, which yanks OS keyboard focus away from whatever app the user is typing in. Flip
    // focusable off for the duration of the synthetic dispatch so requestFocus is a no-op; mouse
    // delivery itself is unaffected by focusable state.
    //
    // IMPORTANT: only do this when the canvas is NOT currently the focus owner. If the user is
    // actively typing in the in-game chat (which lives inside the canvas), the canvas IS the focus
    // owner, and setFocusable(false) immediately yanks focus away to the parent container — exactly
    // the opposite of what this method is trying to prevent. Detect that case and skip the toggle.
    private void dispatchWithoutFocusGrab(Canvas canvas, AWTEvent event) {
        boolean canvasIsFocused = canvas.isFocusOwner();
        boolean wasFocusable = canvas.isFocusable();
        boolean shouldGuard = wasFocusable && !canvasIsFocused;
        if (shouldGuard) canvas.setFocusable(false);
        BotEventGuard.begin();
        try {
            canvas.dispatchEvent(event);
        } finally {
            BotEventGuard.end();
            if (shouldGuard) canvas.setFocusable(true);
        }
    }

    /**
     * MouseV2 clickbox tremor-settle: instead of clicking an exact pixel, wobble into the target's
     * clickbox with a few jittery micro-corrections and return the final landed point. Falls back to
     * {@code target} when MouseV2 is disabled/unavailable.
     */
    private Point settleAt(Point target, NewMenuEntry entry) {
        if (target == null
                || !net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings.useMouseV2
                || Microbot.mouseV2 == null) {
            return target;
        }
        int boxW = 8;
        int boxH = 8;
        Rectangle box = null;
        try {
            if (entry != null && Rs2UiHelper.hasActor(entry)) {
                box = Rs2UiHelper.getActorClickbox(entry.getActor());
            } else if (entry != null && Rs2UiHelper.isGameObject(entry)) {
                box = Rs2UiHelper.getObjectClickbox(entry.getGameObject());
            }
            if (box != null && box.width > 0 && box.height > 0) {
                boxW = box.width;
                boxH = box.height;
            }
        } catch (Exception ignored) {
            // use the default small box
        }
        java.awt.Point settled = Microbot.mouseV2.settleInBox(target.getX(), target.getY(), boxW, boxH);
        java.awt.Point result = settled == null ? new java.awt.Point(target.getX(), target.getY()) : settled;
        if (net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.isEnabled()) {
            // Anchor = tracked target at click time (predicted if available, else raw).
            java.awt.Point predicted = net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.getPredictedTarget();
            java.awt.Point raw = net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.getRawTarget();
            java.awt.Point anchor = predicted != null ? predicted : (raw != null ? raw : result);
            net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.setClick(box, result, true, anchor);
        }
        return settled == null ? target : new Point(settled.x, settled.y);
    }

    private void handleClick(Point point, boolean rightClick) {
        int button = rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        entered(point);
        exited(point);
        moved(point);
        pressed(point, button);
        // MouseV2: hold the button a randomised time instead of an instant press
        // (gaussian mean 4ms / std 6ms).
        if (net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings.useMouseV2) {
            long hold = net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Engine.pressHoldMs(
                    java.util.concurrent.ThreadLocalRandom.current());
            if (hold > 0) {
                sleep((int) hold);
            }
        }
        released(point, button);
        clicked(point, button);
        setLastClick(point);
    }

    private boolean shouldMoveNaturally(Point point) {
        return point.getX() > 1
                && point.getY() > 1
                && (Microbot.naturalMouse != null || Microbot.mouseV2 != null);
    }

    /**
     * Routes a natural-motion move through the configured engine: MouseV2 when enabled and
     * available, otherwise the legacy natural mouse. Falls back to the other engine if one is null.
     */
    private void smartMove(int x, int y) {
        smartMove(x, y, 0, 0);
    }

    /** As {@link #smartMove(int, int)} but passes the target clickbox so MouseV2 can size timing. */
    private void smartMove(int x, int y, int boxW, int boxH) {
        boolean useV2 = net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings.useMouseV2
                && Microbot.mouseV2 != null;
        if (useV2) {
            Microbot.mouseV2.moveTo(x, y, boxW, boxH);
        } else if (Microbot.naturalMouse != null) {
            Microbot.naturalMouse.moveTo(x, y);
        } else if (Microbot.mouseV2 != null) {
            Microbot.mouseV2.moveTo(x, y, boxW, boxH);
        }
    }

    /** Target clickbox dimensions {@code [w, h]} for a menu entry's actor/object, or {@code [0,0]}. */
    private int[] entryBox(NewMenuEntry entry) {
        try {
            Rectangle r = null;
            if (entry != null && Rs2UiHelper.hasActor(entry)) {
                r = Rs2UiHelper.getActorClickbox(entry.getActor());
            } else if (entry != null && Rs2UiHelper.isGameObject(entry)) {
                r = Rs2UiHelper.getObjectClickbox(entry.getGameObject());
            }
            if (r != null && r.width > 0 && r.height > 0) {
                return new int[]{r.width, r.height};
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return new int[]{0, 0};
    }

    /**
     * Gets the canvas viewport rectangle for ShapeAimPicker clipping. Returns {@code null} on failure.
     */
    private static Rectangle canvasViewport() {
        try {
            java.awt.Canvas canvas = Microbot.getMouse().getCanvas();
            if (canvas != null) {
                java.awt.Dimension size = canvas.getSize();
                if (size != null && size.width > 0 && size.height > 0) {
                    return new Rectangle(0, 0, size.width, size.height);
                }
            }
            int w = Microbot.getClient().getCanvasWidth();
            int h = Microbot.getClient().getCanvasHeight();
            if (w > 0 && h > 0) {
                return new Rectangle(0, 0, w, h);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Attempts to pick an aim point via {@link ShapeAimPicker} using the exact hull shape for the
     * entry's actor or game object. Returns the picked {@link Point} (RuneLite), or {@code null}
     * when no shape is available or picking fails (caller falls back to the legacy rect path).
     * Also publishes the shape to {@link net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug}.
     */
    private static Point tryShapeAimPick(NewMenuEntry entry) {
        if (!net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings.useMouseV2) {
            return null;
        }
        try {
            java.awt.Shape shape = null;
            if (Rs2UiHelper.hasActor(entry)) {
                shape = Rs2UiHelper.getActorHull(entry.getActor());
            } else if (Rs2UiHelper.isGameObject(entry)) {
                shape = Rs2UiHelper.getObjectHullShape(entry.getGameObject());
            }
            if (shape == null) {
                return null;
            }
            if (net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.isEnabled()) {
                net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.setTargetShape(shape);
            }
            Rectangle viewport = canvasViewport();
            java.awt.Point picked = ShapeAimPicker.pick(shape, viewport, null, ThreadLocalRandom.current());
            if (picked == null) {
                return null;
            }
            return new Point(picked.x, picked.y);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * MouseV2 "Enhanced Clicking": when the player is moving (so the target drifts on screen) and a
     * predictive engine is available, follow the entry's actor/object to where it will be rather
     * than aiming once at a stale point. Returns the settled cursor position to click, or
     * {@code null} when predictive movement was not used (caller falls back to a normal move).
     */
    private Point tryPredictiveMove(NewMenuEntry entry) {
        if (entry == null
                || !net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings.useMouseV2
                || Microbot.mouseV2 == null) {
            return null;
        }
        boolean hasActor = Rs2UiHelper.hasActor(entry);
        boolean hasObject = !hasActor && Rs2UiHelper.isGameObject(entry);
        if (!hasActor && !hasObject) {
            return null;
        }
        String reason = predictionMotionReason();
        if (reason == null) {
            if (MouseV2Debug.isEnabled()) {
                MouseV2Debug.setPredictive(false, "idle");
            }
            return null;
        }
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.setPredictive(true, reason);
        }
        // Physics model: aim where the target will be once the player has walked toward its
        // destination for the cursor's travel time, then settle on the true projection.
        if (hasActor) {
            Microbot.mouseV2.moveToActorPredicted(entry.getActor());
        } else {
            Microbot.mouseV2.moveToObjectPredicted(entry.getGameObject());
        }
        java.awt.Point mp = getMousePosition();
        return mp == null ? null : new Point(mp.x, mp.y);
    }

    /** @return why predictive movement should run ("moving"/"camera"), or {@code null} if idle. */
    private String predictionMotionReason() {
        try {
            if (Rs2Player.isMoving()) {
                return "moving";
            }
        } catch (Exception ignored) {
            // fall through to the camera motion check
        }
        return isCameraRotating() ? "camera" : null;
    }

    private boolean isPredictionMotionActive() {
        try {
            if (Rs2Player.isMoving()) {
                return true;
            }
        } catch (Exception ignored) {
            // fall through to the camera motion check
        }
        return isCameraRotating();
    }

    private boolean isCameraRotating() {
        try {
            int yaw = Rs2Camera.getYaw();
            int pitch = Rs2Camera.getPitch();
            sleep(35);
            int nextYaw = Rs2Camera.getYaw();
            int nextPitch = Rs2Camera.getPitch();
            return yawDelta(yaw, nextYaw) > 1 || Math.abs(nextPitch - pitch) > 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static int yawDelta(int a, int b) {
        int delta = Math.abs(a - b) % 2048;
        return Math.min(delta, 2048 - delta);
    }

    private Point finalizePredictiveClick(NewMenuEntry entry, Point fallback) {
        Rectangle clickbox = predictiveClickbox(entry);
        if (clickbox == null) {
            return fallback;
        }

        java.awt.Point mouse = getMousePosition();
        if (mouse != null && LiveTargets.isInsideClickbox(clickbox, mouse)) {
            Point inside = new Point(mouse.x, mouse.y);
            if (MouseV2Debug.isEnabled()) {
                MouseV2Debug.setClick(clickbox, new java.awt.Point(inside.getX(), inside.getY()), true);
            }
            return inside;
        }

        // Prefer an in-hull pick (the predictive click point should stay inside the true shape,
        // not the bounding rectangle); fall back to the legacy rect pick.
        Point clickPoint = tryShapeAimPick(entry);
        if (clickPoint == null) {
            clickPoint = Rs2UiHelper.getClickingPoint(clickbox, true);
        }
        if (clickPoint == null) {
            return fallback;
        }
        if (MouseV2Debug.isEnabled()) {
            MouseV2Debug.setClick(clickbox, new java.awt.Point(clickPoint.getX(), clickPoint.getY()), false);
        }
        smartMove(clickPoint.getX(), clickPoint.getY());
        return clickPoint;
    }

    private Rectangle predictiveClickbox(NewMenuEntry entry) {
        if (Rs2UiHelper.hasActor(entry)) {
            return Rs2UiHelper.getActorClickbox(entry.getActor());
        }
        if (Rs2UiHelper.isGameObject(entry)) {
            return Rs2UiHelper.getObjectClickbox(entry.getGameObject());
        }
        return null;
    }

    public Mouse click(Point point, boolean rightClick) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            if (shouldMoveNaturally(point)) {
                smartMove(point.getX(), point.getY());
            }
            handleClick(settleAt(point, null), rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(Point point, boolean rightClick, NewMenuEntry entry) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            Point newPoint = point;
            if (net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.isEnabled()) {
                net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Debug.setTargetEntity(
                        Rs2UiHelper.hasActor(entry) ? entry.getActor()
                                : (Rs2UiHelper.isGameObject(entry) ? entry.getGameObject() : null));
            }
            if (shouldMoveNaturally(point)) {
                Point predicted = tryPredictiveMove(entry);
                if (predicted != null) {
                    newPoint = finalizePredictiveClick(entry, predicted);
                } else {
                    int[] box = entryBox(entry);
                    // Shape-based aim pick when MouseV2 is active (ShapeAimPicker, Part C step 9).
                    Point shapePicked = tryShapeAimPick(entry);
                    if (shapePicked != null) {
                        newPoint = shapePicked;
                        smartMove(newPoint.getX(), newPoint.getY(), box[0], box[1]);
                    } else {
                        smartMove(point.getX(), point.getY(), box[0], box[1]);

                        if (Rs2UiHelper.hasActor(entry)) {
                            Rectangle rectangle = Rs2UiHelper.getActorClickbox(entry.getActor());
                            if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                                newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                                smartMove(newPoint.getX(), newPoint.getY(), box[0], box[1]);
                            }
                        }

                        if (Rs2UiHelper.isGameObject(entry)) {
                            Rectangle rectangle = Rs2UiHelper.getObjectClickbox(entry.getGameObject());
                            if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                                newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                                smartMove(newPoint.getX(), newPoint.getY(), box[0], box[1]);
                            }
                        }
                    }
                }
            }

            Microbot.targetMenu = entry;
            handleClick(settleAt(newPoint, entry), rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(int x, int y) {
        return click(new Point(x, y), false);
    }

    public Mouse click(double x, double y) {
        return click(new Point((int) x, (int) y), false);
    }

    public Mouse click(Rectangle rectangle) {
        return click(Rs2UiHelper.getClickingPoint(rectangle, true), false);
    }

    @Override
    public Mouse click(int x, int y, boolean rightClick) {
        return click(new Point(x, y), rightClick);
    }

    @Override
    public Mouse click(Point point) {
        return click(point, false);
    }

    @Override
    public Mouse click(Point point, NewMenuEntry entry) {
        return click(point, false, entry);
    }

    @Override
    public Mouse click() {
        return click(Microbot.getClient().getMouseCanvasPosition());
    }

    public Mouse move(Point point) {
        setLastMove(point);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
        return this;
    }

    public Mouse move(Rectangle rect) {
        Point pt = new Point((int) rect.getCenterX(), (int) rect.getCenterY());
        setLastMove(pt);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, pt);
        return this;
    }

    public Mouse move(Polygon polygon) {
        Point point = new Point((int) polygon.getBounds().getCenterX(), (int) polygon.getBounds().getCenterY());
        setLastMove(point);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
        return this;
    }

    public Mouse scrollDown(Point point) {
        move(point);
        scheduledExecutorService.schedule(
                () -> dispatchWheel(point, 2, 10),
                Rs2Random.logNormalBounded(40, 100), TimeUnit.MILLISECONDS);
        return this;
    }

    public Mouse scrollUp(Point point) {
        move(point);
        scheduledExecutorService.schedule(
                () -> dispatchWheel(point, -2, -10),
                Rs2Random.logNormalBounded(40, 100), TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public java.awt.Point getMousePosition() {
        Point point = lastMove;
        return new java.awt.Point(point.getX(), point.getY());
    }

    @Override
    public Mouse move(int x, int y) {
        return move(new Point(x, y));
    }

    @Override
    public Mouse move(double x, double y) {
        return move(new Point((int) x, (int) y));
    }

    private synchronized void pressed(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_PRESSED, point, button, 1);
    }

    private synchronized void released(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_RELEASED, point, button, 1);
    }

    private synchronized void clicked(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_CLICKED, point, button, 1);
    }

    private synchronized void exited(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_EXITED, point);
    }

    private synchronized void entered(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_ENTERED, point);
    }

    private synchronized void moved(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
    }

    public void shutdown() {
        scheduledExecutorService.shutdownNow();
    }

    public Mouse drag(Point startPoint, Point endPoint) {
        if (startPoint == null || endPoint == null) return this;

        if (shouldMoveNaturally(startPoint))
            smartMove(startPoint.getX(), startPoint.getY());
        else
            move(startPoint);
        sleep(Rs2Random.logNormalBounded(50, 80));
        pressed(startPoint, MouseEvent.BUTTON1);
        sleep(Rs2Random.logNormalBounded(80, 120));
        if (shouldMoveNaturally(endPoint))
            smartMove(endPoint.getX(), endPoint.getY());
        else
            move(endPoint);
        sleep(Rs2Random.logNormalBounded(80, 120));
        released(endPoint, MouseEvent.BUTTON1);

        return this;
    }
}
