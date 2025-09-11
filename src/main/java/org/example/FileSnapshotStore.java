package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

public final class FileSnapshotStore implements SnapshotStore {
    private final Path dir;           // e.g., src/main/resources/temp
    private final String baseName;    // e.g., "weather"
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public FileSnapshotStore(Path dir, String baseName) {
        this.dir = dir;
        this.baseName = baseName;
    }

    private Path latestPath() { return dir.resolve("latest.json"); }

    @Override
    public String load() {
        try {
            // Prefer latest.json if present
            Path latest = latestPath();
            if (Files.exists(latest)) {
                return Files.readString(latest, StandardCharsets.UTF_8);
            }
            // Otherwise, pick newest "weather-*.json" by filename
            if (!Files.exists(dir)) return null;
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> {
                            String f = p.getFileName().toString();
                            return f.startsWith(baseName + "-") && f.endsWith(".json");
                        })
                        .max(Comparator.comparing(Path::getFileName))
                        .map(p -> {
                            try { return Files.readString(p, StandardCharsets.UTF_8); }
                            catch (IOException e) { return null; }
                        })
                        .orElse(null);
            }
        } catch (IOException e) {
            System.err.println("Snapshot load failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void save(String json) {
        try {
            Files.createDirectories(dir);

            // 1) Write a unique snapshot
            String stamp = LocalDateTime.now().format(TS);
            Path unique = dir.resolve(baseName + "-" + stamp + ".json");
            Path tmpUnique = unique.resolveSibling(unique.getFileName() + ".tmp");
            Files.writeString(tmpUnique, json, StandardCharsets.UTF_8);
            Files.move(tmpUnique, unique,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            // 2) Overwrite latest.json (always points to most recent)
            Path latest = latestPath();
            Path tmpLatest = latest.resolveSibling(latest.getFileName() + ".tmp");
            Files.writeString(tmpLatest, json, StandardCharsets.UTF_8);
            Files.move(tmpLatest, latest,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            System.err.println("Snapshot save failed: " + e.getMessage());
        }
    }
}
