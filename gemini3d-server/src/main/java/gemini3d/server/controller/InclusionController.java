package gemini3d.server.controller;

import gemini3d.server.model.InclusionSession;
import gemini3d.server.service.InclusionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/inclusion")
@Tag(name = "Inclusion", description = "Run the inclusion engine (trace vs specification)")
public class InclusionController {

    private final InclusionService inclusionService;

    public InclusionController(InclusionService inclusionService) {
        this.inclusionService = inclusionService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start inclusion",
            description = """
                    Launches the inclusion pipeline: Emulator → TraceSLI → AutomatonSTR → Verdict.
                    
                    Requires: automaton loaded (POST /api/load) + trace file available.
                    Returns: sessionId for WebSocket subscription at /ws/inclusion/{sessionId}.
                    """)
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, String> body) {
        String traceFile = body.get("traceFile");
        if (traceFile == null || traceFile.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "traceFile is required"
            ));
        }

        try {
            InclusionSession session = inclusionService.start(traceFile);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionId", session.getId(),
                    "automaton", session.getAutomatonName(),
                    "traceFile", session.getTraceFile(),
                    "status", "RUNNING",
                    "websocket", "/ws/inclusion/" + session.getId(),
                    "message", "Inclusion started. Connect to WebSocket for real-time results."
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to start inclusion: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{sessionId}/status")
    @Operation(summary = "Get inclusion status",
            description = "Returns current status of a running or finished inclusion session.")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        return inclusionService.getSession(sessionId)
                .map(s -> ResponseEntity.ok(s.toStatusMap()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/stop")
    @Operation(summary = "Stop inclusion",
            description = "Force-stops a running inclusion. Interrupts emulator and sequencer threads.")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String sessionId) {
        return inclusionService.getSession(sessionId)
                .map(s -> {
                    inclusionService.stopSession(sessionId);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "sessionId", sessionId,
                            "status", "STOPPED",
                            "message", "Inclusion stopped"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
