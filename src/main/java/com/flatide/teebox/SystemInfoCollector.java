package com.flatide.teebox;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

public class SystemInfoCollector {
    private static final int MAX_FILES_WALK = 10000;
    // The dataDir size walk stats up to 3 × MAX_FILES_WALK files per collect(). The admin
    // dashboard's auto-refresh polls this every 5s per viewer, so the walk result (informational,
    // slow-moving) is cached briefly; live values (memory, uptime, disk free) stay uncached.
    private static final long DIR_SIZES_TTL_MS = 30_000L;

    private final TeeBoxConfig config;
    private final long startTimeMs;
    private volatile DirSizes cachedDirSizes;
    private final Object dirSizesLock = new Object();

    private static final class DirSizes {
        final long runs;
        final long tasks;
        final long scriptRegistry;
        final long computedAt;

        DirSizes(long runs, long tasks, long scriptRegistry, long computedAt) {
            this.runs = runs;
            this.tasks = tasks;
            this.scriptRegistry = scriptRegistry;
            this.computedAt = computedAt;
        }
    }

    public SystemInfoCollector(TeeBoxConfig config) {
        this.config = config;
        this.startTimeMs = System.currentTimeMillis();
    }

    public SystemInfo collect() {
        SystemInfo info = new SystemInfo();

        info.teeboxVersion = TeeBoxVersion.get();

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

        // Data directory sizes (TTL-cached — see DIR_SIZES_TTL_MS). Double-checked under
        // dirSizesLock so an expired TTL admits exactly one walker; concurrent callers briefly
        // block on the lock and then reuse the value it computed, instead of each re-walking up
        // to 3 × MAX_FILES_WALK files.
        DirSizes sizes = cachedDirSizes;
        if (sizes == null || System.currentTimeMillis() - sizes.computedAt >= DIR_SIZES_TTL_MS) {
            synchronized (dirSizesLock) {
                sizes = cachedDirSizes;
                if (sizes == null || System.currentTimeMillis() - sizes.computedAt >= DIR_SIZES_TTL_MS) {
                    sizes = new DirSizes(
                            dirSize(new File(dataDir, "runs")),
                            dirSize(new File(dataDir, "tasks")),
                            dirSize(new File(dataDir, "script-registry")),
                            System.currentTimeMillis());
                    cachedDirSizes = sizes;
                }
            }
        }
        info.runsDirSize = sizes.runs;
        info.tasksDirSize = sizes.tasks;
        info.scriptRegistryDirSize = sizes.scriptRegistry;
        info.totalDataSize = info.runsDirSize + info.tasksDirSize + info.scriptRegistryDirSize;

        // Paths and config
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
