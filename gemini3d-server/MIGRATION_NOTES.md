# Server migration notes — v0 → SLI

Exact list of every file change in this server bundle.

## Deleted

| File | Why |
|---|---|
| `src/main/java/gemini3d/server/model/MealyProperty.java` | Replaced by G3DL `Verdict` from `g3dl-monitoring v2` |
| `src/main/java/gemini3d/server/service/PropertyExtractor.java` | Auto-extraction of predicates dropped — properties are operator-authored G3DL |

## Added

| File | Role |
|---|---|
| `src/main/java/gemini3d/server/monitoring/TickBridge.java` | Converts trace `StepResult` into the SLI's `MonitorInput`. Parses real `tSeconds` from CSV `time_delta`. |
| `src/main/java/gemini3d/server/monitoring/MonitoringSessionService.java` | Owns one `FederatedMonitorSLI` + its evolving `FederatedConfig` per session. Single Spring-aware entry point into `g3dl-monitoring v2`. |
| `src/main/java/gemini3d/server/controller/PropertiesController.java` | REST CRUD for G3DL properties, scoped to an inclusion session |

## Modified

### `build.gradle`
Added one line: `implementation 'gemini3d:g3dl-monitoring:2.0.0'`.
All other deps unchanged.

### `model/InclusionStepMessage.java`
* New field `tSeconds` (double) — real trace time
* New field `globalPropertyStatus` (`ALLOW | UNKNOWN | FAIL | null`)
* Replaced `propertyStates: Map<String, String>` with
  `propertyVerdicts: Map<String, VerdictDto>` — structured per-monitor
  verdicts with kind, status, scope, reason, details
* Static factories updated; `ok/fail` collapsed into `step()` (runner
  already produces the verdict string).

### `service/AutomatonService.java`
* Removed `extractedProperties` field
* Removed `getExtractedProperties()`, `createFreshMealyProperties()`
* Removed import of `MealyProperty` and `PropertyExtractor`
* `loadFromJson()` no longer logs property count

### `service/InclusionService.java`
* Constructor now takes `MonitoringSessionService`
* `start()` builds a `FederatedMonitorSLI` for the session via the
  monitoring service, then drives it via
  `federation.stepTick(input, config)` on every trace tick
* Each tick:
  1. `TickBridge.toInput(step.step(), step.measurement(), step.currentState())`
  2. `monitoring.getConfig(sessionId)` for the current `FederatedConfig`
  3. `federation.stepTick(input, currentConfig)` → `(VerdictBatch, nextConfig)`
  4. `monitoring.updateConfig(sessionId, nextConfig)`
  5. Convert `VerdictBatch` → `Map<String, VerdictDto>` (looks up
     kind + scope from `federation.lookup(monitorId)`)
* `onFinished()` builds a final-state snapshot from each monitor's
  current internal `MonitorState`
* `stopSession()` disposes the federation via `monitoring.disposeFederation(id)`
* **Bug fix carried over from mission 2**: uses
  `automaton.getSpace().getStates()` directly as `List<String>` — earlier
  drafts incorrectly called `s.getName()` on what is in fact a bare
  String.

### `controller/AutomatonController.java`
* Removed all `properties` references in response payloads — the load
  response no longer reports property count

### `controller/SpecController.java`
* Removed the Mealy section — only the global automaton is now served
* The IHM's "list of automata" view should query
  `/api/sessions/{sid}/properties` for per-session monitors instead

## Unchanged

`Gemini3dServerApplication`, `CorsConfig`, `OpenApiConfig`,
`WebSocketConfig`, `EmulatorController`, `HealthController`,
`InclusionController`, `TraceController`, `InclusionSession`,
`TraceService`, `InclusionWebSocketHandler`, `application.properties`,
gradle wrapper.

## SLI wire-up at the line level

Where the v0 had:
```java
for (MealyProperty mp : properties) {
    mp.evaluate(step.measurement(), step.step());
}
Map<String, String> propStates = ...;
```

The new code has:
```java
MonitorInput input = bridge.toInput(
        step.step(), step.measurement(), step.currentState());
FederatedConfig currentConfig = monitoring.getConfig(sessionId)
        .orElse(federation.initial().iterator().next());
var stepResult = federation.stepTick(input, currentConfig);
monitoring.updateConfig(sessionId, stepResult.nextConfig());
Map<String, VerdictDto> verdictDtos = toDtos(federation, stepResult.batch());
```

Same role per tick, but threaded through the strict SLI contract:
`(input, config) → (batch, newConfig)`. The config carries every
monitor's internal state, so the state machines (Response IDLE/ARMED,
Reach Waiting/Reached) live in data rather than in mutable fields on
monitor objects.

## What did NOT change

The IHM doesn't need any changes — the WebSocket payload shape
(`tSeconds`, `globalPropertyStatus`, `propertyVerdicts`) is the same as
the mission-2 contract, and the property REST endpoints have the same
URLs and request/response bodies. The IHM's `MonitorsPage` continues to
work byte-for-byte with this server.
