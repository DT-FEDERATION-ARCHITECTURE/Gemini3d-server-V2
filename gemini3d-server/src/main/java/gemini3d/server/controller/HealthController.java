package gemini3d.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Server health check")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Returns server status. Used by frontend to check connectivity.")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "gemini3d-server",
                "version", "1.0.0"
        );
    }
}
