package org.example.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.example.interfaces.HttpHandler;
import org.example.interfaces.LamportClock;

public class DefaultHttpHandler implements HttpHandler {

    @Override
    public String buildRequest(String method, String path, String host, int port,
                               Map<String, String> extraHeaders, int contentLength) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(contentLength).append("\r\n\r\n");
        return sb.toString();
    }

    @Override
    public void send(OutputStream out, String headers, byte[] body) throws IOException {
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) out.write(body);
        out.flush();
    }

    @Override
    public String readRawResponse(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

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

    @Override
    public void writeEmpty(OutputStream out, int statusCode,
                           LamportClock clock, String nodeId) throws IOException {
        clock.tick();
        String res =
                "HTTP/1.1 " + statusCode + " " + reason(statusCode) + "\r\n" +
                        "X-Lamport-Node: " + nodeId + "\r\n" +
                        "X-Lamport-Clock: " + clock.get() + "\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(res.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeJson(OutputStream out, int statusCode, String jsonBody,
                          LamportClock clock, String nodeId) throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
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
