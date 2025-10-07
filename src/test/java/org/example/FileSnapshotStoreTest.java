package org.example;

import org.example.interfaces.SnapshotStore;
import org.example.persistance.FileSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class FileSnapshotStoreTest {

    @TempDir Path tmp;

    @Test
    void saveThenLoadLatest() throws IOException {
        SnapshotStore store = new FileSnapshotStore(tmp, "weather");

        String json1 = "{\"id\":\"A\"}";
        store.save(json1);

        // latest.json should exist and be readable
        Path latest = tmp.resolve("latest.json");
        assertTrue(Files.exists(latest));
        assertEquals(json1, Files.readString(latest, StandardCharsets.UTF_8));

        // load returns latest content
        String loaded = store.load();
        assertEquals(json1, loaded);

        String json2 = "{\"id\":\"B\"}";
        store.save(json2);

        // latest should be updated
        assertEquals(json2, Files.readString(latest, StandardCharsets.UTF_8));
        assertEquals(json2, store.load());

        // There should also be at least one timestamped snapshot file
        long count = Files.list(tmp)
                .filter(p -> p.getFileName().toString().startsWith("weather-") && p.toString().endsWith(".json"))
                .count();
        assertTrue(count >= 2);
    }
}
