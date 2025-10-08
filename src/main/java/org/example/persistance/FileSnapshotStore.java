package org.example.persistance;

import org.example.interfaces.SnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * FileSnapshotStore provides simple file-based persistence for JSON snapshots.
 * <p>
 * Each snapshot is written both to:
 * <ul>
 *     <li>a timestamped file (e.g., {@code weather-20251008-103311-256.json})</li>
 *     <li>a stable file {@code latest.json} which always points to the newest snapshot</li>
 * </ul>
 * <b>SonarQube notes:</b>
 * <ul>
 *     <li>All file operations are atomic (via temporary files + atomic move).</li>
 *     <li>Exceptions are logged but not rethrown, since persistence failure should not crash the app.</li>
 *     <li>Directory creation uses {@link Files#createDirectories(Path)} for idempotence.</li>
 * </ul>
 */
public final class FileSnapshotStore implements SnapshotStore {

    // Directory where snapshot files are stored, e.g. src/main/resources/temp
    private final Path dir;

    // Base name for snapshot files, e.g. "weather"
    private final String baseName;

    // Timestamp pattern for unique filenames, ensures lexical ordering by time.
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /**
     * Constructs a file snapshot store.
     *
     * @param dir      directory to store snapshots in.
     * @param baseName prefix for snapshot file names.
     */
    public FileSnapshotStore(Path dir, String baseName) {
        this.dir = dir;
        this.baseName = baseName;
    }

    /** @return the standard path of the "latest.json" file. */
    private Path latestPath() {
        return dir.resolve("latest.json");
    }

    /**
     * Loads the most recent snapshot content as a JSON string.
     * <p>
     * It first checks {@code latest.json}, and if not present, finds the most
     * recent {@code baseName-*.json} file by lexicographic order.
     * </p>
     *
     * @return snapshot JSON, or {@code null} if no valid snapshot exists.
     */
    @Override
    public String load() {
        try {
            // Prefer latest.json if present (fastest path)
            Path latest = latestPath();
            if (Files.exists(latest)) {
                return Files.readString(latest, StandardCharsets.UTF_8);
            }

            // Otherwise, pick newest "baseName-*.json" by filename timestamp
            if (!Files.exists(dir)) return null;

            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> {
                            String f = p.getFileName().toString();
                            return f.startsWith(baseName + "-") && f.endsWith(".json");
                        })
                        // Lexical filename order matches chronological order due to timestamp pattern
                        .max(Comparator.comparing(Path::getFileName))
                        .map(p -> {
                            try {
                                return Files.readString(p, StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                // Sonar: intentionally fallback to null; skipping unreadable snapshot is safe.
                                return null;
                            }
                        })
                        .orElse(null);
            }

        } catch (IOException e) {
            // Sonar: persistence errors are non-fatal, so they are logged instead of propagated.
            System.err.println("Snapshot load failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Saves the given JSON snapshot to disk.
     * <p>
     * The method:
     * <ol>
     *     <li>Writes to a timestamped file (ensuring historical record).</li>
     *     <li>Overwrites {@code latest.json} for quick reloads.</li>
     *     <li>Uses atomic renames to avoid partial writes.</li>
     * </ol>
     *
     * @param json snapshot content to persist.
     */
    @Override
    public void save(String json) {
        try {
            // Ensure directory exists before writing
            Files.createDirectories(dir);

            // 1) Write unique timestamped snapshot
            String stamp = LocalDateTime.now().format(TS);
            Path unique = dir.resolve(baseName + "-" + stamp + ".json");

            // Use temporary file for atomic write (Sonar: avoids partial file on crash)
            Path tmpUnique = unique.resolveSibling(unique.getFileName() + ".tmp");
            Files.writeString(tmpUnique, json, StandardCharsets.UTF_8);

            // Atomic move to final file
            Files.move(tmpUnique, unique,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            // 2) Overwrite latest.json (always most recent snapshot)
            Path latest = latestPath();
            Path tmpLatest = latest.resolveSibling(latest.getFileName() + ".tmp");
            Files.writeString(tmpLatest, json, StandardCharsets.UTF_8);

            // Replace existing file atomically for safety
            Files.move(tmpLatest, latest,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            // Sonar: error is logged instead of rethrown to prevent full application failure.
            System.err.println("Snapshot save failed: " + e.getMessage());
        }
    }
}
