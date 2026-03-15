package com.propertee.teebox;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

public class SystemInfoCollector {
    private static final int MAX_FILES_WALK = 10000;

    private final TeeBoxConfig config;
    private final long startTimeMs;

    public SystemInfoCollector(TeeBoxConfig config) {
        this.config = config;
        this.startTimeMs = System.currentTimeMillis();
    }

    public SystemInfo collect() {
        SystemInfo info = new SystemInfo();

        // JVM info
        info.javaVersion = System.getProperty("java.version", "unknown");
        info.javaVendor = System.getProperty("java.vendor", "unknown");
        info.osName = System.getProperty("os.name", "unknown");
        info.osArch = System.getProperty("os.arch", "unknown");
        info.availableProcessors = Runtime.getRuntime().availableProcessors();
        info.uptimeMs = System.currentTimeMillis() - startTimeMs;

        // Memory
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
        info.heapUsed = heap.getUsed();
        info.heapMax = heap.getMax();
        info.nonHeapUsed = nonHeap.getUsed();
        info.nonHeapCommitted = nonHeap.getCommitted();

        // Disk (partition where dataDir lives)
        File dataDir = config.dataDir;
        info.diskTotal = dataDir.getTotalSpace();
        info.diskFree = dataDir.getFreeSpace();
        info.diskUsable = dataDir.getUsableSpace();

        // Data directory sizes
        info.runsDirSize = dirSize(new File(dataDir, "runs"));
        info.tasksDirSize = dirSize(new File(dataDir, "tasks"));
        info.scriptRegistryDirSize = dirSize(new File(dataDir, "script-registry"));
        info.totalDataSize = info.runsDirSize + info.tasksDirSize + info.scriptRegistryDirSize;

        // Paths and config
        info.scriptsRootPath = config.scriptsRoot.getAbsolutePath();
        info.dataDirPath = config.dataDir.getAbsolutePath();
        info.maxConcurrentRuns = config.maxConcurrentRuns;
        info.bindAddress = config.bindAddress;
        info.port = config.port;

        return info;
    }

    private long dirSize(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        long[] size = new long[]{0};
        int[] count = new int[]{0};
        walkSize(dir, size, count);
        return size[0];
    }

    private void walkSize(File dir, long[] size, int[] count) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            if (count[0] >= MAX_FILES_WALK) {
                return;
            }
            File child = children[i];
            if (child.isFile()) {
                size[0] += child.length();
                count[0]++;
            } else if (child.isDirectory()) {
                walkSize(child, size, count);
            }
        }
    }
}
