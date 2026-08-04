package gemini3d.server.service;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages trace files (CSV).
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    @Value("${gemini3d.traces.directory:traces}")
    private String tracesDirectory;

    private Path tracesPath;

    @PostConstruct
    public void init() throws IOException {
        tracesPath = Paths.get(tracesDirectory).toAbsolutePath();
        if (!Files.exists(tracesPath)) {
            Files.createDirectories(tracesPath);
            log.info("Created traces directory: {}", tracesPath);
        }
        log.info("Traces directory: {} ({} files)", tracesPath, countFiles());
    }

    /**
     * Lists all available trace files.
     */
    public List<Map<String, Object>> listTraces() throws IOException {
        try (Stream<Path> files = Files.list(tracesPath)) {
            return files
                    .filter(p -> p.toString().endsWith(".csv"))
                    .sorted()
                    .map(p -> {
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count() - 1; // minus header
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "size", size,
                                    "measurements", lines
                            );
                        } catch (IOException e) {
                            return Map.<String, Object>of(
                                    "name", p.getFileName().toString(),
                                    "size", 0L,
                                    "measurements", 0L
                            );
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Uploads a trace file from the browser.
     *
     * @return the filename (used to reference in inclusion/start)
     */
    public String uploadTrace(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "uploaded-" + System.currentTimeMillis() + ".csv";
        }

        // Sanitize filename
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        Path target = tracesPath.resolve(filename);
        file.transferTo(target.toFile());

        log.info("Trace uploaded: {} ({} bytes)", filename, file.getSize());
        return filename;
    }

    /**
     * Resolves a trace filename to its absolute path.
     *
     * @throws IllegalArgumentException if file doesn't exist
     */
    public Path resolveTrace(String filename) {
        Path path = tracesPath.resolve(filename);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Trace file not found: " + filename);
        }
        return path;
    }

    public boolean traceExists(String filename) {
        return Files.exists(tracesPath.resolve(filename));
    }

    public Path getTracesPath() {
        return tracesPath;
    }

    private long countFiles() throws IOException {
        try (Stream<Path> files = Files.list(tracesPath)) {
            return files.filter(p -> p.toString().endsWith(".csv")).count();
        }
    }
}
