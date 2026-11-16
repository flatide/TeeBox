package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.RunRequest;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Run ids must be unique, not just probably different. The id used to be the submit time to the
 * millisecond plus 16 bits of the nanosecond clock, so two submits in one millisecond could get
 * the same id — and the second run then replaced the first in the registry and in its file.
 */
public class RunIdTest {
    private static final Pattern RUN_ID = Pattern.compile("run-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{12}");

    @Test
    public void concurrentSubmitsNeverShareARunId() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-runid").toFile();
        final RunManager runManager = new RunManager(dataDir, 8);
        ExecutorService submitters = Executors.newFixedThreadPool(16);
        try {
            runManager.registerScriptVersion("quick", "1", "return 1\n", "quick", Arrays.asList("test"), true);
            final int perThread = 150;
            final CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> pending = new ArrayList<Future<List<String>>>();
            for (int t = 0; t < 16; t++) {
                pending.add(submitters.submit(new Callable<List<String>>() {
                    @Override
                    public List<String> call() throws Exception {
                        List<String> ids = new ArrayList<String>();
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            RunRequest request = new RunRequest();
                            request.scriptId = "quick";
                            RunInfo run = runManager.submit(request);
                            ids.add(run.runId);
                        }
                        return ids;
                    }
                }));
            }
            start.countDown();
            List<String> all = new ArrayList<String>();
            for (Future<List<String>> ids : pending) {
                all.addAll(ids.get());
            }

            Set<String> distinct = new HashSet<String>(all);
            Assert.assertEquals("two submits shared a run id", all.size(), distinct.size());
            Assert.assertEquals("every submit is its own run in the registry",
                all.size(), runManager.countRuns(null));
            for (String id : all) {
                Assert.assertTrue("unexpected run id shape: " + id, RUN_ID.matcher(id).matches());
            }
        } finally {
            submitters.shutdownNow();
            runManager.shutdown();
        }
    }
}
