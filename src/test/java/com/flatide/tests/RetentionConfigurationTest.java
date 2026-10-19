package com.flatide.tests;

import com.flatide.teebox.ManagedTaskEngine;
import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunManager;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.RunStore;
import com.flatide.teebox.ScriptInfo;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.webhook.WebhookDelivery;
import com.flatide.teebox.webhook.WebhookDispatcher;
import com.flatide.teebox.webhook.WebhookStore;
import com.flatide.teebox.webhook.WebhookTarget;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RetentionConfigurationTest {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final String[] KEYS = {
        "propertee.teebox.runRetentionMs",
        "propertee.teebox.runArchiveRetentionMs",
        "propertee.teebox.maintenanceIntervalMs",
        "propertee.teebox.scriptRetentionMs",
        "propertee.task.retentionMs",
        "propertee.task.archiveRetentionMs"
    };

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Map<String, String> savedProperties = new LinkedHashMap<>();

    @Before
    public void isolateSystemProperties() {
        for (String key : KEYS) saveAndClear(key);
        saveAndClear("propertee.teebox.config");
        saveAndClear("propertee.teebox.dataDir");
    }

    @After
    public void restoreSystemProperties() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    @Test
    public void fileDurationsAreLoadedWithoutChangingGlobalPropertiesOrOtherConfigs() throws Exception {
        TeeBoxConfig config = configWithDurations("14d", "60d", "1h", "21d", "14d", "60d");
        Assert.assertArrayEquals(new long[]{14 * DAY, 60 * DAY, 3600000L, 21 * DAY, 14 * DAY, 60 * DAY},
                durations(config));
        for (String key : KEYS) Assert.assertNull(key, System.getProperty(key));

        // Another server in the same JVM keeps its own defaults; file values are not exported as -D.
        Assert.assertArrayEquals(new long[]{DAY, 7 * DAY, 60000L, 7 * DAY, DAY, 7 * DAY},
                durations(configFromFile("")));
    }

    @Test
    public void systemPropertiesOverrideFileDurationsIncludingInvalidFileValues() throws Exception {
        String[] overrides = {"48h", "30d", "500ms", "21d", "72h", "45d"};
        for (int i = 0; i < KEYS.length; i++) System.setProperty(KEYS[i], overrides[i]);
        TeeBoxConfig config = configWithDurations("invalid", "invalid", "invalid", "invalid", "invalid", "invalid");
        long[] expected = {2 * DAY, 30 * DAY, 500L, 21 * DAY, 3 * DAY, 45 * DAY};
        Assert.assertArrayEquals(expected, durations(config));
        System.setProperty("propertee.teebox.dataDir", config.dataDir.getAbsolutePath());
        Assert.assertArrayEquals(expected, durations(TeeBoxConfig.fromSystemProperties()));

        for (String key : KEYS) System.setProperty(key, "  ");
        Assert.assertArrayEquals(new long[]{14 * DAY, 60 * DAY, 3600000L, 21 * DAY, 14 * DAY, 60 * DAY},
                durations(configWithDurations("14d", "60d", "1h", "21d", "14d", "60d")));
    }

    @Test
    public void invalidOrOverflowingDurationsFailWithTheSettingName() throws Exception {
        for (String key : KEYS) {
            for (String value : new String[]{"14days", "9223372036854775807d"}) {
                IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
                        () -> configFromFile(key + "=" + value + "\n"));
                Assert.assertTrue(error.getMessage(), error.getMessage().contains(key));
            }
        }
        for (String value : new String[]{"0", "-1ms"}) {
            IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
                    () -> configFromFile(KEYS[2] + "=" + value + "\n"));
            Assert.assertTrue(error.getMessage(), error.getMessage().contains(KEYS[2]));
        }
    }

    @Test
    public void rawMillisecondsZeroAndNegativeRetentionRemainSupported() throws Exception {
        Assert.assertArrayEquals(new long[]{0, -1, 1234, -1, 0, -1},
                durations(configWithDurations("0", "-1", "1234", "-1", "0", "-1")));
    }

    @Test
    public void fileDurationsControlRunAndTaskArchivalAndPurgeAtStartup() throws Exception {
        TeeBoxConfig config = configWithDurations("14d", "60d", "1h", "14d", "14d", "60d");
        seedRun(config.dataDir, "recent", 3, false);
        seedRun(config.dataDir, "archive-due", 15, false);
        seedRun(config.dataDir, "keep-archived", 8, true);
        seedRun(config.dataDir, "purge-due", 61, true);
        RunInfo longRun = seedRun(config.dataDir, "long-running", 3, false);
        longRun.createdAt -= 60 * DAY;
        new RunStore(config.dataDir).save(longRun);
        seedTask(config.dataDir, "recent", 3, false);
        seedTask(config.dataDir, "archive-due", 15, false);
        seedTask(config.dataDir, "keep-archived", 8, true);
        seedTask(config.dataDir, "purge-due", 61, true);

        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            Assert.assertFalse(manager.getRun("recent").archived);
            Assert.assertEquals("preserve-input", manager.getRun("recent").properties.get("input"));
            Assert.assertFalse("age is measured from completion", manager.getRun("long-running").archived);
            Assert.assertTrue(manager.getRun("archive-due").archived);
            Assert.assertTrue(manager.getRun("archive-due").properties.isEmpty());
            Assert.assertTrue(manager.getRun("keep-archived").archived);
            Assert.assertNull(manager.getRun("purge-due"));
            Assert.assertFalse(new File(config.dataDir, "runs/purge-due.json").exists());

            Assert.assertFalse(manager.getTask("recent").archived);
            Assert.assertTrue(manager.getTask("archive-due").archived);
            Assert.assertTrue(new File(manager.getTaskDir("archive-due"), "archive.json").isFile());
            Assert.assertFalse(new File(manager.getTaskDir("archive-due"), "meta.json").exists());
            Assert.assertTrue(manager.getTask("keep-archived").archived);
            Assert.assertNull(manager.getTask("purge-due"));
            Assert.assertFalse(manager.getTaskDir("purge-due").exists());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void fileIntervalDrivesScheduledPurgeAndScriptRetention() throws Exception {
        TeeBoxConfig config = configWithDurations("0", "0", "100ms", "14d", "0", "0");
        seedRun(config.dataDir, "old", 3, false);
        seedTask(config.dataDir, "old", 3, false);
        File recentScript = seedDeletedScript(config.dataDir, "recent", 3);
        File expiredScript = seedDeletedScript(config.dataDir, "expired", 15);
        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            awaitMaintenance(manager);
            Assert.assertNull(manager.getRun("old"));
            Assert.assertNull(manager.getTask("old"));
            Assert.assertTrue("14d script retention keeps a 3d-old deletion", recentScript.isFile());
            Assert.assertFalse("scheduled sweep purges the 15d-old deletion", expiredScript.exists());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void negativeFileRetentionDisablesArchiveAndPurge() throws Exception {
        TeeBoxConfig config = configWithDurations("-1", "-1", "100ms", "-1", "-1", "-1");
        seedRun(config.dataDir, "old", 90, false);
        seedRun(config.dataDir, "archived", 90, true);
        seedTask(config.dataDir, "old", 90, false);
        seedTask(config.dataDir, "archived", 90, true);
        File deletedScript = seedDeletedScript(config.dataDir, "old", 90);
        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            awaitMaintenance(manager);
            Assert.assertFalse(manager.getRun("old").archived);
            Assert.assertTrue(manager.getRun("archived").archived);
            Assert.assertFalse(manager.getTask("old").archived);
            Assert.assertTrue(manager.getTask("archived").archived);
            Assert.assertTrue(deletedScript.isFile());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void disablingArchivalStillPurgesAlreadyArchivedRecords() throws Exception {
        TeeBoxConfig config = configWithDurations("-1", "30d", "1h", "7d", "-1", "30d");
        seedRun(config.dataDir, "unarchived", 90, false);
        seedRun(config.dataDir, "archived", 90, true);
        seedTask(config.dataDir, "unarchived", 90, false);
        seedTask(config.dataDir, "archived", 90, true);
        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            Assert.assertFalse("purge cannot skip the archive stage", manager.getRun("unarchived").archived);
            Assert.assertFalse(manager.getTask("unarchived").archived);
            Assert.assertNull("existing archives still obey the purge age", manager.getRun("archived"));
            Assert.assertNull(manager.getTask("archived"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void disabledRunPurgeKeepsWebhookTombstonesAcrossRestart() throws Exception {
        assertWebhookTombstonesSurvive(configWithDurations("14d", "-1", "1h", "7d", "14d", "60d"), 90);
    }

    @Test
    public void disabledRunArchivalKeepsWebhookTombstonesAcrossRestart() throws Exception {
        assertWebhookTombstonesSurvive(configWithDurations("-1", "30d", "1h", "7d", "14d", "60d"), 90);
    }

    @Test
    public void hugeRunPurgeRetentionDoesNotOverflowWebhookRetention() throws Exception {
        assertWebhookTombstonesSurvive(configWithDurations("14d", Long.toString(Long.MAX_VALUE),
                "1h", "7d", "14d", "60d"), 90);
    }

    @Test
    public void webhookTombstonesOutliveALongerArchiveThreshold() throws Exception {
        // This run cannot be purged at 14d: it does not enter the archive until 60d.
        assertWebhookTombstonesSurvive(configWithDurations("60d", "14d", "1h", "7d", "14d", "60d"), 30);
    }

    @Test
    public void longMaintenanceIntervalKeepsWebhookTombstonesAcrossBothSweeps() throws Exception {
        // Archive/purge at 1d with a 2d sweep period can retain the run until nearly 5d. A 4d-old
        // unarchived run is archived at the first startup sweep, then purged on the second start;
        // both startup reconciles must still see the original delivery, not enqueue a new one.
        assertWebhookTombstonesSurvive(configWithDurations("1d", "1d", "2d", "7d", "14d", "60d"),
                4, false);
    }

    @Test
    public void longMaintenanceIntervalStillAllowsFiniteWebhookExpiry() throws Exception {
        assertFiniteWebhookRetention(configWithDurations("1d", "1d", "2d", "7d", "14d", "60d"), 4, 6);
    }

    @Test
    public void hugeMaintenanceIntervalsDoNotOverflowWebhookRetention() throws Exception {
        // Just below the multiplication overflow boundary, addition to the run horizon overflows;
        // above it, doubling the interval itself overflows. Both operations must saturate.
        for (long interval : new long[]{Long.MAX_VALUE / 2, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE}) {
            assertWebhookTombstonesSurvive(configWithDurations("14d", "60d", Long.toString(interval),
                    "7d", "14d", "60d"), 90, false);
        }
    }

    @Test
    public void finiteWebhookRetentionStillExpiresTerminalRecords() throws Exception {
        assertFiniteWebhookRetention(configWithDurations("14d", "60d", "1h", "7d", "14d", "60d"), 60, 62);
    }

    private static void assertFiniteWebhookRetention(TeeBoxConfig config, int keptAgeDays, int expiredAgeDays)
            throws Exception {
        config.webhookEnabled = true;
        WebhookStore store = new WebhookStore(config.dataDir);
        long now = System.currentTimeMillis();
        for (WebhookDelivery.State state : new WebhookDelivery.State[]{
                WebhookDelivery.State.DELIVERED, WebhookDelivery.State.DEAD}) {
            seedTombstone(store, "recent-" + state, state, now - keptAgeDays * DAY);
            seedTombstone(store, "expired-" + state, state, now - expiredAgeDays * DAY);
        }
        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            scanWebhookTombstones(manager.getWebhookDispatcher());
            for (WebhookDelivery.State state : new WebhookDelivery.State[]{
                    WebhookDelivery.State.DELIVERED, WebhookDelivery.State.DEAD}) {
                Assert.assertNotNull("computed safety margin is retained", store.load("recent-" + state));
                Assert.assertNull("finite retention still expires old records", store.load("expired-" + state));
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void programmaticConfigStillHonorsSystemOverridesAtRuntime() throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.dataDir = temp.newFolder();
        config.runRetentionMs = config.taskRetentionMs = 0;
        config.maintenanceIntervalMs = 3600000L;
        config.scriptRetentionMs = 14 * DAY;
        seedRun(config.dataDir, "recent", 3, false);
        seedTask(config.dataDir, "recent", 3, false);
        File deletedScript = seedDeletedScript(config.dataDir, "recent", 3);
        System.setProperty(KEYS[0], "14d");
        System.setProperty(KEYS[2], "100ms");
        System.setProperty(KEYS[3], "0");
        System.setProperty(KEYS[4], "14d");
        RunManager manager = new RunManager(config.dataDir, 1, config);
        try {
            awaitMaintenance(manager);
            Assert.assertFalse(manager.getRun("recent").archived);
            Assert.assertFalse(manager.getTask("recent").archived);
            Assert.assertFalse(deletedScript.exists());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void legacyConstructorsStillHonorSystemRetention() throws Exception {
        System.setProperty(KEYS[0], "14d");
        System.setProperty(KEYS[4], "14d");
        File dataDir = temp.newFolder();
        seedRun(dataDir, "recent", 3, false);
        seedTask(dataDir, "recent", 3, false);
        RunManager manager = new RunManager(dataDir, 1);
        try {
            Assert.assertFalse(manager.getRun("recent").archived);
            Assert.assertFalse(manager.getTask("recent").archived);
        } finally {
            manager.shutdown();
        }
        ManagedTaskEngine engine = new ManagedTaskEngine(dataDir.getAbsolutePath(), "retention-test");
        try {
            engine.init();
            engine.archiveExpiredTasks();
            Assert.assertFalse(engine.getTask("recent").archived);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void invalidRuntimeSettingsFailBeforeAnyStartupArchival() throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.dataDir = temp.newFolder();
        config.runRetentionMs = config.taskRetentionMs = 0;
        config.maintenanceIntervalMs = 0;
        seedRun(config.dataDir, "recent", 3, false);
        seedTask(config.dataDir, "recent", 3, false);
        IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
                () -> new RunManager(config.dataDir, 1, config));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains(KEYS[2]));

        config.maintenanceIntervalMs = 60000;
        System.setProperty(KEYS[5], "invalid");
        error = Assert.assertThrows(IllegalArgumentException.class,
                () -> new RunManager(config.dataDir, 1, config));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains(KEYS[5]));
        RunInfo persisted = new RunStore(config.dataDir).load("recent");
        Assert.assertFalse(persisted.archived);
        Assert.assertEquals("preserve-input", persisted.properties.get("input"));
        Assert.assertTrue(new File(config.dataDir, "tasks/task-recent/meta.json").isFile());
        Assert.assertFalse(new File(config.dataDir, "tasks/task-recent/archive.json").exists());
    }

    private void saveAndClear(String key) {
        savedProperties.put(key, System.getProperty(key));
        System.clearProperty(key);
    }

    private TeeBoxConfig configWithDurations(String... values) throws Exception {
        Assert.assertEquals(KEYS.length, values.length);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) content.append(KEYS[i]).append('=').append(values[i]).append('\n');
        return configFromFile(content.toString());
    }

    private TeeBoxConfig configFromFile(String settings) throws Exception {
        File root = temp.newFolder();
        File file = new File(root, "teebox.properties");
        String path = new File(root, "data").getAbsolutePath().replace("\\", "\\\\");
        Files.writeString(file.toPath(), "propertee.teebox.dataDir=" + path + "\n" + settings);
        return TeeBoxConfig.fromArgs(new String[]{"--config", file.getAbsolutePath()});
    }

    private static long[] durations(TeeBoxConfig config) {
        return new long[]{config.runRetentionMs, config.runArchiveRetentionMs, config.maintenanceIntervalMs,
                config.scriptRetentionMs, config.taskRetentionMs, config.taskArchiveRetentionMs};
    }

    private static void assertWebhookTombstonesSurvive(TeeBoxConfig config, int ageDays) throws Exception {
        assertWebhookTombstonesSurvive(config, ageDays, true);
    }

    private static void assertWebhookTombstonesSurvive(TeeBoxConfig config, int ageDays,
                                                      boolean runSurvivesSecondSweep) throws Exception {
        config.webhookEnabled = true;
        // Keep the allowlist empty so even a regression that re-enqueues a callback cannot make
        // a network request. Comparing state/attempts/timestamps catches DEAD -> DEAD re-enqueues too.
        config.webhookUrlAllowlist = null;
        WebhookStore store = new WebhookStore(config.dataDir);
        List<WebhookDelivery> expected = new ArrayList<>();
        for (WebhookDelivery.State state : new WebhookDelivery.State[]{
                WebhookDelivery.State.DELIVERED, WebhookDelivery.State.DEAD}) {
            RunInfo run = seedRun(config.dataDir, "hook-" + state, ageDays, false);
            run.callback = new WebhookTarget("https://callback.invalid/cb");
            new RunStore(config.dataDir).save(run);
            expected.add(seedTombstone(store, run.runId, state, run.endedAt));
        }
        // The second construction runs the real startup reconcile against the persisted tombstones.
        for (int start = 0; start < 2; start++) {
            RunManager manager = new RunManager(config.dataDir, 1, config);
            try {
                WebhookDispatcher dispatcher = manager.getWebhookDispatcher();
                scanWebhookTombstones(dispatcher);
                List<RunInfo> retained = new ArrayList<>();
                for (WebhookDelivery original : expected) {
                    RunInfo run = manager.getRun(original.runId);
                    if (start == 0 || runSurvivesSecondSweep) {
                        Assert.assertNotNull("run is still retained", run);
                        retained.add(run);
                    } else {
                        Assert.assertNull("second startup sweep purged the archived run", run);
                    }
                    Assert.assertNotNull("scan must preserve " + original.state + " tombstone",
                            store.load(original.runId));
                }
                dispatcher.reconcile(retained);
                for (WebhookDelivery original : expected) {
                    WebhookDelivery actual = store.load(original.runId);
                    Assert.assertEquals(original.state, actual.state);
                    Assert.assertEquals(original.attempts, actual.attempts);
                    Assert.assertEquals(original.createdAt, actual.createdAt);
                    Assert.assertEquals(original.deliveredAt, actual.deliveredAt);
                    Assert.assertEquals(original.payload, actual.payload);
                }
            } finally {
                manager.shutdown();
            }
        }
    }

    private static WebhookDelivery seedTombstone(WebhookStore store, String runId,
                                                WebhookDelivery.State state, long completedAt) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.deliveryId = runId;
        delivery.runId = runId;
        delivery.url = "https://callback.invalid/cb";
        delivery.state = state;
        delivery.attempts = state == WebhookDelivery.State.DELIVERED ? 3 : 12;
        delivery.createdAt = completedAt;
        delivery.deliveredAt = state == WebhookDelivery.State.DELIVERED ? completedAt : null;
        delivery.payload = new LinkedHashMap<>();
        delivery.payload.put("marker", "original-delivery");
        store.save(delivery);
        return delivery;
    }

    private static void scanWebhookTombstones(WebhookDispatcher dispatcher) throws Exception {
        // Seed old timestamps and invoke the production scan deterministically, without waiting
        // days or racing the background scan. These fixtures contain only terminal deliveries.
        dispatcher.shutdown();
        Method scan = WebhookDispatcher.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(dispatcher);
    }

    private static RunInfo seedRun(File dataDir, String id, int ageDays, boolean archived) {
        RunInfo run = new RunInfo();
        run.runId = id;
        run.scriptId = "example";
        run.status = RunStatus.COMPLETED;
        run.endedAt = System.currentTimeMillis() - ageDays * DAY;
        run.createdAt = run.endedAt - 1000;
        run.archived = archived;
        if (!archived) run.properties.put("input", "preserve-input");
        new RunStore(dataDir).save(run);
        return run;
    }

    private static void seedTask(File dataDir, String id, int ageDays, boolean archived) throws Exception {
        File dir = new File(dataDir, "tasks/task-" + id);
        Files.createDirectories(dir.toPath());
        long endedAt = System.currentTimeMillis() - ageDays * DAY;
        Files.writeString(new File(dir, archived ? "archive.json" : "meta.json").toPath(),
                "{\"taskId\":\"" + id + "\",\"runId\":\"" + id + "\",\"status\":\"completed\","
                + "\"alive\":false,\"archived\":" + archived + ",\"startTime\":" + (endedAt - 1000)
                + ",\"endTime\":" + endedAt + ",\"exitCode\":0}");
    }

    private static File seedDeletedScript(File dataDir, String id, int ageDays) throws Exception {
        File dir = new File(dataDir, "script-registry/" + id);
        Files.createDirectories(dir.toPath());
        ScriptInfo script = new ScriptInfo();
        script.scriptId = id;
        script.deletedAt = System.currentTimeMillis() - ageDays * DAY;
        File meta = new File(dir, "script.json");
        Files.writeString(meta.toPath(), new Gson().toJson(script));
        return meta;
    }

    private static void awaitMaintenance(RunManager manager) throws Exception {
        CountDownLatch swept = new CountDownLatch(2);
        manager.addMaintenanceTask(swept::countDown);
        Assert.assertTrue("maintenanceIntervalMs from config drives the scheduler", swept.await(5, TimeUnit.SECONDS));
    }
}
