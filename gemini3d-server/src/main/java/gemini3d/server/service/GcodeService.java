package gemini3d.server.service;

import gemini3d.trace.gcode.GcodeParser;
import gemini3d.trace.gcode.GcodeToolpath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages G-code files (list/upload/resolve -- same shape as TraceService)
 * and parses them into a {@link GcodeToolpath} via the gemini3d-trace
 * gcode package.
 *
 * Parsing is cached in memory per filename: a toolpath is parsed once, on
 * first request, and reused after that. Re-uploading a file with the same
 * name invalidates its cache entry so the next request re-parses it.
 */
@Service
public class GcodeService {

    private static final Logger log = LoggerFactory.getLogger(GcodeService.class);

    @Value("${gemini3d.gcode.directory:gcode}")
    private String gcodeDirectory;

    private Path gcodePath;

    /** filename -> parsed toolpath. Cleared per-file on re-upload. */
    private final Map<String, GcodeToolpath> toolpathCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        gcodePath = Paths.get(gcodeDirectory).toAbsolutePath();
        if (!Files.exists(gcodePath)) {
            Files.createDirectories(gcodePath);
            log.info("Created gcode directory: {}", gcodePath);
        }
        log.info("Gcode directory: {} ({} files)", gcodePath, countFiles());
    }

    /**
     * Lists all available G-code files.
     */
    public List<Map<String, Object>> listGcodeFiles() throws IOException {
        try (Stream<Path> files = Files.list(gcodePath)) {
            return files
                    .filter(p -> p.toString().endsWith(".gcode") || p.toString().endsWith(".nc"))
                    .sorted()
                    .map(p -> {
                        try {
                            long size = Files.size(p);
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "size", size,
                                    "cached", toolpathCache.containsKey(p.getFileName().toString())
                            );
                        } catch (IOException e) {
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "size", 0L,
                                    "cached", false
                            );
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Uploads a G-code file from the browser.
     *
     * @return the filename (used to reference in /toolpath)
     */
    public String uploadGcode(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "uploaded-" + System.currentTimeMillis() + ".gcode";
        }

        // Sanitize filename -- same policy as TraceService
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        Path target = gcodePath.resolve(filename);
        file.transferTo(target.toFile());

        // A re-upload of an existing name must not serve a stale parse.
        toolpathCache.remove(filename);

        log.info("Gcode uploaded: {} ({} bytes)", filename, file.getSize());
        return filename;
    }

    /**
     * Resolves a G-code filename to its absolute path.
     *
     * @throws IllegalArgumentException if the file doesn't exist
     */
    public Path resolveGcode(String filename) {
        Path path = gcodePath.resolve(filename);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Gcode file not found: " + filename);
        }
        return path;
    }

    public boolean gcodeExists(String filename) {
        return Files.exists(gcodePath.resolve(filename));
    }

    public Path getGcodePath() {
        return gcodePath;
    }

    /**
     * Returns the parsed toolpath for a file, parsing and caching it on
     * first request.
     *
     * @throws IllegalArgumentException if the file doesn't exist
     * @throws IOException              if parsing fails (unsupported/empty file, etc.)
     */
    public GcodeToolpath getToolpath(String filename) throws IOException {
        Path path = resolveGcode(filename); // throws IllegalArgumentException if missing

        GcodeToolpath cached = toolpathCache.get(filename);
        if (cached != null) {
            return cached;
        }

        GcodeToolpath parsed = GcodeParser.parse(path.toFile());
        toolpathCache.put(filename, parsed);
        log.info("Gcode parsed: {} ({} points, {} layers, {} unsupported command types)",
                filename, parsed.getPoints().size(), parsed.getLayerCount(),
                parsed.getUnsupportedCommands().size());
        return parsed;
    }

    private long countFiles() throws IOException {
        try (Stream<Path> files = Files.list(gcodePath)) {
            return files.filter(p -> p.toString().endsWith(".gcode") || p.toString().endsWith(".nc")).count();
        }
    }
}