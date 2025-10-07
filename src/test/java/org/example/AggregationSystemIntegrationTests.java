package org.example;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.example.NetTestUtils.waitForPortOpen;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AggregationSystemIntegrationTests {

    private static Process server;
    private static int port;
    private static String javaExe;
    private static String classpath;

    @BeforeAll
    static void startServer() throws Exception {
        javaExe   = resolveJavaExe();
        classpath = System.getProperty("java.class.path");
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }

        ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp", classpath,
                "org.example.server.AggregationServer", String.valueOf(port));
        pb.redirectErrorStream(true);
        server = pb.start();

        // robust boot wait: poll the TCP port (works even if server prints nothing)
        waitForPortOpen("localhost", port, 8000);
    }


    @AfterAll
    static void stopServer() {
        if (server != null && server.isAlive()) {
            server.destroy();
            try { if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); }
            catch (InterruptedException ignored) {}
        }
    }

    // ---------- Tests (renamed to be behavior-descriptive) ----------

    @Test @Order(1)
    void givenFirstPut_whenNonEmpty_then201Created_andSubsequentPut_then200Ok() throws Exception {
        Path txt = createFile("id:IDS60901A\nname:Adelaide\nstate:SA"); // simulates weather.txt
        String first  = runContentServer("localhost:" + port, txt.toString());
        assertHasCode(first, 201);

        // also assert Lamport headers present in server response headers
        assertTrue(first.contains("X-Lamport-Node:"), "Expected Lamport Node header in response");
        assertTrue(first.contains("X-Lamport-Clock:"), "Expected Lamport Clock header in response");

        String second = runContentServer("localhost:" + port, txt.toString());
        assertHasCode(second, 200);
    }

    @Test @Order(2)
    void givenEmptyPut_then204NoContent_and_givenInvalidJson_then500() throws Exception {
        Path empty = createFile("");
        assertHasCode(runContentServer("localhost:" + port, empty.toString()), 204);

        Path bad = createFile("{ \"name\":\"missing id\" ");
        assertHasCode(runContentServer("localhost:" + port, bad.toString()), 500);
    }

    @Test @Order(3)
    void givenValidPut_whenGetWeather_then200AndJsonBody() throws Exception {
        Path json = createFile("{\"id\":\"IDSX\",\"name\":\"WX\",\"state\":\"SA\"}");
        runContentServer("localhost:" + port, json.toString());
        String out = runGetClient("localhost:" + port, "/weather.json");
        assertHasCode(out, 200);
        assertTrue(out.toLowerCase(Locale.ROOT).contains("idsx") || out.contains("WX"), "Body should reflect stored JSON");
    }

    @Test @Order(4)
    void givenInvalidEndpoints_then400BadRequestForPutAndGet() throws Exception {
        Path json = createFile("{\"id\":\"P1\"}");
        String putWrong = runMain("org.example.client.ContentServer",
                "http://localhost:" + port + "/not-weather", json.toString());
        assertHasCode(putWrong, 400);

        String getWrong = runMain("org.example.client.GetClient", "localhost:" + port, "/nope");
        assertHasCode(getWrong, 400);
    }

    @Test @Order(5)
    void givenConcurrentPuts_thenGetEventuallyReturns200() throws Exception {
        Path a = createFile("{\"id\":\"A\",\"name\":\"WEATHER A\"}");
        Path b = createFile("{\"id\":\"B\",\"name\":\"WEATHER B\"}");

        Process p1 = spawn("org.example.client.ContentServer", "localhost:" + port, a.toString());
        Process p2 = spawn("org.example.client.ContentServer", "localhost:" + port, b.toString());
        waitQuietly(p1, 5000);
        waitQuietly(p2, 5000);

        String out = runGetClient("localhost:" + port, "/weather.json");
        assertHasCode(out, 200);
    }

    @Test @Order(6)
    void givenGetBetweenTwoPuts_whenBarrierEnabled_thenFirstGetSeesA_thenLaterB() throws Exception {
        Path A = createFile("{\"id\":\"IDS60901A\",\"name\":\"WEATHER A\",\"state\":\"SA\"}");
        Path B = createFile("{\"id\":\"IDS60901B\",\"name\":\"WEATHER B\",\"state\":\"SA\"}");

        runContentServer("localhost:" + port, A.toString());
        // schedule B with small delay (simulate the PowerShell Start-Job)
        Process pb = spawn("org.example.client.ContentServer", "localhost:" + port, B.toString());
        waitQuietly(pb, 300); // small wait to let PUT B be processed soon

        String first = runGetClient("localhost:" + port, "/weather.json");
        assertHasCode(first, 200);
        assertTrue(first.contains("IDS60901A"), "Expected A visible first due to ordering barrier");

        // After a moment, B should be visible
        Thread.sleep(1200);
        String second = runGetClient("localhost:" + port, "/weather.json");
        assertHasCode(second, 200);
        assertTrue(second.contains("IDS60901B"), "Expected B visible after second PUT");
    }

    // ---------- helpers ----------

    private static String resolveJavaExe() {
        String bin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) bin += ".exe";
        return bin;
    }

    private static Path createFile(String content) throws IOException {
        Path f = Files.createTempFile("it-", ".txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        f.toFile().deleteOnExit();
        return f;
    }

    private static Process spawn(String mainClass, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(resolveJavaExe(), "-cp", classpath, mainClass);
        for (String a : args) pb.command().add(a);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static void waitQuietly(Process p, long ms) {
        try { p.waitFor(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        if (p.isAlive()) p.destroy();
    }

    private static String runContentServer(String hostPort, String file) throws Exception {
        return runMain("org.example.client.ContentServer", hostPort, file);
    }

    private static String runGetClient(String hostPort, String path) throws Exception {
        return runMain("org.example.client.GetClient", hostPort, path);
    }

    private static String runMain(String mainClass, String... args) throws Exception {
        Process p = spawn(mainClass, args);
        String out = readAll(p.getInputStream(), 7000);
        p.waitFor(7, TimeUnit.SECONDS);
        if (p.isAlive()) p.destroy();
        return out;
    }

    private static String readAll(InputStream in, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[2048];
        while (System.currentTimeMillis() < end) {
            int avail = in.available();
            if (avail > 0) {
                int n = in.read(buf, 0, Math.min(avail, buf.length));
                if (n > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } else {
                try { Thread.sleep(8); } catch (InterruptedException ignored) {}
            }
        }
        return sb.toString();
    }

    private static String readUntil(InputStream in, String needle, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1024];
        while (System.currentTimeMillis() < end) {
            int n = in.read(buf);
            if (n > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.toString().contains(needle)) break;
            } else {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        }
        return sb.toString();
    }

    private static void assertHasCode(String output, int code) {
        String s = output == null ? "" : output;
        boolean ok =
                s.contains("HTTP/1.1 " + code) ||               // headers shown by ContentServer
                        s.matches("(?s).*\\R\\s*" + code + "\\s+.*");    // "200 OK" on a line (GetClient)
        assertTrue(ok, "Expected HTTP " + code + " in output, got:\n" + s);
    }
}
