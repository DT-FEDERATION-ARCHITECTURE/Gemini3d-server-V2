package gemini3d.server.config;

import gemini3d.server.websocket.InclusionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InclusionWebSocketHandler inclusionHandler;

    public WebSocketConfig(InclusionWebSocketHandler inclusionHandler) {
        this.inclusionHandler = inclusionHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(inclusionHandler, "/ws/inclusion/{sessionId}")
                .setAllowedOrigins("http://localhost:3000", "http://localhost:3001");
    }
}
