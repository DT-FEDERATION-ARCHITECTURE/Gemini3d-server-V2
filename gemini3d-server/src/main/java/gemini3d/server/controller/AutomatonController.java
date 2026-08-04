package gemini3d.server.controller;

import gemini3d.server.service.AutomatonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.digitaltwin.automaton.model.Automaton;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Automaton", description = "Load and manage the automaton specification")
public class AutomatonController {

    private final AutomatonService automatonService;

    public AutomatonController(AutomatonService automatonService) {
        this.automatonService = automatonService;
    }

    @PostMapping("/load")
    @Operation(summary = "Load automaton",
            description = "Receives automaton JSON from the editor. Parses, validates, stores. "
                    + "Properties are no longer auto-extracted — register them via "
                    + "POST /api/sessions/{sid}/properties after starting an inclusion.")
    public ResponseEntity<Map<String, Object>> load(@RequestBody Map<String, Object> body) {
        try {
            Automaton automaton = automatonService.loadFromJson(body);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("id", automaton.getName());
            response.put("name", automaton.getName());
            response.put("states", automaton.getSpace().getStates().size());
            response.put("transitions", automaton.getTransitions().size());
            response.put("variables", automaton.getSpace().getVariables().size());
            response.put("message", "Automaton '" + automaton.getName() + "' loaded — "
                    + automaton.getSpace().getStates().size() + " states, "
                    + automaton.getTransitions().size() + " transitions");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Validation", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "ParseError",
                    "message", "Failed to parse automaton: " + e.getMessage()));
        }
    }

    @GetMapping("/automaton")
    @Operation(summary = "Get loaded automaton info")
    public ResponseEntity<Map<String, Object>> getAutomaton() {
        return automatonService.getCurrent()
                .map(a -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("loaded", true);
                    r.put("name", a.getName());
                    r.put("states", a.getSpace().getStates());
                    r.put("transitions", a.getTransitions().size());
                    r.put("variables", a.getSpace().getVariables().size());
                    return ResponseEntity.ok(r);
                })
                .orElse(ResponseEntity.ok(Map.of("loaded", false)));
    }
}
