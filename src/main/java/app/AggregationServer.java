package app;

public class AggregationServer {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4567;
        System.out.println("App on running on port" + port + "...");
    }
}
