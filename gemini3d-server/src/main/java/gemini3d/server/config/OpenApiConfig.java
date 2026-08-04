package gemini3d.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gemini3dOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Gemini3D Inclusion Engine API")
                        .description("""
                                REST API + WebSocket for the Gemini3D Digital Twin Inclusion Engine.
                                
                                **Workflow:**
                                1. `POST /api/load` — Send automaton from the editor
                                2. `POST /api/trace/upload` or use pre-loaded trace
                                3. `POST /api/inclusion/start` — Launch inclusion
                                4. `WS /ws/inclusion/{sessionId}` — Stream results in real-time
                                
                                **WebSocket:** Connect to `/ws/inclusion/{sessionId}` after starting.
                                Each step pushes a JSON message with measurement, spec state, verdict.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Gemini3D Team")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local dev")
                ));
    }
}
