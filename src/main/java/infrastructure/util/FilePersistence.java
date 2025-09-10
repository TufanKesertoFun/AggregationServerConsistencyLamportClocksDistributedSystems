package infrastructure.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FilePersistence {
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    private FilePersistence() {}

    public static void saveAtomically(Object snapshot, Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".tmp");
        Path fin = dir.resolve(name + ".json");
        try (OutputStream os = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            os.write(gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
            os.flush();
            if (os instanceof FileOutputStream) ((FileOutputStream) os).getFD().sync();
        }
        Files.move(tmp, fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    public static <T> T loadOrNull(Path file, Class<T> cls) {
        try {
            if (!Files.exists(file)) return null;
            byte[] b = Files.readAllBytes(file);
            String s = new String(b, StandardCharsets.UTF_8);
            return (T) gson.fromJson(s, cls);
        } catch (Exception e) {
            return null;
        }
    }
}
