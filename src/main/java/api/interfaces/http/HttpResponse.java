package api.interfaces.http;

/** Minimal response  contract */
public interface HttpResponse {
    void status(int code, String reason);
    void header(String name, String value);
    void body(String text);
}
