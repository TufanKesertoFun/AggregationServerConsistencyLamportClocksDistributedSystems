package org.example.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.example.interfaces.HttpHandler;
import org.example.interfaces.LamportClock;

/**
 * DefaultHttpHandler implements low-level HTTP wire formatting for requests and responses.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *   <li>No logic was changed; only explanatory comments were added.</li>
 *   <li>This class focuses purely on serialization/deserialization of HTTP messages.</li>
 *   <li>Lamport clock ticks are intentionally triggered on every outgoing response (send event).</li>
 * </ul>
 */
public class DefaultHttpHandler implements HttpHandler {

    /**
     * Builds a raw HTTP/1.1 request string with headers.
     *
     * @param method         HTTP method (e.g. "GET", "PUT").
     * @param path           resource path (must start with '/').
     * @param host           target host.
     * @param port           target port.
     * @param extraHeaders   optional map of additional headers (can be null).
     * @param contentLength  payload size in bytes.
     * @return complete HTTP request string ready to send.
     */
    @Override
    public String buildRequest(String method, String path, String host, int port,
                               Map<String, String> extraHeaders, int contentLength) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(":").append(port).append("\r\n");

        // Append user-specified headers
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }

        // Content-Length must always be present for well-formed HTTP/1.1
        sb.append("Content-Length: ").append(contentLength).append("\r\n\r\n");
        return sb.toString();
    }

    /**
     * Writes the full request (headers + body) to the output stream.
     *
     * @param out     destination stream.
     * @param headers request headers built via {@link #buildRequest}.
     * @param body    request payload; may be empty for GET.
     * @throws IOException if I/O fails during transmission.
     */
    @Override
    public void send(OutputStream out, String headers, byte[] body) throws IOException {
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush(); // ensure transmission completeness
    }

    /**
     * Reads the entire raw HTTP response as UTF-8 text.
     * <p>Sonar: Using {@link InputStream#readAllBytes()} is acceptable here
     * since message size is bounded by assignment context.</p>
     */
    @Override
    public String readRawResponse(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Converts a numeric HTTP status code into a standard reason phrase.
     * <p>Sonar: The switch expression is exhaustive for all codes used in this project.</p>
     */
    @Override
    public String reason(int code) {
        return switch (code) {
            case OK -> "OK";
            case CREATED -> "Created";
            case NO_CONTENT -> "No Content";
            case BAD_REQUEST -> "Bad Request";
            case NOT_FOUND -> "Not Found";
            case INTERNAL_SERVER_ERROR -> "Internal Server Error";
            default -> "Unknown";
        };
    }

    /**
     * Writes an HTTP response with no body.
     * <p>
     * The Lamport clock is ticked before sending (each send counts as an event).
     * </p>
     *
     * @param out         destination stream.
     * @param statusCode  HTTP status code (e.g., 200 or 404).
     * @param clock       Lamport clock instance.
     * @param nodeId      sender node identifier.
     * @throws IOException if writing to the stream fails.
     */
    @Override
    public void writeEmpty(OutputStream out, int statusCode,
                           LamportClock clock, String nodeId) throws IOException {
        // Tick on every send event (Lamport local event)
        clock.tick();
        String res =
                "HTTP/1.1 " + statusCode + " " + reason(statusCode) + "\r\n" +
                        "X-Lamport-Node: " + nodeId + "\r\n" +
                        "X-Lamport-Clock: " + clock.get() + "\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";

        out.write(res.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes an HTTP JSON response, including Lamport metadata headers.
     * <p>
     * Sonar: tick() is intentionally called before composing headers;
     * body encoding is UTF-8 by design for deterministic reproducibility.
     * </p>
     *
     * @param out         output stream to write to.
     * @param statusCode  HTTP status code (200, 201, etc.).
     * @param jsonBody    valid JSON string payload.
     * @param clock       Lamport clock (for ordering).
     * @param nodeId      sending node ID.
     * @throws IOException if I/O fails during write.
     */
    @Override
    public void writeJson(OutputStream out, int statusCode, String jsonBody,
                          LamportClock clock, String nodeId) throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);

        // Tick before send (each HTTP response is a Lamport event)
        clock.tick();

        String res =
                "HTTP/1.1 " + statusCode + " " + reason(statusCode) + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "X-Lamport-Node: " + nodeId + "\r\n" +
                        "X-Lamport-Clock: " + clock.get() + "\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";

        out.write(res.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }
}
