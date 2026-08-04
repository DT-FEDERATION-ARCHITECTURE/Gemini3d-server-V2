# Gemini3D Server — Mission 2 (SLI edition)

Server updated to consume `g3dl-monitoring v2` — the SLI rebuild.
Same WebSocket contract as before for the IHM, same REST surface for
property CRUD, but the monitoring core now speaks
`ISemanticTransitionRelation` and can compose with the rest of the WP4
stack as a peer SLI.

## Prerequisites

Three libraries installed to your local Maven repo (in this order):

```bash
# 1) automaton-semantics — your existing lib, unchanged
cd automaton-semantics
gradle publishToMavenLocal             # → org.digitaltwin:automaton-semantics:1.0.0-SNAPSHOT

# 2) gemini3d-trace — your existing lib, unchanged
cd gemini3d-trace
gradle publishToMavenLocal             # → gemini3d:gemini3d-trace:1.0-SNAPSHOT

# 3) g3dl-monitoring v2 (new — SLI edition)
cd g3dl-monitoring
gradle publishToMavenLocal             # → gemini3d:g3dl-monitoring:2.0.0
```

Then the server:

```bash
cd gemini3d-server
./gradlew bootRun                       # → http://localhost:8080
```

Swagger UI at `http://localhost:8080/swagger-ui.html`.

## What this server now does (architecturally)

```
trace runner → StepResult ─► TickBridge ─► MonitorInput
                                            │
                                            ▼
                                FederatedMonitorSLI.stepTick(input, config)
                                            │
                              ┌─────────────┴─────────────┐
                              ▼                           ▼
                       actions(input,cfg)         execute(monitorId,input,cfg)
                       (scope routing)            (per-monitor SLI delegation)
                              │                           │
                              └─────────────┬─────────────┘
                                            ▼
                              VerdictBatch + nextConfig
                                            │
                                            ▼
                                  WebSocket push
                                  (per-monitor verdicts + globalStatus)
```

Each per-tick step:
1. `TickBridge` builds a `MonitorInput` (`scope, valuation, tSeconds, step`)
   from the trace's `StepResult`. Parses real `tSeconds` from CSV
   `time_delta` when present.
2. `MonitoringSessionService` holds the federation + the current
   `FederatedConfig` for the session.
3. `InclusionService` calls `federation.stepTick(input, config)`,
   replaces the stored config with `stepResult.nextConfig()`, and pushes
   verdicts + globalStatus to the WebSocket.

## REST API

Unchanged: `/api/load`, `/api/automaton`, `/api/inclusion/start`,
`/api/inclusion/{id}/status`, `/api/inclusion/{id}/stop`,
`/api/emulator/*`, `/api/trace/*`, `/api/spec/automata`,
`/api/spec/automata/{id}`.

New (mission 2): all scoped to an inclusion session id (returned by
`POST /api/inclusion/start`):

```
GET    /api/sessions/{sid}/properties              list all
GET    /api/sessions/{sid}/properties/{pid}        get one
POST   /api/sessions/{sid}/properties              add (body: {"source":"<G3DL>"})
PUT    /api/sessions/{sid}/properties/{pid}        replace (atomic)
DELETE /api/sessions/{sid}/properties/{pid}        remove
GET    /api/sessions/{sid}/properties/snapshot     internal state of every monitor
```

On parse / validation failure, body is structured:

```json
{
  "stage": "validation",
  "errors": [
    {
      "code": "UNKNOWN_SIGNAL",
      "line": 1,
      "col": 28,
      "message": "Signal 'Presion' is not present in the trace schema.",
      "hint": "Did you mean 'Pression'?"
    }
  ]
}
```

## WebSocket payload

Sent at every trace tick on `/ws/inclusion/{sessionId}`:

```json
{
  "type": "step",
  "step": 1247,
  "tSeconds": 49.880,
  "measurement": { ... },
  "currentState": "PRINTING",
  "verdict": "OK",
  ...
  "globalPropertyStatus": "FAIL",
  "propertyVerdicts": {
    "door_safety": {
      "kind": "RESPONSE",
      "status": "FAIL",
      "scope": "PRINTING",
      "reason": "Reaction not satisfied within 2.0s of trigger",
      "details": { "armedAt": 47.840, "deadline": 49.840, "timeoutAt": 49.880 }
    }
  }
}
```

This matches the IHM contract from the mission-3 work — the IHM keeps
working without changes.

## Files

```
src/main/java/gemini3d/server/
├── Gemini3dServerApplication.java
├── config/                            unchanged
├── controller/
│   ├── AutomatonController.java       updated: no Mealy properties field
│   ├── EmulatorController.java        unchanged
│   ├── HealthController.java          unchanged
│   ├── InclusionController.java       unchanged
│   ├── PropertiesController.java      NEW — G3DL CRUD
│   ├── SpecController.java            updated: global automaton only
│   └── TraceController.java           unchanged
├── model/
│   ├── InclusionSession.java          unchanged
│   └── InclusionStepMessage.java      updated: VerdictDto + tSeconds + globalPropertyStatus
├── monitoring/                        NEW package
│   ├── MonitoringSessionService.java  owns FederatedMonitorSLI + FederatedConfig per session
│   └── TickBridge.java                StepResult → MonitorInput, parses time_delta
├── service/
│   ├── AutomatonService.java          updated: dropped Mealy extraction
│   ├── InclusionService.java          updated: drives federation.stepTick()
│   └── TraceService.java              unchanged
└── websocket/
    └── InclusionWebSocketHandler.java unchanged
```

Deleted: `model/MealyProperty.java`, `service/PropertyExtractor.java`.

## Verified

- Compiles clean with `javac --release 21` against the three real
  library jars (automaton-semantics, gemini3d-trace, g3dl-monitoring v2).
- 19 Java source files, zero errors, zero warnings.
- `g3dl-monitoring v2` itself was verified with 38/38 in-sandbox tests
  passing and the egm.csv replay reproducing the v1 numbers exactly
  (3,227 ALLOW, 0 UNKNOWN, **4,471 FAIL** on `Pression < 30 in PRINTING`
  across 7,698 ticks).

## Trying it

```bash
# 1) Health
curl http://localhost:8080/api/health

# 2) Load an automaton
curl -X POST http://localhost:8080/api/load \
     -H "Content-Type: application/json" \
     -d @your-automaton.json

# 3) Start inclusion (creates the federated SLI for this session)
curl -X POST http://localhost:8080/api/inclusion/start \
     -H "Content-Type: application/json" \
     -d '{"traceFile":"egm.csv"}'
# → { "sessionId":"abc12345", ... }

# 4) Register a G3DL property
curl -X POST http://localhost:8080/api/sessions/abc12345/properties \
     -H "Content-Type: application/json" \
     -d '{"source":"property pressure_safe: in PRINTING: Pression < 30"}'

# 5) List
curl http://localhost:8080/api/sessions/abc12345/properties

# 6) Snapshot — internal state of every monitor
curl http://localhost:8080/api/sessions/abc12345/properties/snapshot

# 7) Subscribe at ws://localhost:8080/ws/inclusion/abc12345
#    Every tick now carries propertyVerdicts + globalPropertyStatus.
```

## Heads-up

If you start an inclusion and register no properties, `propertyVerdicts`
is empty and `globalPropertyStatus` is `null` (no monitors active —
nothing to aggregate). That's intentional. The IHM's `MonitorsPage`
shows a "no properties registered" empty state in that case.
