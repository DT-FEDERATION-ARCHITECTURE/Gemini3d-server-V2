package gemini3d.server.model;

import java.util.List;
import java.util.Map;


public record InclusionStepMessage(
        String type,                                // "step", "finished", "error"
        int step,
        double tSeconds,
        Map<String, Object> measurement,
        Map<String, Integer> confidence,
        String previousState,
        String currentState,
        String firedTransition,
        List<String> enabledTransitions,
        String verdict,                             // automaton transition verdict (OK / FAIL)
        String reason,
        Map<String, Object> valuation,
        int possibleConfigs,
        String globalPropertyStatus,
        Map<String, VerdictDto> propertyVerdicts
) {

    /** Per-monitor verdict, carried in {@link #propertyVerdicts}. */
    public record VerdictDto(
            String kind,                            // INVARIANT | BAND | RESPONSE | REACH
            String status,                          // ALLOW | UNKNOWN | FAIL
            String scope,                           // scope state name
            String reason,                          // human-readable, may be null on ALLOW
            Map<String, Object> details             // structured evidence
    ) {}

    public static InclusionStepMessage step(
            int step, double tSeconds,
            Map<String, Object> measurement,
            Map<String, Integer> confidence,
            String prevState, String curState,
            String fired, List<String> enabled,
            String verdict, String reason,
            Map<String, Object> valuation, int configs,
            String globalPropertyStatus,
            Map<String, VerdictDto> propertyVerdicts) {
        return new InclusionStepMessage("step", step, tSeconds, measurement, confidence,
                prevState, curState, fired, enabled, verdict, reason,
                valuation, configs, globalPropertyStatus, propertyVerdicts);
    }

    public static InclusionStepMessage finished(int totalSteps, int ok, int fail,
                                                Map<String, VerdictDto> finalVerdicts) {
        String verdict = fail == 0 ? "CONFORMS" : "VIOLATIONS_DETECTED";
        return new InclusionStepMessage("finished", totalSteps, 0.0, Map.of(), Map.of(),
                null, null, null, List.of(), verdict,
                fail > 0 ? fail + " failures" : null,
                Map.of("ok", ok, "fail", fail), 0, null, finalVerdicts);
    }

    public static InclusionStepMessage error(String reason) {
        return new InclusionStepMessage("error", 0, 0.0, Map.of(), Map.of(),
                null, null, null, List.of(), "ERROR", reason,
                Map.of(), 0, null, Map.of());
    }
}
