package gemini3d.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.digitaltwin.automaton.model.Automaton;
import org.digitaltwin.automaton.parser.AutomatonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;


@Service
public class AutomatonService {

    private static final Logger log = LoggerFactory.getLogger(AutomatonService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Automaton currentAutomaton;

    public Automaton loadFromJson(Map<String, Object> rawJson) throws Exception {
        String jsonString = objectMapper.writeValueAsString(rawJson)
                .replace("\"real\"", "\"float\"");
        Automaton automaton = AutomatonParser.parse(jsonString);

        this.currentAutomaton = automaton;

        log.info("Automaton loaded: '{}' — {} states, {} transitions",
                automaton.getName(),
                automaton.getSpace().getStates().size(),
                automaton.getTransitions().size());

        return automaton;
    }

    public Optional<Automaton> getCurrent() {
        return Optional.ofNullable(currentAutomaton);
    }

    public boolean isLoaded() {
        return currentAutomaton != null;
    }

    public void clear() {
        this.currentAutomaton = null;
    }
}
