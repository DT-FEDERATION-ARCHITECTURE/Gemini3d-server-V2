package gemini3d.server.controller;

import gemini3d.server.service.InclusionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/emulator")
@Tag(name = "Emulator", description = "Configure emulator timing (speed control)")
public class EmulatorController {

    private final InclusionService inclusionService;

    public EmulatorController(InclusionService inclusionService) {
        this.inclusionService = inclusionService;
    }

    @GetMapping("/config")
    @Operation(summary = "Get emulator config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(inclusionService.getEmulatorConfig());
    }

    @PostMapping("/config")
    @Operation(summary = "Set emulator config",
            description = "mode: FIXED_PERIOD or REAL_DELTA_T. periodMs: ms between readings.")
    public ResponseEntity<Map<String, Object>> setConfig(@RequestBody Map<String, Object> body) {
        String mode = (String) body.getOrDefault("mode", "FIXED_PERIOD");
        long periodMs = body.containsKey("periodMs")
                ? ((Number) body.get("periodMs")).longValue() : 500;

        if (periodMs < 0 || periodMs > 30000) {
            return ResponseEntity.badRequest().body(Map.of("error", "periodMs must be 0-30000"));
        }

        inclusionService.setEmulatorConfig(mode, periodMs);
        return ResponseEntity.ok(Map.of(
                "success", true, "mode", mode, "periodMs", periodMs,
                "message", "Config updated. Takes effect on next inclusion start."));
    }
}