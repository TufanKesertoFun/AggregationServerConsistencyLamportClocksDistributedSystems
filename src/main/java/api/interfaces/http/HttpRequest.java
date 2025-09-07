package api.interfaces.http;

/** Minimal request contract */
public interface HttpRequest {
    String method();
    String path();
}
