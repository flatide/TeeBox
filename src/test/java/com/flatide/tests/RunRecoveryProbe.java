package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunRegistry;

import java.io.File;

/**
 * Starts a run registry over an existing data directory, as a server start does, and reports what
 * it found. {@link ArchivedInputRecoveryTest} runs it in a JVM of its own, with a small heap.
 */
public class RunRecoveryProbe {

    public static void main(String[] args) {
        File dataDir = new File(args[0]);
        RunRegistry registry = new RunRegistry(dataDir, 200, 50, 20, 24L * 3600_000L, 7L * 24 * 3600_000L);
        System.out.println("maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        System.out.println("runs=" + registry.countRuns(null));
        RunInfo detail = registry.getRunWithInput(args[1]);
        System.out.println("inputChars=" + String.valueOf(detail.properties.get("blob")).length());
    }
}
