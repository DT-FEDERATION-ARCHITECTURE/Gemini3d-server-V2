package gemini3d.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import gemini3d.server.model.InclusionStepMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;


@Component
public class  InclusionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InclusionWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // sessionId → list of WebSocket sessions watching it
    private final Map<String, List<WebSocketSession>> watchers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String inclusionId = extractSessionId(session);
        if (inclusionId != null) {
            watchers.computeIfAbsent(inclusionId, k -> new CopyOnWriteArrayList<>()).add(session);
            log.info("WebSocket connected: {} watching session {}", session.getId(), inclusionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String inclusionId = extractSessionId(session);
        if (inclusionId != null) {
            List<WebSocketSession> sessions = watchers.get(inclusionId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) watchers.remove(inclusionId);
            }
            log.info("WebSocket disconnected: {} from session {}", session.getId(), inclusionId);
        }
    }

    /**
     * Pushes a step message to ALL clients watching the given inclusion session.
     * Called by InclusionService from the sequencer thread.
     */
    public void pushMessage(String inclusionSessionId, InclusionStepMessage message) {
        List<WebSocketSession> sessions = watchers.get(inclusionSessionId);
        if (sessions == null || sessions.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);

            for (WebSocketSession ws : sessions) {
                if (ws.isOpen()) {
                    try {
                        synchronized (ws) {
                            ws.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to push to WebSocket {}: {}", ws.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize message: {}", e.getMessage());
        }
    }

    /**
     * Removes all watchers for a session (cleanup after inclusion finishes).
     */
    public void cleanupSession(String inclusionSessionId) {
        List<WebSocketSession> sessions = watchers.remove(inclusionSessionId);
        if (sessions != null) {
            for (WebSocketSession ws : sessions) {
                try {
                    if (ws.isOpen()) ws.close(CloseStatus.NORMAL);
                } catch (IOException ignored) {}
            }
        }
    }

    public boolean hasWatchers(String inclusionSessionId) {
        List<WebSocketSession> sessions = watchers.get(inclusionSessionId);
        return sessions != null && !sessions.isEmpty();
    }

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // /ws/inclusion/{sessionId}
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : null;
    }
}
