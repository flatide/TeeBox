package com.propertee.teebox;

public class TeeBoxMain {
    public static void main(String[] args) throws Exception {
        RuntimePolicy.requireNonRoot();
        TeeBoxConfig config = TeeBoxConfig.fromArgs(args);
        System.setProperty("propertee.task.baseDir", new java.io.File(config.dataDir, "tasks").getAbsolutePath());

        final TeeBoxServer server = new TeeBoxServer(config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                // Use stderr directly — Log4j2 may already be shut down by its own hook
                System.err.println("[TeeBox] Shutting down");
                server.stop();
                System.err.println("[TeeBox] Shutdown complete");
            }
        }, "propertee-teebox-shutdown"));

        TeeBoxLog.info("TeeBox", "Listening on http://" + config.bindAddress + ":" + server.getPort() + "/admin");
        System.out.println("TeeBox listening on http://" + config.bindAddress + ":" + server.getPort() + "/admin");
        while (true) {
            Thread.sleep(60000L);
        }
    }
}
