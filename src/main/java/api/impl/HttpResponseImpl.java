package api.impl;

import api.interfaces.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponseImpl implements HttpResponse {
    private int status = 200;
    private String reason = "OK";
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    @Override
    public void status(int code, String reason) {
        this.status = code;
        this.reason = reason;
    }

    @Override
    public void header(String name, String value) {
        headers.put(name, value);
    }

    @Override
    public void body(String text) {
        this.body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    // getters used by writer
    public int status() { return status; }
    public String reason() { return reason; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body; }
}
