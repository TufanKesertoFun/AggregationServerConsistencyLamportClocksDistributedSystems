package org.example;

import org.example.client.ContentServer;
import org.example.client.GetClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientHelpersTest {

    @Test
    void contentServer_buildBody_parsesTextAndJson() throws Exception {
        Method buildBody = ContentServer.class.getDeclaredMethod("buildBody", Path.class);
        buildBody.setAccessible(true);

        Path txt = Files.createTempFile("w", ".txt");
        Files.writeString(txt, "id:IDX\nname:N\nstate:SA", StandardCharsets.UTF_8);
        byte[] jsonFromTxt = (byte[]) buildBody.invoke(null, txt);
        String s1 = new String(jsonFromTxt, StandardCharsets.UTF_8);
        assertTrue(s1.startsWith("{") && s1.contains("\"id\":\"IDX\""), s1);

        Path js = Files.createTempFile("w", ".json");
        Files.writeString(js, "{\"id\":\"JX\",\"name\":\"Json\",\"state\":\"SA\"}", StandardCharsets.UTF_8);
        byte[] jsonRaw = (byte[]) buildBody.invoke(null, js);
        String s2 = new String(jsonRaw, StandardCharsets.UTF_8);
        assertTrue(s2.contains("\"JX\""));
    }

    @Test
    void contentServer_parseHostPortPath_and_httpHelpers() throws Exception {
        assertEquals("host", invokeStr(ContentServer.class, "parseHost", "host:123/x"));
        assertEquals(123, (int) invokeInt(ContentServer.class, "parsePort", "host:123/x", 4567));
        assertEquals("/x", invokeStr(ContentServer.class, "parsePath", "host:123/x", "/weather.json"));
        assertEquals("HTTP/1.1 200 OK", invokeStr(ContentServer.class, "firstLine", "HTTP/1.1 200 OK\r\nH: v\r\n\r\nb"));
        assertEquals("b", invokeStr(ContentServer.class, "bodyOf", "HTTP/1.1 200 OK\r\n\r\nb"));
        assertEquals(200, (int) invokeInt(ContentServer.class, "statusCodeOf", "HTTP/1.1 200 OK"));
        assertEquals("v", invokeStr(ContentServer.class, "headerValue", "H: v\r\n\r\n", "H"));
    }

    @Test
    void getClient_helpers() throws Exception {
        assertEquals("host", invokeStr(GetClient.class, "parseHost", "host:789/x"));
        assertEquals(789, (int) invokeInt(GetClient.class, "parsePort", "host:789/x", 4567));
        assertEquals("/x", invokeStr(GetClient.class, "parsePath", "host:789/x", "/weather.json"));
        assertEquals("b", invokeStr(GetClient.class, "bodyOf", "HTTP/1.1 200 OK\r\n\r\nb"));
        assertEquals("HTTP/1.1 404 Not Found", invokeStr(GetClient.class, "statusLineOf", "HTTP/1.1 404 Not Found\r\nX:1\r\n"));
        assertEquals(404, (int) invokeInt(GetClient.class, "statusCodeOf", "HTTP/1.1 404 Not Found"));
        assertNull(invokeStr(GetClient.class, "headerValue", "X: 1\r\n\r\n", "Nope"));
    }

    // --- reflection helpers
    private static String invokeStr(Class<?> c, String m, Object... args) throws Exception {
        Class<?>[] types = types(args);
        Method mm = c.getDeclaredMethod(m, types);
        mm.setAccessible(true);
        Object out = mm.invoke(null, args);
        return (out == null) ? null : out.toString();
    }
    private static int invokeInt(Class<?> c, String m, Object... args) throws Exception {
        Class<?>[] types = types(args);
        Method mm = c.getDeclaredMethod(m, types);
        mm.setAccessible(true);
        Object out = mm.invoke(null, args);
        return ((Number) out).intValue();
    }
    private static Class<?>[] types(Object[] a) {
        Class<?>[] t = new Class<?>[a.length];
        for (int i=0;i<a.length;i++) t[i] = (a[i] instanceof Integer) ? int.class :
                (a[i] instanceof Long) ? long.class :
                        (a[i] instanceof String) ? String.class :
                                (a[i] instanceof Path) ? Path.class : a[i].getClass();
        return t;
    }
}
