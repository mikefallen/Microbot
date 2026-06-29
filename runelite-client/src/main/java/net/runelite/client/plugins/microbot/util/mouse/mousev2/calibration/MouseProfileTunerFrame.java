package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.MouseV2Engine;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Swing recorder UI for the MouseV2 Profile Tuner.
 * <p>
 * Presents a large white canvas with a red target circle; as the user moves toward and clicks it
 * the motion is recorded, classified into fragments and accumulated into a {@link ProfileAggregator}.
 * "Process &amp; Save" writes calibration.json, fragment pools and micro-pauses.json to disk, then
 * reloads the engine models.
 * <p>
 * <strong>Threading</strong>: this class is 100% Swing EDT — it never calls game-client methods.
 */
@Singleton
public final class MouseProfileTunerFrame extends JFrame {

    // ---- target appearance ----
    private static final int TARGET_RADIUS = 22;
    private static final int MIN_SPAWN_DIST = 150;

    // ---- static singleton for openStatic() ----
    private static volatile MouseProfileTunerFrame STATIC_INSTANCE;

    // ---- recording state ----
    private final ProfileAggregator aggregator = new ProfileAggregator();
    /** Samples for the current in-flight acquisition. */
    private final List<MovementSample> currentSamples = new ArrayList<>();
    /** All past completed trails (for faded history). */
    private final List<List<MovementSample>> history = new ArrayList<>();
    private boolean recording = true;
    private int movementCount = 0;
    private long targetSpawnMs = 0L;

    // ---- target geometry ----
    private double targetX, targetY;

    // ---- UI components ----
    private JPanel canvas;
    private JLabel movementsLabel;
    /** Summary counts: noise bucket → count. */
    private final Map<String, JLabel> noiseCountLabels  = new LinkedHashMap<>();
    /** Summary counts: correction bucket → count. */
    private final Map<String, JLabel> corrCountLabels   = new LinkedHashMap<>();
    /** Running bucket counts for the live summary. */
    private final Map<String, Integer> noiseCounts      = new LinkedHashMap<>();
    private final Map<String, Integer> corrCounts       = new LinkedHashMap<>();

    private final ExecutorService saveWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MouseProfileTuner-save");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public MouseProfileTunerFrame() {
        STATIC_INSTANCE = this;
        initCounts();
        buildUi();
        spawnTarget();
    }

    // -------------------------------------------------------------------------
    // Static entry point
    // -------------------------------------------------------------------------

    /**
     * Opens (or re-shows) the singleton recorder frame on the EDT. Creates a new instance lazily if
     * none has been created via Guice injection yet.
     */
    public static void openStatic() {
        SwingUtilities.invokeLater(() -> {
            MouseProfileTunerFrame f = STATIC_INSTANCE;
            if (f == null) {
                f = new MouseProfileTunerFrame();
            }
            f.setVisible(true);
            f.toFront();
        });
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUi() {
        setTitle("Recording…");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout(4, 4));

        // ---- canvas ----
        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawCanvas((Graphics2D) g);
            }
        };
        canvas.setBackground(Color.WHITE);
        canvas.setPreferredSize(new Dimension(700, 500));
        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                onCanvasMouse(e.getX(), e.getY());
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                onCanvasMouse(e.getX(), e.getY());
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onCanvasClick(e.getX(), e.getY());
            }
        });
        add(canvas, BorderLayout.CENTER);

        // ---- controls bar ----
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        JButton stopBtn = new JButton("Stop");
        stopBtn.addActionListener(e -> {
            recording = !recording;
            stopBtn.setText(recording ? "Stop" : "Resume");
        });
        controls.add(stopBtn);

        JButton saveBtn = new JButton("Process & Save");
        saveBtn.addActionListener(e -> processAndSave());
        controls.add(saveBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clearSession());
        controls.add(clearBtn);

        movementsLabel = new JLabel("Movements: 0");
        controls.add(movementsLabel);

        add(controls, BorderLayout.NORTH);

        // ---- fragment summary + delete button (south panel) ----
        JPanel south = new JPanel(new BorderLayout(4, 4));

        JPanel summary = buildSummaryPanel();
        south.add(summary, BorderLayout.CENTER);

        JButton deleteBtn = new JButton("Delete My Data");
        deleteBtn.setForeground(Color.RED);
        deleteBtn.addActionListener(e -> deleteMyData());
        JPanel deletePan = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        deletePan.add(deleteBtn);
        south.add(deletePan, BorderLayout.EAST);

        add(south, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildSummaryPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Fragment Summary"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 6, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // Noise textures
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new JLabel("Noise Textures"), gbc);
        gbc.gridwidth = 1;

        String[] noiseBuckets = {
            "short_slow", "short_fast",
            "medium_slow", "medium_fast",
            "long_slow",   "long_fast"
        };
        for (String key : noiseBuckets) {
            JLabel lbl = new JLabel("0");
            noiseCountLabels.put(key, lbl);
            noiseCounts.put(key, 0);

            gbc.gridx = 0; gbc.gridy = row;
            panel.add(new JLabel(key + ":"), gbc);
            gbc.gridx = 1; gbc.gridy = row;
            panel.add(lbl, gbc);
            row++;
        }

        // Correction templates
        gbc.gridx = 2; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Correction Templates"), gbc);
        gbc.gridwidth = 1;

        String[] corrBuckets = {"small", "medium", "large"};
        int cRow = 1;
        for (String key : corrBuckets) {
            JLabel lbl = new JLabel("0");
            corrCountLabels.put(key, lbl);
            corrCounts.put(key, 0);

            gbc.gridx = 2; gbc.gridy = cRow;
            panel.add(new JLabel(key + ":"), gbc);
            gbc.gridx = 3; gbc.gridy = cRow;
            panel.add(lbl, gbc);
            cRow++;
        }

        return panel;
    }

    private void initCounts() {
        for (String key : new String[]{"short_slow","short_fast","medium_slow","medium_fast","long_slow","long_fast"}) {
            noiseCounts.put(key, 0);
        }
        for (String key : new String[]{"small","medium","large"}) {
            corrCounts.put(key, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Canvas painting
    // -------------------------------------------------------------------------

    private void drawCanvas(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // faded history trails
        g.setStroke(new BasicStroke(1.0f));
        g.setColor(new Color(180, 180, 180, 80));
        for (List<MovementSample> trail : history) {
            drawTrail(g, trail, new Color(180, 180, 220, 60));
        }

        // current trail (bright blue)
        drawTrail(g, currentSamples, new Color(40, 100, 200, 200));

        // target circle
        int tx = (int) Math.round(targetX);
        int ty = (int) Math.round(targetY);
        // semi-transparent fill
        g.setColor(new Color(255, 60, 60, 70));
        g.fillOval(tx - TARGET_RADIUS, ty - TARGET_RADIUS, TARGET_RADIUS * 2, TARGET_RADIUS * 2);
        // ring
        g.setStroke(new BasicStroke(2.0f));
        g.setColor(new Color(220, 20, 20));
        g.drawOval(tx - TARGET_RADIUS, ty - TARGET_RADIUS, TARGET_RADIUS * 2, TARGET_RADIUS * 2);

        // crosshair
        int ch = TARGET_RADIUS / 2;
        g.drawLine(tx - ch, ty, tx + ch, ty);
        g.drawLine(tx, ty - ch, tx, ty + ch);
    }

    private static void drawTrail(Graphics2D g, List<MovementSample> trail, Color c) {
        if (trail.size() < 2) return;
        g.setColor(c);
        g.setStroke(new BasicStroke(1.5f));
        for (int i = 1; i < trail.size(); i++) {
            MovementSample a = trail.get(i - 1);
            MovementSample b = trail.get(i);
            g.drawLine((int) a.x, (int) a.y, (int) b.x, (int) b.y);
        }
    }

    // -------------------------------------------------------------------------
    // Recording logic
    // -------------------------------------------------------------------------

    private void onCanvasMouse(int x, int y) {
        if (!recording) return;
        currentSamples.add(new MovementSample(x, y, System.currentTimeMillis()));
        canvas.repaint();
    }

    private void onCanvasClick(int x, int y) {
        if (!recording) return;
        double dx = x - targetX;
        double dy = y - targetY;
        if (dx * dx + dy * dy > TARGET_RADIUS * (double) TARGET_RADIUS) {
            return; // click missed target
        }

        // Finalise this acquisition
        currentSamples.add(new MovementSample(x, y, System.currentTimeMillis()));
        List<MovementSample> acquired = new ArrayList<>(currentSamples);
        history.add(acquired);
        currentSamples.clear();

        // Feed into aggregator
        aggregator.addAcquisition(acquired, targetX, targetY, TARGET_RADIUS,
                TARGET_RADIUS * 2.0, targetSpawnMs);

        // Classify for live summary
        FragmentClassifier.Result result =
                FragmentClassifier.classify(acquired, targetX, targetY, TARGET_RADIUS);
        if (result != null) {
            if (result.noiseFragment != null) {
                String nKey = result.distBucket + "_" + result.speedBucket;
                noiseCounts.merge(nKey, 1, Integer::sum);
                JLabel lbl = noiseCountLabels.get(nKey);
                if (lbl != null) lbl.setText(String.valueOf(noiseCounts.get(nKey)));
            }
            if (result.correctionFragment != null) {
                corrCounts.merge(result.magBucket, 1, Integer::sum);
                JLabel lbl = corrCountLabels.get(result.magBucket);
                if (lbl != null) lbl.setText(String.valueOf(corrCounts.get(result.magBucket)));
            }
        }

        movementCount++;
        movementsLabel.setText("Movements: " + movementCount);

        spawnTarget();
        canvas.repaint();
    }

    /** Places the next red target at a random canvas position ≥ MIN_SPAWN_DIST from the centre. */
    private void spawnTarget() {
        targetSpawnMs = System.currentTimeMillis();
        int w = Math.max(300, canvas.getWidth());
        int h = Math.max(300, canvas.getHeight());
        int cx = w / 2;
        int cy = h / 2;
        Random rng = new Random();
        for (int attempt = 0; attempt < 50; attempt++) {
            int x = TARGET_RADIUS + rng.nextInt(w - TARGET_RADIUS * 2);
            int y = TARGET_RADIUS + rng.nextInt(h - TARGET_RADIUS * 2);
            double dist = Math.hypot(x - cx, y - cy);
            if (dist >= MIN_SPAWN_DIST) {
                targetX = x;
                targetY = y;
                return;
            }
        }
        // Fallback: just place it offset from centre
        targetX = cx + MIN_SPAWN_DIST;
        targetY = cy;
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

    private void clearSession() {
        currentSamples.clear();
        history.clear();
        movementCount = 0;
        movementsLabel.setText("Movements: 0");
        initCounts();
        noiseCountLabels.values().forEach(l -> l.setText("0"));
        corrCountLabels.values().forEach(l -> l.setText("0"));
        spawnTarget();
        canvas.repaint();
    }

    private void processAndSave() {
        if (aggregator.getCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "No movements recorded yet. Move your mouse to the red circles and click them.",
                    "Nothing to save", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // File I/O on a worker thread; UI update on EDT afterward
        saveWorker.submit(() -> {
            try {
                // --- Tier 1: calibration.json ---
                CalibrationData existing = MouseProfileStore.loadCalibration();
                CalibrationData.ParamMap merged = aggregator.fitInto(existing);

                // Build a new CalibrationData with the merged params
                CalibrationData updated = new CalibrationData();
                updated.version      = 1;
                updated.sampleCount  = existing.sampleCount + aggregator.getCount();
                updated.contributors = existing.contributors + 1;
                updated.parameters   = merged;
                MouseProfileStore.saveCalibration(updated);

                // --- Tier 2: fragment pools ---
                Map<String, List<Fragment>> noiseByBucket      = new java.util.HashMap<>();
                Map<String, List<Fragment>> corrByBucket       = new java.util.HashMap<>();
                for (FragmentClassifier.Result fr : aggregator.getFragments()) {
                    if (fr.noiseFragment != null) {
                        String key = fr.distBucket + "_" + fr.speedBucket;
                        noiseByBucket.computeIfAbsent(key, k -> new ArrayList<>()).add(fr.noiseFragment);
                    }
                    if (fr.correctionFragment != null) {
                        corrByBucket.computeIfAbsent(fr.magBucket, k -> new ArrayList<>()).add(fr.correctionFragment);
                    }
                }
                for (Map.Entry<String, List<Fragment>> e : noiseByBucket.entrySet()) {
                    MouseProfileStore.saveNoiseFragments(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, List<Fragment>> e : corrByBucket.entrySet()) {
                    MouseProfileStore.saveCorrectionFragments(e.getKey(), e.getValue());
                }

                // --- Tier 2: micro-pauses.json ---
                // Simple default lognormal params (refined from reaction-time data if available)
                Map<String, double[]> pauses = new java.util.HashMap<>();
                pauses.put("short",  new double[]{3.8, 0.6});
                pauses.put("medium", new double[]{4.0, 0.6});
                pauses.put("long",   new double[]{4.2, 0.6});
                MouseProfileStore.saveMicroPauses(pauses);

                // --- Reload models ---
                CalibrationModel.reload(MouseProfileStore.CALIBRATION_PATH);
                FragmentLibrary.reload(MouseProfileStore.BASE_DIR);
                MicroPauseModel.reload(MouseProfileStore.MICRO_PAUSES_PATH);
                MouseV2Engine eng = MouseV2Engine.INSTANCE;
                if (eng != null) {
                    eng.invalidateProfileCache();
                }

                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(MouseProfileTunerFrame.this,
                                "Saved! " + aggregator.getCount() + " movements recorded.",
                                "Profile Updated", JOptionPane.INFORMATION_MESSAGE));
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(MouseProfileTunerFrame.this,
                                "Failed to save: " + ex.getMessage(),
                                "Save Error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void deleteMyData() {
        int choice = JOptionPane.showConfirmDialog(this,
                "This will delete your recorded calibration data and revert to the built-in defaults.\nContinue?",
                "Delete My Data", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        MouseProfileStore.deleteAll();
        CalibrationModel.resetToDefault();
        // Reload fragment library from an empty state
        FragmentLibrary.reload(null);
        MicroPauseModel.reload(null);
        MouseV2Engine eng = MouseV2Engine.INSTANCE;
        if (eng != null) {
            eng.invalidateProfileCache();
        }
        clearSession();
        JOptionPane.showMessageDialog(this,
                "Your recorded data has been deleted. The engine now uses the built-in defaults.",
                "Data Deleted", JOptionPane.INFORMATION_MESSAGE);
    }
}
