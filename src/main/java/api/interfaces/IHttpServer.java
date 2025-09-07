package api.interfaces;

/*
AutoCloseable will help me to close my server automatically
 */
public interface IHttpServer extends AutoCloseable {
    void start(int port) throws Exception;
    @Override void close() throws Exception;
}
