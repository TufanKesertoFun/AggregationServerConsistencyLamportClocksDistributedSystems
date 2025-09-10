package api.interfaces.http;

/** Minimal request contract */
public interface HttpRequest {
    String method();
    String path();

    // extras we need for handlers
    String version();
    String header(String name);
    byte[] body();
}
