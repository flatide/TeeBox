package com.propertee.teebox;

public class TeeBoxMain {
    public static void main(String[] args) throws Exception {
        TeeBoxConfig config = TeeBoxConfig.fromArgs(args);
        System.setProperty("propertee.task.baseDir", new java.io.File(config.dataDir, "tasks").getAbsolutePath());

        final TeeBoxServer server = new TeeBoxServer(config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
            }
        }, "propertee-teebox-shutdown"));

        System.out.println("TeeBox listening on http://" + config.bindAddress + ":" + server.getPort() + "/admin");
        while (true) {
            Thread.sleep(60000L);
        }
    }
}
