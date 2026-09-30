package gemini3d.server.controller;

import gemini3d.server.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/replay")
@Tag(name = "Replay", description = "Stream a trace's sensor data — no automaton, no verdicts")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start a trace replay",
            description = """
                    Streams a trace CSV row-by-row over the WebSocket, with no automaton
                    and no monitoring federation.

                    Use this when you only need the sensor stream (e.g. the 3D digital-twin
                    view). Use /api/inclusion/start instead when you want runtime
                    verification against a loaded automaton.

                    Body : { "traceFile": "...", "speed": 30 }

                      speed = secondes de trace jouees par seconde reelle
                        1   -> temps reel
                        30  -> trente fois plus vite
                        <=0 -> aucune attente (aussi vite que le WebSocket le permet)

                    La cadence suit les horodatages du fichier, pas un delai fixe : les
                    traces reelles vont de 4 ms a 504 ms d'echantillonnage, et l'une
                    d'elles varie elle-meme d'un facteur 400 en cours de route. Un delai
                    fixe ne peut donc pas convenir a plusieurs fichiers a la fois.

                    { "periodMs": 100 } reste accepte : ancien comportement a cadence
                    fixe, conserve pour ne casser aucun appelant existant. Si les deux
                    sont fournis, "speed" l'emporte.

                    Renvoie sessionId -- s'abonner sur /ws/inclusion/{sessionId}.
                    """)
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        Object traceFileRaw = body.get("traceFile");
        if (traceFileRaw == null || traceFileRaw.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "traceFile is required"));
        }
        String traceFile = traceFileRaw.toString();

        Object speedRaw = body.get("speed");
        Object periodRaw = body.get("periodMs");

        Double speed = null;
        if (speedRaw != null) {
            try {
                speed = Double.parseDouble(speedRaw.toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "speed must be a number"));
            }
            if (speed.isNaN() || speed.isInfinite()) {
                return ResponseEntity.badRequest().body(Map.of("error", "speed must be a finite number"));
            }
        }

        Long periodMs = null;
        if (periodRaw != null) {
            try {
                periodMs = Math.max(0L, Long.parseLong(periodRaw.toString()));
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "periodMs must be a number"));
            }
        }

        try {
            // "speed" l'emporte ; a defaut on garde strictement l'ancien chemin.
            ReplayService.ReplaySession s = (speed != null || periodMs == null)
                    ? replayService.start(traceFile, speed != null ? speed : 1.0)
                    : replayService.startFixedPeriod(traceFile, periodMs);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("sessionId", s.id);
            out.put("traceFile", s.traceFile);
            out.put("speed", s.speed);
            out.put("periodMs", s.periodMs);
            // Indicatif : 0 si le fichier ne porte pas d'horodatage exploitable.
            // Le client ne s'en sert que pour l'affichage, jamais pour cadencer.
            out.put("traceDurationSec", s.traceDurationSec);
            out.put("estimatedReplaySec", s.getEstimatedReplaySec());
            out.put("status", "RUNNING");
            out.put("websocket", "/ws/inclusion/" + s.id);
            out.put("message", "Replay started. Connect to the WebSocket for the live stream.");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start replay: " + e.getMessage()));
        }
    }

    @GetMapping("/{sessionId}/status")
    @Operation(summary = "Get replay status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        return replayService.getSession(sessionId)
                .map(s -> ResponseEntity.ok(s.toStatusMap()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/stop")
    @Operation(summary = "Stop a replay")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String sessionId) {
        return replayService.getSession(sessionId)
                .map(s -> {
                    replayService.stop(sessionId);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "sessionId", sessionId,
                            "status", "STOPPED",
                            "steps", s.getSteps(),
                            "message", "Replay stopped"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}