package net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration;

import java.util.HashMap;
import java.util.Map;

/**
 * Data-transfer object for the calibration.json file on disk. Mirrors the JSON schema so Gson can
 * serialise / deserialise it directly.
 * <p>
 * The {@link ParamMap} inner class wraps the {@code "parameters"} object and provides helpers for
 * the Welford-style weighted merge performed by {@link ProfileAggregator}.
 */
public final class CalibrationData {

    public int version      = 1;
    public int sampleCount  = 0;
    public int contributors = 0;
    public ParamMap parameters = new ParamMap();

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** One parameter entry inside the {@code "parameters"} map. */
    public static final class ParamEntry {
        public String distribution = "uniform";
        public Map<String, Double> params = new HashMap<>();
        public double clampLo = Double.NEGATIVE_INFINITY;
        public double clampHi = Double.POSITIVE_INFINITY;

        public ParamEntry() {}

        public ParamEntry(String distribution, Map<String, Double> params,
                          double clampLo, double clampHi) {
            this.distribution = distribution;
            this.params       = params;
            this.clampLo      = clampLo;
            this.clampHi      = clampHi;
        }

        /** Deep copy. */
        public ParamEntry copy() {
            return new ParamEntry(distribution, new HashMap<>(params), clampLo, clampHi);
        }
    }

    /**
     * The map of parameter entries, with helpers for the weighted merge step.
     */
    public static final class ParamMap {
        /** Raw map backing; key = parameter name, value = distribution entry. */
        public Map<String, ParamEntry> data = new HashMap<>();

        public ParamMap() {}

        /**
         * Returns a deep copy of this map (so the merge can mutate it without touching the
         * original).
         */
        public ParamMap deepCopy() {
            ParamMap out = new ParamMap();
            for (Map.Entry<String, ParamEntry> e : data.entrySet()) {
                out.data.put(e.getKey(), e.getValue().copy());
            }
            return out;
        }

        /**
         * Weighted merge of a new {@code estimate} into an existing {@code uniform} entry.
         * If no entry exists yet the method creates one with the provided default clamping range.
         * <p>
         * The new min/max widen toward {@code estimate} (rather than collapsing), so sparse data
         * cannot shrink the distribution below its prior span.
         *
         * @param key         parameter name
         * @param estimate    new observed value for this parameter
         * @param weight      weight of the new session in [0,1] (= newCount/totalCount)
         * @param defaultLo   default clampLo used when creating a new entry
         * @param defaultHi   default clampHi used when creating a new entry
         */
        public void mergeUniform(String key, double estimate, double weight,
                                 double defaultLo, double defaultHi) {
            if (Double.isNaN(estimate) || Double.isInfinite(estimate)) {
                return;
            }
            ParamEntry existing = data.get(key);
            if (existing == null) {
                // First observation: set up a fresh entry centered on the estimate
                Map<String, Double> p = new HashMap<>();
                p.put("min", estimate);
                p.put("max", estimate);
                data.put(key, new ParamEntry("uniform", p, defaultLo, defaultHi));
                return;
            }

            // Weighted blend of min/max
            double prevMin = existing.params.getOrDefault("min", estimate);
            double prevMax = existing.params.getOrDefault("max", estimate);

            double newMin = prevMin * (1.0 - weight) + Math.min(estimate, prevMin) * weight;
            double newMax = prevMax * (1.0 - weight) + Math.max(estimate, prevMax) * weight;

            // Never collapse below a minimum span (avoid degenerate distributions)
            double minSpan = Math.max(1e-6, (defaultHi - defaultLo) * 0.02);
            if (newMax - newMin < minSpan) {
                double mid = (newMin + newMax) / 2.0;
                newMin = mid - minSpan / 2.0;
                newMax = mid + minSpan / 2.0;
            }

            existing.params.put("min", newMin);
            existing.params.put("max", newMax);
        }
    }
}
