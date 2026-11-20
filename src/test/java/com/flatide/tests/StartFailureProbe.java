package com.flatide.tests;

import com.flatide.teebox.TeeBoxMain;

/**
 * Starts a server as {@link TeeBoxMain} does, in a JVM where something else is running that would
 * outlive the main thread — as a thread that a failed start had left behind would. {@link
 * StartFailureTest} runs it in a JVM of its own, with a start that fails.
 */
public class StartFailureProbe {

    public static void main(String[] args) throws Exception {
        Thread left = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ended) {
                    // the JVM is going down
                }
            }
        }, "left-running");
        left.setDaemon(false);
        left.start();
        TeeBoxMain.main(args);
    }
}
