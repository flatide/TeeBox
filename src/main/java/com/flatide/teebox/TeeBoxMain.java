package com.flatide.teebox;

public class TeeBoxMain {
    public static void main(String[] args) throws Exception {
        // First, and outside what is logged below: a start as root — or as a user who cannot be
        // told from root — is to leave nothing behind, and a log file made by root would keep
        // the server's own user from writing to it afterwards.
        RuntimePolicy.requireNonRoot();
        final TeeBoxConfig config;
        final TeeBoxServer server;
        try {
            config = TeeBoxConfig.fromArgs(args);
            System.setProperty("propertee.task.baseDir", new java.io.File(config.dataDir, "tasks").getAbsolutePath());
            server = new TeeBoxServer(config);
            server.start();
        } catch (Throwable failure) {
            startFailed(failure);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                // Use stderr directly — Log4j2 may already be shut down by its own hook
                System.err.println("[TeeBox] Shutting down");
                server.stop();
                System.err.println("[TeeBox] Shutdown complete");
            }
        }, "propertee-teebox-shutdown"));

        String banner = "TeeBox " + TeeBoxVersion.get()
                + " (engine " + TeeBoxVersion.engineVersion() + " @" + TeeBoxVersion.engineCommit() + ")"
                + " listening on http://" + config.bindAddress + ":" + server.getPort() + "/admin";
        TeeBoxLog.info("TeeBox", banner);
        System.out.println(banner);
        while (true) {
            Thread.sleep(60000L);
        }
    }

    /**
     * A start that fails ends the process, and says why where an operator looks.
     *
     * <p>It used to do neither reliably. The failure left the main thread as an uncaught
     * exception: the reason went to stderr alone — never into the log file — and the process
     * ended only when no other thread was running yet. Past that point (the port is taken) it
     * stayed, a server that listened nowhere and went on with its background work on the data
     * directory. What had been started is stopped where it fails by now (see TeeBoxServer and
     * RunManager); the exit here does not rely on that. Whatever is still running, and whatever
     * becomes of the report, the process ends: with exit code 1, as an uncaught exception ends it.
     */
    private static void startFailed(Throwable failure) {
        try {
            try {
                TeeBoxLog.error("TeeBox", "Start failed: " + failure, failure);
            } catch (Throwable loggingFailed) {
                failure.printStackTrace();
            }
            // Said on stderr itself as well: where the logging is set up without a console, or
            // cannot work at all, this line is what whoever started the process sees.
            System.err.println("[TeeBox] Start failed: " + failure);
        } finally {
            System.exit(1);
        }
    }
}
