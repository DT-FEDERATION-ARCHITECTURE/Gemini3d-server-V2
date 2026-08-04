package gemini3d.server.controller;

import gemini3d.monitoring.parser.ParseError;
import gemini3d.monitoring.sli.FederatedMonitorSLI.RegistrationResult;
import gemini3d.monitoring.validator.ValidationError;
import gemini3d.server.monitoring.MonitoringSessionService;
import gemini3d.server.monitoring.MonitoringSessionService.PropertyNotFoundException;
import gemini3d.server.monitoring.MonitoringSessionService.PropertySummary;
import gemini3d.server.monitoring.MonitoringSessionService.SessionNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/sessions/{sessionId}/properties")
@Tag(name = "Properties", description = "G3DL property CRUD per inclusion session")
public class PropertiesController {

    private final MonitoringSessionService monitoring;

    public PropertiesController(MonitoringSessionService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping
    @Operation(summary = "List all properties registered for a session")
    public ResponseEntity<?> list(@PathVariable String sessionId) {
        try {
            List<PropertySummary> props = monitoring.listProperties(sessionId);
            return ResponseEntity.ok(props);
        } catch (SessionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    @GetMapping("/{propertyId}")
    @Operation(summary = "Get one property")
    public ResponseEntity<?> get(@PathVariable String sessionId, @PathVariable String propertyId) {
        try {
            return monitoring.getProperty(sessionId, propertyId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("Property '" + propertyId + "' not found")));
        } catch (SessionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    @PostMapping
    @Operation(summary = "Add a property",
            description = "Body: { \"source\": \"<G3DL text>\" }. "
                    + "On 400, the body contains structured parse or validation errors.")
    public ResponseEntity<?> add(@PathVariable String sessionId,
                                 @RequestBody Map<String, String> body) {
        String source = body.get("source");
        if (source == null || source.isBlank()) {
            return ResponseEntity.badRequest().body(error("'source' is required"));
        }
        try {
            RegistrationResult r = monitoring.addProperty(sessionId, source);
            return registrationResponse(r, HttpStatus.CREATED);
        } catch (SessionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    @PutMapping("/{propertyId}")
    @Operation(summary = "Replace a property by id (atomic — old kept on validation failure)")
    public ResponseEntity<?> replace(@PathVariable String sessionId,
                                     @PathVariable String propertyId,
                                     @RequestBody Map<String, String> body) {
        String source = body.get("source");
        if (source == null || source.isBlank()) {
            return ResponseEntity.badRequest().body(error("'source' is required"));
        }
        try {
            RegistrationResult r = monitoring.replaceProperty(sessionId, propertyId, source);
            return registrationResponse(r, HttpStatus.OK);
        } catch (SessionNotFoundException | PropertyNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    @DeleteMapping("/{propertyId}")
    @Operation(summary = "Remove a property by id")
    public ResponseEntity<?> remove(@PathVariable String sessionId,
                                    @PathVariable String propertyId) {
        try {
            boolean removed = monitoring.removeProperty(sessionId, propertyId);
            if (removed) return ResponseEntity.noContent().build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("Property '" + propertyId + "' not found"));
        } catch (SessionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    @GetMapping("/snapshot")
    @Operation(summary = "Read-only snapshot of every monitor's current internal state")
    public ResponseEntity<?> snapshot(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(monitoring.snapshot(sessionId));
        } catch (SessionNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    // ---- response helpers ----------------------------------------------

    private static ResponseEntity<?> registrationResponse(RegistrationResult r, HttpStatus successStatus) {
        return switch (r) {
            case RegistrationResult.Registered ok -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", ok.semantics().id());
                body.put("scope", ok.semantics().scope());
                body.put("kind", ok.semantics().kind());
                body.put("source", ok.semantics().source());
                yield ResponseEntity.status(successStatus).body(body);
            }
            case RegistrationResult.ParseFailed pf ->
                ResponseEntity.badRequest().body(Map.of(
                    "stage", "parse",
                    "errors", pf.errors().stream().map(PropertiesController::serialize).toList()));
            case RegistrationResult.ValidationFailed vf ->
                ResponseEntity.badRequest().body(Map.of(
                    "stage", "validation",
                    "errors", vf.errors().stream().map(PropertiesController::serialize).toList()));
        };
    }

    private static Map<String, Object> serialize(ParseError e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code().name());
        m.put("line", e.location().line());
        m.put("col", e.location().col());
        m.put("message", e.message());
        if (e.hint() != null) m.put("hint", e.hint());
        return m;
    }

    private static Map<String, Object> serialize(ValidationError e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code().name());
        m.put("line", e.location().line());
        m.put("col", e.location().col());
        m.put("message", e.message());
        if (e.hint() != null) m.put("hint", e.hint());
        return m;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
