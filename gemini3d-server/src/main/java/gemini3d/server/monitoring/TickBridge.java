package gemini3d.server.monitoring;

import gemini3d.monitoring.sli.MonitorInput;

import java.util.LinkedHashMap;
import java.util.Map;


public final class TickBridge {

    /** Emulator pacing modes mirrored from {@code InclusionRunner.EmulatorMode}. */
    public enum TimeMode { FIXED_PERIOD, REAL_DELTA_T }

    private final TimeMode timeMode;
    private final long emulatorPeriodMs;
    private final long sessionStartNanos;

    public TickBridge(TimeMode timeMode, long emulatorPeriodMs) {
        this.timeMode = timeMode;
        this.emulatorPeriodMs = emulatorPeriodMs;
        this.sessionStartNanos = System.nanoTime();
    }

    /**
     * Build a {@link MonitorInput} from the trace runner's step output.
     *
     * @param stepNumber       monotonic step index from the runner
     * @param rawMeasurement   the {@code step.measurement()} map
     * @param currentState     current automaton state (becomes scope)
     */
    public MonitorInput toInput(int stepNumber,
                                Map<String, Object> rawMeasurement,
                                String currentState) {
        double tSeconds = computeTSeconds(stepNumber, rawMeasurement);
        Map<String, Double> coerced = coerceMeasurement(rawMeasurement);
        return new MonitorInput(currentState, coerced, tSeconds, stepNumber);
    }

    /**
     * Parse trace timestamp in seconds. Mirrors the {@code getTimeValue}
     * logic the runner uses internally for {@code REAL_DELTA_T} pacing, so
     * monitor time stays consistent with runner time.
     */
    double computeTSeconds(int stepNumber, Map<String, Object> measurement) {
        Object td = measurement.get("time_delta");
        if (td != null) {
            String s = td.toString();
            if (s.contains("days")) {
                Double parsed = parsePandasTimeDelta(s);
                if (parsed != null) return parsed;
            }
        }
        for (String key : measurement.keySet()) {
            if (key.equalsIgnoreCase("time") || key.equalsIgnoreCase("t")) {
                Double v = coerceNullable(measurement.get(key));
                if (v != null && !v.isNaN()) return v;
            }
        }
        return switch (timeMode) {
            case FIXED_PERIOD -> stepNumber * (emulatorPeriodMs / 1000.0);
            case REAL_DELTA_T -> (System.nanoTime() - sessionStartNanos) / 1_000_000_000.0;
        };
    }

    /** Parse {@code "N days HH:MM:SS.ffffff"} into seconds. */
    static Double parsePandasTimeDelta(String s) {
        try {
            String[] parts = s.split(" days ");
            if (parts.length != 2) return null;
            long days = Long.parseLong(parts[0].trim());
            String[] hms = parts[1].trim().split(":");
            if (hms.length != 3) return null;
            int hours = Integer.parseInt(hms[0]);
            int minutes = Integer.parseInt(hms[1]);
            double seconds = Double.parseDouble(hms[2]);
            return days * 86400.0 + hours * 3600.0 + minutes * 60.0 + seconds;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Coerce a {@code Map<String, Object>} measurement into the
     * {@code Map<String, Double>} the SLI consumes.
     *
     * <p>{@code time_delta} and {@code date} are dropped — they're not
     * numeric signals and operators wouldn't reference them in G3DL.
     */
    static Map<String, Double> coerceMeasurement(Map<String, Object> raw) {
        Map<String, Double> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if ("time_delta".equals(key) || "date".equals(key)) continue;
            out.put(key, coerce(e.getValue()));
        }
        return out;
    }

    static double coerce(Object value) {
        if (value == null) return Double.NaN;
        if (value instanceof Number n) return n.doubleValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return Double.NaN;
        s = s.replace(',', '.');
        try { return Double.parseDouble(s); }
        catch (NumberFormatException ex) { return Double.NaN; }
    }

    static Double coerceNullable(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString().trim().replace(',', '.')); }
        catch (NumberFormatException ex) { return null; }
    }
}
