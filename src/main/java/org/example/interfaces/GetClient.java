package org.example.interfaces;

/** Contract for a simple GET client that returns the pretty-printed JSON body. */
public interface GetClient {
    /**
     * @param urlOrHostPort e.g. "http://localhost:4567" or "localhost:4567[/path]"
     * @param pathOrNull optional path (e.g. "/weather.json"); if null, parse from urlOrHostPort or default
     * @return pretty-printed JSON string (values quoted), or empty string if no body
     */
    String fetch(String urlOrHostPort, String pathOrNull) throws Exception;
}
