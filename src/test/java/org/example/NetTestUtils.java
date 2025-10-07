package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class NetTestUtils {
    private NetTestUtils() {}

    /** Polls until the TCP port is open or times out. */
    public static void waitForPortOpen(String host, int port, long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        IOException last = null;
        while (System.currentTimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 200);
                return; // success
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        if (last != null) throw last;
    }
}
