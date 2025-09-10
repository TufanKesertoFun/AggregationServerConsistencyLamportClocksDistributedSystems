package api.impl;

import api.interfaces.http.HttpRequest;
import java.util.Map;

public class MinimalHttpRequest implements HttpRequest {
    private final String method;
    private final String path;
    private final String version;
    private final Map<String,String> headers;
    private final byte[] body;

    public MinimalHttpRequest(String method, String path, String version,
                              Map<String,String> headers, byte[] body){
        this.method = method;
        this.path = path;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    @Override public String method(){ return method; }
    @Override public String path(){ return path; }
    @Override public String version(){ return version; }

    @Override
    public String header(String name){
        if (name == null) return null;
        return headers.getOrDefault(name, headers.getOrDefault(name.toLowerCase(), null));
    }

    @Override public byte[] body() { return body; }
}
