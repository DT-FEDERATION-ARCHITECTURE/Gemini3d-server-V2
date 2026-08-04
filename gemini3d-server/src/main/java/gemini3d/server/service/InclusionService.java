package gemini3d.server.service;

import gemini3d.monitoring.sli.FederatedConfig;
import gemini3d.monitoring.sli.FederatedMonitorSLI;
import gemini3d.monitoring.sli.IMonitorSemantics;
import gemini3d.monitoring.sli.MonitorId;
import gemini3d.monitoring.sli.MonitorInput;
import gemini3d.monitoring.sli.MonitorState;
import gemini3d.monitoring.sli.Verdict;
import gemini3d.monitoring.sli.VerdictBatch;
import gemini3d.server.model.InclusionSession;
import gemini3d.server.model.InclusionStepMessage;
import gemini3d.server.model.InclusionStepMessage.VerdictDto;
import gemini3d.server.monitoring.MonitoringSessionService;
import gemini3d.server.monitoring.TickBridge;
import gemini3d.server.websocket.InclusionWebSocketHandler;
import gemini3d.trace.membership.InclusionRunner;

import org.digitaltwin.automaton.model.Automaton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class InclusionService {

    private static final Logger log = LoggerFactory.getLogger(InclusionService.class);

    private final AutomatonService automatonService;
    private final TraceService traceService;
    private final InclusionWebSocketHandler webSocketHandler;
    private final MonitoringSessionService monitoring;

    @Value("${gemini3d.emulator.period-ms:500}")
    private long defaultPeriodMs;

    private volatile InclusionRunner.EmulatorMode emulatorMode = InclusionRunner.EmulatorMode.FIXED_PERIOD;
    private volatile long emulatorPeriodMs = 500;

    private final Map<String, InclusionSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, InclusionRunner> runners = new ConcurrentHashMap<>();

    public InclusionService(AutomatonService automatonService,
                            TraceService traceService,
                            InclusionWebSocketHandler webSocketHandler,
                            MonitoringSessionService monitoring) {
        this.automatonService = automatonService;
        this.traceService = traceService;
        this.webSocketHandler = webSocketHandler;
        this.monitoring = monitoring;
    }

    public InclusionSession start(String traceFile) {
        Automaton automaton = automatonService.getCurrent()
                .orElseThrow(() -> new IllegalStateException("No automaton loaded. POST /api/load first."));

        Path csvPath = traceService.resolveTrace(traceFile);
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        InclusionSession session = new InclusionSession(sessionId, automaton.getName(), traceFile);
        sessions.put(sessionId, session);

        // ── Build the federated SLI for this session ──
        // Space.getStates() returns List<String> — not List<State>.
        Set<String> automatonStates = new LinkedHashSet<>(automaton.getSpace().getStates());
        Set<String> traceSignals = readCsvHeader(csvPath);

        TickBridge.TimeMode timeMode = emulatorMode == InclusionRunner.EmulatorMode.REAL_DELTA_T
                ? TickBridge.TimeMode.REAL_DELTA_T
                : TickBridge.TimeMode.FIXED_PERIOD;
        TickBridge bridge = new TickBridge(timeMode, emulatorPeriodMs);

        FederatedMonitorSLI federation = monitoring.createFederation(
                sessionId, automatonStates, traceSignals, bridge);

        // ── Build and configure the runner ──
        InclusionRunner runner = new InclusionRunner(automaton, csvPath.toString(), emulatorPeriodMs);
        runner.setEmulatorMode(emulatorMode);
        runner.setEmulatorPeriodMs(emulatorPeriodMs);
        runners.put(sessionId, runner);

        // ── Step callback ──
        runner.onStep(step -> {
            if ("OK".equals(step.verdict())) session.recordOk();
            else session.recordFail();

            // Bridge StepResult → MonitorInput (parses time_delta from measurement).
            MonitorInput input = bridge.toInput(
                    step.step(), step.measurement(), step.currentState());

            // Step the federated SLI: actions → execute-each → aggregate.
            FederatedConfig currentConfig = monitoring.getConfig(sessionId)
                    .orElse(federation.initial().iterator().next());
            var stepResult = federation.stepTick(input, currentConfig);
            monitoring.updateConfig(sessionId, stepResult.nextConfig());

            // Convert per-monitor verdicts to wire DTOs.
            Map<String, VerdictDto> verdictDtos = toDtos(federation, stepResult.batch());

            // 100% confidence for now (sensor uncertainty is a future feature).
            Map<String, Integer> confidence = new LinkedHashMap<>();
            for (String key : step.measurement().keySet()) {
                confidence.put(key, 100);
            }

            String globalStatus = stepResult.batch().verdicts().isEmpty()
                    ? null
                    : stepResult.batch().globalStatus().name();

            webSocketHandler.pushMessage(sessionId, InclusionStepMessage.step(
                    step.step(), input.tSeconds(),
                    step.measurement(), confidence,
                    step.previousState(), step.currentState(),
                    step.firedTransition(), step.enabledTransitions(),
                    step.verdict(), step.reason(),
                    step.valuation(), step.possibleConfigs(),
                    globalStatus, verdictDtos));
        });

        // ── Finished callback ──
        runner.onFinished(result -> {
            session.markFinished();
            runners.remove(sessionId);

            // Emit a final snapshot of every monitor as the closing verdicts.
            Map<String, VerdictDto> finalVerdicts = snapshotToVerdicts(sessionId, federation);

            webSocketHandler.pushMessage(sessionId,
                    InclusionStepMessage.finished(result.totalSteps(), result.ok(), result.fail(),
                                                   finalVerdicts));
            log.info("[{}] Finished: {} steps, {} ok, {} fail", sessionId,
                    result.totalSteps(), result.ok(), result.fail());

            // Federation intentionally NOT disposed here — the IHM may want to
            // read /api/sessions/{id}/properties/snapshot after the run ends.
            // The federation stays alive until the inclusion is explicitly
            // stopped or the server restarts.
        });

        // ── Error callback ──
        runner.onError(msg -> {
            session.markError(msg);
            runners.remove(sessionId);
            webSocketHandler.pushMessage(sessionId, InclusionStepMessage.error(msg));
            log.error("[{}] Error: {}", sessionId, msg);
        });

        Thread thread = new Thread(() -> {
            session.markRunning(Thread.currentThread(), Thread.currentThread());
            runner.run();
        }, "Inclusion-" + sessionId);
        thread.setDaemon(true);
        thread.start();

        log.info("[{}] Started: automaton='{}', trace='{}', mode={}, period={}ms",
                sessionId, automaton.getName(), traceFile, emulatorMode, emulatorPeriodMs);
        return session;
    }

    // ── Emulator config ──

    public void setEmulatorConfig(String mode, long periodMs) {
        this.emulatorMode = "REAL_DELTA_T".equalsIgnoreCase(mode)
                ? InclusionRunner.EmulatorMode.REAL_DELTA_T
                : InclusionRunner.EmulatorMode.FIXED_PERIOD;
        this.emulatorPeriodMs = periodMs;
        log.info("Emulator config: mode={}, period={}ms", this.emulatorMode, periodMs);
    }

    public Map<String, Object> getEmulatorConfig() {
        return Map.of("mode", emulatorMode.name(), "periodMs", emulatorPeriodMs);
    }

    public Optional<InclusionSession> getSession(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public void stopSession(String id) {
        InclusionRunner r = runners.get(id);
        if (r != null) r.stop();
        InclusionSession s = sessions.get(id);
        if (s != null) s.stop();
        monitoring.disposeFederation(id);
    }

    // ── Helpers ──

    /**
     * Read the header line of the CSV and return the column names as a
     * Set. Order preserved. {@code time_delta} and {@code date} are kept
     * — operators usually won't reference them in G3DL but excluding them
     * would impose a naming convention that isn't ours to set.
     */
    private static Set<String> readCsvHeader(Path csvPath) {
        try (var lines = Files.lines(csvPath, StandardCharsets.UTF_8)) {
            String header = lines.findFirst().orElse("");
            String[] cols = header.split(";");
            Set<String> set = new LinkedHashSet<>();
            for (String c : cols) set.add(c.trim());
            return set;
        } catch (IOException e) {
            log.warn("Could not read CSV header for {}: {}", csvPath, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Convert the federation's verdict batch into wire DTOs. Looks up each
     * monitor's kind + scope via {@code federation.lookup} — these are not
     * carried inside {@link Verdict} itself.
     */
    private static Map<String, VerdictDto> toDtos(FederatedMonitorSLI federation,
                                                   VerdictBatch batch) {
        Map<String, VerdictDto> out = new LinkedHashMap<>();
        for (var entry : batch.verdicts().entrySet()) {
            MonitorId id = entry.getKey();
            Verdict v = entry.getValue();
            IMonitorSemantics meta = federation.lookup(id).orElse(null);
            String kind  = meta != null ? meta.kind()  : "?";
            String scope = meta != null ? meta.scope() : "?";
            out.put(id.value(), new VerdictDto(kind, v.status().name(), scope, v.reason(), v.details()));
        }
        return out;
    }

    /**
     * Build the closing per-monitor map. We don't have last-tick verdicts
     * for monitors that didn't run on the last tick (out of scope), so we
     * synthesise from each monitor's internal config state — same approach
     * as {@code MonitoringSessionService.snapshot}, just shaped as VerdictDto.
     */
    private Map<String, VerdictDto> snapshotToVerdicts(String sessionId,
                                                       FederatedMonitorSLI federation) {
        Map<String, VerdictDto> out = new LinkedHashMap<>();
        FederatedConfig cfg = monitoring.getConfig(sessionId)
                .orElse(federation.initial().iterator().next());

        for (IMonitorSemantics m : federation.monitors()) {
            MonitorId mid = new MonitorId(m.id());
            MonitorState state = cfg.monitorStates().getOrDefault(
                    mid, m.initial().iterator().next());

            // Map state shape → status. Conservative: only Reached is ALLOW
            // (sticky); Armed/Waiting are still UNKNOWN; Idle/Unit are ALLOW
            // (no outstanding obligations).
            String status = switch (state) {
                case MonitorState.Reached ignored -> "ALLOW";
                case MonitorState.Unit ignored    -> "ALLOW";
                case MonitorState.Idle ignored    -> "ALLOW";
                case MonitorState.Armed ignored   -> "UNKNOWN";
                case MonitorState.Waiting ignored -> "UNKNOWN";
            };

            Map<String, Object> details = new LinkedHashMap<>();
            switch (state) {
                case MonitorState.Armed armed -> {
                    details.put("armedAt", armed.armedAtSeconds());
                    details.put("deadline", armed.deadlineSeconds());
                }
                case MonitorState.Waiting waiting -> details.put("deadline", waiting.deadlineSeconds());
                case MonitorState.Reached reached -> details.put("reachedAt", reached.reachedAtSeconds());
                default -> { /* no extra detail */ }
            }

            out.put(m.id(), new VerdictDto(m.kind(), status, m.scope(), null, details));
        }
        return out;
    }
}
