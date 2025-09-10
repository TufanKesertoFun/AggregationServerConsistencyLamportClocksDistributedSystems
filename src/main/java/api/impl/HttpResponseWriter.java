package api.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpResponseWriter {

    private HttpResponseWriter() {}

    public static void write(OutputStream out, HttpResponseImpl res) throws IOException {
        if (!res.headers().containsKey("Connection")) {
            res.header("Connection", "close");
        }
        if (!res.headers().containsKey("Content-Length")) {
            res.header("Content-Length", String.valueOf(res.body().length));
        }

        OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.US_ASCII);

        // IMPORTANT: space after HTTP/1.1
        w.write("HTTP/1.1 " + res.status() + " " + res.reason() + "\r\n");

        for (Map.Entry<String, String> e : res.headers().entrySet()) {
            // add a space after colon (nicer + some clients expect it)
            w.write(e.getKey() + ": " + e.getValue() + "\r\n");
        }

        w.write("\r\n"); // end headers
        w.flush();

        out.write(res.body());
        out.flush();
    }
}
