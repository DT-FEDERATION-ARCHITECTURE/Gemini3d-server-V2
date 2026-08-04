package gemini3d.server.controller;

import gemini3d.server.service.AutomatonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.digitaltwin.automaton.model.Automaton;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/spec")
@Tag(name = "Spec Automata", description = "Global automaton spec for canvas rendering")
public class SpecController {

    private final AutomatonService automatonService;

    public SpecController(AutomatonService automatonService) {
        this.automatonService = automatonService;
    }

    @GetMapping("/automata")
    @Operation(summary = "List all spec automata",
            description = "Returns the global automaton (the only spec at this level "
                    + "since mission 2). For G3DL properties, query "
                    + "/api/sessions/{sid}/properties on the active inclusion session.")
    public ResponseEntity<Map<String, Object>> listAutomata() {
        if (!automatonService.isLoaded()) {
            return ResponseEntity.ok(Map.of("loaded", false, "automata", List.of()));
        }

        Automaton automaton = automatonService.getCurrent().get();

        Map<String, Object> global = new LinkedHashMap<>();
        global.put("id", "global");
        global.put("type", "global");
        global.put("name", automaton.getName());
        global.put("states", automaton.getSpace().getStates().size());
        global.put("transitions", automaton.getTransitions().size());

        List<Map<String, Object>> all = new ArrayList<>();
        all.add(global);

        return ResponseEntity.ok(Map.of(
                "loaded", true,
                "automata", all,
                "globalName", automaton.getName()
        ));
    }

    @GetMapping("/automata/{id}")
    @Operation(summary = "Get automaton by ID for canvas rendering",
            description = "id='global' → full automaton spec.")
    public ResponseEntity<Map<String, Object>> getAutomaton(@PathVariable String id) {
        if (!automatonService.isLoaded()) {
            return ResponseEntity.notFound().build();
        }

        if ("global".equals(id)) {
            return ResponseEntity.ok(buildGlobalResponse(automatonService.getCurrent().get()));
        }

        return ResponseEntity.notFound().build();
    }

    private Map<String, Object> buildGlobalResponse(Automaton a) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "global");
        result.put("type", "global");
        result.put("name", a.getName());

        String initialState = a.getInitial() != null ? a.getInitial().getState() : null;
        List<Map<String, Object>> states = new ArrayList<>();
        for (String s : a.getSpace().getStates()) {
            states.add(Map.of("name", s, "isInitial", s.equals(initialState)));
        }
        result.put("states", states);

        List<Map<String, Object>> transitions = new ArrayList<>();
        for (var t : a.getTransitions()) {
            transitions.add(Map.of(
                    "name", t.getName(),
                    "from", t.getFrom(),
                    "to", t.getTo(),
                    "guard", t.getGuard() != null ? t.getGuard() : "",
                    "action", t.getAction() != null ? t.getAction() : ""));
        }
        result.put("transitions", transitions);

        return result;
    }
}
