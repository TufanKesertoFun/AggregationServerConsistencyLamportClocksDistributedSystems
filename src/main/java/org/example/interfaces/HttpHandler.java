package org.example.interfaces;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * HttpHandler — generic interface for sending/receiving HTTP messages.
 * Only defines the wire-level methods. No Lamport or business logic.
 */
public interface HttpHandler {

    int OK = 200;
    int CREATED = 201;
    int NO_CONTENT = 204;
    int BAD_REQUEST = 400;
    int NOT_FOUND = 404;
    int INTERNAL_SERVER_ERROR = 500;

    /** Build full HTTP/1.1 request headers (no body). */
    String buildRequest(String method,
                        String path,
                        String host,
                        int port,
                        Map<String, String> extraHeaders,
                        int contentLength);

    /** Send prepared request headers and optional body. */
    void send(OutputStream out, String requestHeaders, byte[] body) throws IOException;

    /** Read entire HTTP response (status line, headers, body) into a single string. */
    String readRawResponse(InputStream in) throws IOException;

    /* ---------------- Server-side helpers ---------------- */

    /** Write JSON response. */
    void writeJson(OutputStream out, int status, String json,
                   org.example.interfaces.LamportClock clock, String nodeId) throws IOException;

    /** Write empty response (Content-Length: 0). */
    void writeEmpty(OutputStream out, int status,
                    org.example.interfaces.LamportClock clock, String nodeId) throws IOException;

    /** Map HTTP status codes to reason phrases. */
    String reason(int code);
}
