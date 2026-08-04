package gemini3d.server.monitoring;

import gemini3d.monitoring.sli.FederatedConfig;
import gemini3d.monitoring.sli.FederatedMonitorSLI;
import gemini3d.monitoring.sli.FederatedMonitorSLI.RegistrationResult;
import gemini3d.monitoring.sli.IMonitorSemantics;
import gemini3d.monitoring.sli.MonitorId;
import gemini3d.monitoring.sli.MonitorState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class MonitoringSessionService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringSessionService.class);

    /** sessionId → federation. */
    private final Map<String, FederatedMonitorSLI> federations = new ConcurrentHashMap<>();

    /** sessionId → current FederatedConfig. Updated each tick by InclusionService. */
    private final Map<String, FederatedConfig> configs = new ConcurrentHashMap<>();

    /** sessionId → bridge (held for the lifetime of the inclusion run). */
    private final Map<String, TickBridge> bridges = new ConcurrentHashMap<>();

    /**
     * Create a fresh federation for an inclusion session.
     *
     * @param sessionId        inclusion session id
     * @param automatonStates  automaton state names (used by the validator to
     *                         resolve {@code in <STATE>} clauses, and by the
     *                         federation's {@code actions()} for scope routing)
     * @param traceSignals     CSV column names (used by the validator to
     *                         resolve signal references)
     * @param bridge           tick → input bridge for this session
     */
    public FederatedMonitorSLI createFederation(String sessionId,
                                                Set<String> automatonStates,
                                                Set<String> traceSignals,
                                                TickBridge bridge) {
        FederatedMonitorSLI federation = new FederatedMonitorSLI(automatonStates, traceSignals);
        federations.put(sessionId, federation);
        configs.put(sessionId, federation.initial().iterator().next());
        bridges.put(sessionId, bridge);
        log.info("[{}] Federated SLI created — {} states, {} signals",
                 sessionId, automatonStates.size(), traceSignals.size());
        return federation;
    }

    public void disposeFederation(String sessionId) {
        if (federations.remove(sessionId) != null) {
            log.info("[{}] Federated SLI disposed", sessionId);
        }
        configs.remove(sessionId);
        bridges.remove(sessionId);
    }

    public Optional<FederatedMonitorSLI> getFederation(String sessionId) {
        return Optional.ofNullable(federations.get(sessionId));
    }

    public Optional<TickBridge> getBridge(String sessionId) {
        return Optional.ofNullable(bridges.get(sessionId));
    }

    public Optional<FederatedConfig> getConfig(String sessionId) {
        return Optional.ofNullable(configs.get(sessionId));
    }

    /** Replace the federated config for a session after a tick. */
    public void updateConfig(String sessionId, FederatedConfig newConfig) {
        configs.computeIfPresent(sessionId, (k, v) -> newConfig);
    }

    public boolean hasSession(String sessionId) {
        return federations.containsKey(sessionId);
    }

    // ---- Property CRUD --------------------------------------------------

    public RegistrationResult addProperty(String sessionId, String source) {
        FederatedMonitorSLI fed = requireFederation(sessionId);
        RegistrationResult r = fed.addProperty(source);
        if (r instanceof RegistrationResult.Registered ok) {
            // A new monitor needs its initial state in the joint config.
            // The federation's existing config map has older monitors only;
            // patch in the freshly registered one.
            MonitorId newId = new MonitorId(ok.semantics().id());
            MonitorState newState = ok.semantics().initial().iterator().next();
            FederatedConfig current = configs.get(sessionId);
            if (current != null) {
                configs.put(sessionId, current.with(newId, newState));
            }
            log.info("[{}] Property registered: {} ({})",
                     sessionId, ok.semantics().id(), ok.semantics().kind());
        }
        return r;
    }

    public boolean removeProperty(String sessionId, String propertyId) {
        FederatedMonitorSLI fed = requireFederation(sessionId);
        boolean removed = fed.removeProperty(new MonitorId(propertyId));
        if (removed) log.info("[{}] Property removed: {}", sessionId, propertyId);
        return removed;
    }

    /**
     * Replace a property atomically — on parse/validation failure the old
     * property remains. On success the old monitor's state is dropped (the
     * new monitor starts at its own initial state).
     */
    public RegistrationResult replaceProperty(String sessionId, String propertyId, String source) {
        FederatedMonitorSLI fed = requireFederation(sessionId);

        Optional<IMonitorSemantics> existing = fed.lookup(new MonitorId(propertyId));
        if (existing.isEmpty()) throw new PropertyNotFoundException(sessionId, propertyId);

        String oldSource = existing.get().source();
        fed.removeProperty(new MonitorId(propertyId));

        RegistrationResult r = fed.addProperty(source);
        if (!(r instanceof RegistrationResult.Registered)) {
            // Roll back.
            if (oldSource != null) fed.addProperty(oldSource);
        } else {
            // Reset the joint-config entry for this monitor to its initial state.
            var ok = (RegistrationResult.Registered) r;
            MonitorId mid = new MonitorId(ok.semantics().id());
            MonitorState newState = ok.semantics().initial().iterator().next();
            FederatedConfig current = configs.get(sessionId);
            if (current != null) {
                configs.put(sessionId, current.with(mid, newState));
            }
            log.info("[{}] Property replaced: {}", sessionId, propertyId);
        }
        return r;
    }

    public List<PropertySummary> listProperties(String sessionId) {
        FederatedMonitorSLI fed = requireFederation(sessionId);
        List<PropertySummary> out = new ArrayList<>();
        for (IMonitorSemantics m : fed.monitors()) {
            out.add(new PropertySummary(m.id(), m.scope(), m.kind(), m.source()));
        }
        return out;
    }

    public Optional<PropertySummary> getProperty(String sessionId, String propertyId) {
        FederatedMonitorSLI fed = requireFederation(sessionId);
        return fed.lookup(new MonitorId(propertyId))
                  .map(m -> new PropertySummary(m.id(), m.scope(), m.kind(), m.source()));
    }

    /**
     * Read-only snapshot of every monitor — id, kind, scope, current status
     * (derived from the joint config), and the live internal state.
     *
     * <p>"Current status" here is the status of the last verdict produced
     * for this monitor in this session; we don't store it on the config so
     * the snapshot only reports the monitor's internal config-state, which
     * the IHM can render. Status is reported from the live config via the
     * monitor's interpretation of its own state — e.g. {@code Reached} →
     * ALLOW (sticky), {@code Armed} → currently UNKNOWN (waiting), etc.
     */
    public List<Map<String, Object>> snapshot(String sessionId) {
        FederatedMonitorSLI fed = requireFederation(sessionId);
        FederatedConfig cfg = configs.get(sessionId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (IMonitorSemantics m : fed.monitors()) {
            MonitorId id = new MonitorId(m.id());
            MonitorState state = cfg != null
                    ? cfg.monitorStates().getOrDefault(id, m.initial().iterator().next())
                    : m.initial().iterator().next();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.id());
            entry.put("kind", m.kind());
            entry.put("scope", m.scope());
            entry.put("internalState", describeState(state));
            out.add(entry);
        }
        return out;
    }

    /** Render a MonitorState into a small map the IHM can display. */
    private static Map<String, Object> describeState(MonitorState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (s) {
            case MonitorState.Unit ignored -> m.put("kind", "Unit");
            case MonitorState.Idle ignored -> m.put("kind", "Idle");
            case MonitorState.Armed armed -> {
                m.put("kind", "Armed");
                m.put("armedAt", armed.armedAtSeconds());
                m.put("deadline", armed.deadlineSeconds());
            }
            case MonitorState.Waiting waiting -> {
                m.put("kind", "Waiting");
                m.put("deadline", waiting.deadlineSeconds());
            }
            case MonitorState.Reached reached -> {
                m.put("kind", "Reached");
                m.put("reachedAt", reached.reachedAtSeconds());
            }
        }
        return m;
    }

    // ---- helpers / DTOs / exceptions ------------------------------------

    private FederatedMonitorSLI requireFederation(String sessionId) {
        FederatedMonitorSLI fed = federations.get(sessionId);
        if (fed == null) throw new SessionNotFoundException(sessionId);
        return fed;
    }

    /** Compact view of a property for the IHM list view. */
    public record PropertySummary(String id, String scope, String kind, String source) {}

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String sessionId) {
            super("No monitoring federation for session '" + sessionId + "'");
        }
    }

    public static class PropertyNotFoundException extends RuntimeException {
        public PropertyNotFoundException(String sessionId, String propertyId) {
            super("Property '" + propertyId + "' not found in session '" + sessionId + "'");
        }
    }
}
