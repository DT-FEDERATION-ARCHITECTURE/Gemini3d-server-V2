package gemini3d.server.controller;

import gemini3d.server.service.GcodeService;
import gemini3d.trace.gcode.GcodeToolpath;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gcode")
@Tag(name = "Gcode", description = "Manage G-code files and their parsed toolpath (the 'desired piece')")
public class GcodeController {

    private final GcodeService gcodeService;

    public GcodeController(GcodeService gcodeService) {
        this.gcodeService = gcodeService;
    }

    @GetMapping("/list")
    @Operation(summary = "List available G-code files",
            description = "Returns all .gcode/.nc files in the gcode directory (pre-loaded + uploaded).")
    public ResponseEntity<Map<String, Object>> list() {
        try {
            List<Map<String, Object>> files = gcodeService.listGcodeFiles();
            return ResponseEntity.ok(Map.of(
                    "files", files,
                    "count", files.size(),
                    "directory", gcodeService.getGcodePath().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to list gcode files: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload a G-code file",
            description = "Uploads a .gcode file from the browser. Stored in the gcode directory. " +
                    "Re-uploading an existing filename invalidates its cached toolpath.")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File is empty"
            ));
        }

        try {
            String filename = gcodeService.uploadGcode(file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", filename,
                    "size", file.getSize(),
                    "message", "Gcode '" + filename + "' uploaded (" + file.getSize() + " bytes)"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Upload failed: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{filename}/toolpath")
    @Operation(summary = "Get the parsed toolpath for a G-code file",
            description = "Parses (or returns the cached parse of) the full toolpath in one shot -- " +
                    "this is the complete 'desired piece' geometry, available up front and independent " +
                    "of any live trace/session. Use extrudeBounds (not fullBounds) to fit a camera to " +
                    "the actual part, since travel/purge moves can extend past it.")
    public ResponseEntity<Map<String, Object>> toolpath(@PathVariable String filename) {
        try {
            GcodeToolpath tp = gcodeService.getToolpath(filename);
            return ResponseEntity.ok(Map.of(
                    "filename", filename,
                    "pointCount", tp.getPoints().size(),
                    "layerCount", tp.getLayerCount(),
                    "totalExtrudeLength", tp.getTotalExtrudeLength(),
                    "fullBounds", tp.getFullBounds(),
                    "extrudeBounds", tp.getExtrudeBounds(),
                    "unsupportedCommands", tp.getUnsupportedCommands(),
                    "points", tp.getPoints()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to parse gcode: " + e.getMessage()
            ));
        }
    }
}