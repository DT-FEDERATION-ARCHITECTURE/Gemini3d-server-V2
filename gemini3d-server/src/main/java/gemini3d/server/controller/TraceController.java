package gemini3d.server.controller;

import gemini3d.server.service.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trace")
@Tag(name = "Trace", description = "Manage trace files (CSV sensor data)")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping("/list")
    @Operation(summary = "List available traces",
            description = "Returns all CSV files in the traces directory (pre-loaded + uploaded).")
    public ResponseEntity<Map<String, Object>> list() {
        try {
            List<Map<String, Object>> traces = traceService.listTraces();
            return ResponseEntity.ok(Map.of(
                    "traces", traces,
                    "count", traces.size(),
                    "directory", traceService.getTracesPath().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to list traces: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload trace file",
            description = "Uploads a CSV trace file from the browser. Stored in the traces directory.")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File is empty"
            ));
        }

        try {
            String filename = traceService.uploadTrace(file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", filename,
                    "size", file.getSize(),
                    "message", "Trace '" + filename + "' uploaded (" + file.getSize() + " bytes)"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Upload failed: " + e.getMessage()
            ));
        }
    }
}
