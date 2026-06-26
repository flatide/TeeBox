package com.flatide.tests;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.webhook.WebhookDelivery;
import com.flatide.teebox.webhook.WebhookDispatcher;
import com.flatide.teebox.webhook.WebhookHttpClient;
import com.flatide.teebox.webhook.WebhookStore;
import com.flatide.teebox.webhook.WebhookTarget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebhookDispatcherTest {

    private File dataDir;
    private WebhookStore store;
    private WebhookDispatcher dispatcher;

    /** Stub HTTP client: returns a configurable status, or throws, and records calls. */
    private static class StubClient extends WebhookHttpClient {
        volatile int status = 200;
        volatile boolean throwIo = false;
        final AtomicInteger calls = new AtomicInteger(0);
        volatile String lastUrl;
        volatile String lastBody;
        volatile String lastDeliveryId;

        @Override
        public int post(String url, String jsonBody, String deliveryId, int timeoutMs) throws IOException {
            calls.incrementAndGet();
            lastUrl = url;
            lastBody = jsonBody;
            lastDeliveryId = deliveryId;
            if (throwIo) {
                throw new IOException("connection refused");
            }
            return status;
        }
    }

    private final StubClient client = new StubClient();

    @Before
    public void setUp() throws Exception {
        dataDir = File.createTempFile("teebox-webhook", "");
        dataDir.delete();
        dataDir.mkdirs();
        store = new WebhookStore(dataDir);
        dispatcher = new WebhookDispatcher(store, client, "host.internal,host.internal:9000", 1000, 2,
            7L * 24L * 60L * 60L * 1000L);
    }

    @After
    public void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private RunInfo terminalRun(String runId, String url) {
        RunInfo run = new RunInfo();
        run.runId = runId;
        run.scriptId = "demo";
        run.version = "1";
        run.status = RunStatus.COMPLETED;
        run.createdAt = System.currentTimeMillis();
        run.endedAt = Long.valueOf(System.currentTimeMillis());
        run.resultSummary = "ok";
        run.callback = url != null ? new WebhookTarget(url) : null;
        return run;
    }

    // ---- validateTarget ----

    @Test
    public void allowsHostOnAllowlist() {
        dispatcher.validateTarget(new WebhookTarget("https://host.internal/cb"));
        dispatcher.validateTarget(new WebhookTarget("http://host.internal:9000/cb"));
    }

    @Test
    public void rejectsHostNotOnAllowlist() {
        try {
            dispatcher.validateTarget(new WebhookTarget("https://evil.example.com/cb"));
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsNonHttpScheme() {
        try {
            dispatcher.validateTarget(new WebhookTarget("ftp://host.internal/cb"));
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsWhenAllowlistEmpty() {
        WebhookDispatcher noAllow = new WebhookDispatcher(store, client, null, 1000, 1,
            7L * 24L * 60L * 60L * 1000L);
        try {
            noAllow.validateTarget(new WebhookTarget("https://host.internal/cb"));
            fail("expected rejection (no allowlist configured)");
        } catch (IllegalArgumentException expected) {
        }
    }

    // ---- enqueue / reconcile (synchronous) ----

    @Test
    public void enqueueIsIdempotentAndSnapshotsPayload() {
        RunInfo run = terminalRun("r-1", "https://host.internal/cb");
        dispatcher.onRunTerminal(run);
        assertTrue(store.exists("r-1"));
        WebhookDelivery d = store.load("r-1");
        assertNotNull(d);
        assertEquals(WebhookDelivery.State.PENDING, d.state);
        assertEquals("demo", d.payload.get("scriptId"));
        assertEquals("COMPLETED", d.payload.get("status"));

        // second enqueue must not overwrite (idempotent)
        d.state = WebhookDelivery.State.DELIVERED;
        store.save(d);
        dispatcher.onRunTerminal(run);
        assertEquals(WebhookDelivery.State.DELIVERED, store.load("r-1").state);
    }

    @Test
    public void enqueueMarksDeadWhenUrlNoLongerAllowed() {
        // Simulates a persisted/tampered run whose callback host is not on the current allowlist.
        dispatcher.onRunTerminal(terminalRun("r-8", "https://evil.example.com/cb"));
        WebhookDelivery d = store.load("r-8");
        assertNotNull(d);
        assertEquals(WebhookDelivery.State.DEAD, d.state);
        assertTrue(d.lastError != null && d.lastError.contains("blocked"));
    }

    @Test
    public void ignoresRunWithoutCallback() {
        dispatcher.onRunTerminal(terminalRun("r-2", null));
        assertNull(store.load("r-2"));
    }

    @Test
    public void reconcileEnqueuesTerminalRunsWithCallbackOnly() {
        List<RunInfo> runs = new ArrayList<RunInfo>();
        runs.add(terminalRun("r-3", "https://host.internal/cb"));
        RunInfo noCb = terminalRun("r-4", null);
        runs.add(noCb);
        RunInfo running = terminalRun("r-5", "https://host.internal/cb");
        running.status = RunStatus.RUNNING;
        runs.add(running);

        dispatcher.reconcile(runs);
        assertTrue(store.exists("r-3"));
        assertNull(store.load("r-4"));  // no callback
        assertNull(store.load("r-5"));  // not terminal
    }

    @Test
    public void reconcileDoesNotResetAnExistingTombstone() {
        // Re-enqueue is prevented by the persisted record (which outlives the run), not a time window:
        // a DELIVERED tombstone must survive reconcile untouched.
        RunInfo run = terminalRun("r-tomb", "https://host.internal/cb");
        dispatcher.onRunTerminal(run);
        WebhookDelivery d = store.load("r-tomb");
        d.state = WebhookDelivery.State.DELIVERED;
        store.save(d);

        java.util.List<RunInfo> runs = new ArrayList<RunInfo>();
        runs.add(run);
        dispatcher.reconcile(runs);
        assertEquals(WebhookDelivery.State.DELIVERED, store.load("r-tomb").state);
    }

    // ---- delivery loop (async) ----

    @Test
    public void deliversOn2xx() throws Exception {
        client.status = 202;
        dispatcher.start(50);
        dispatcher.onRunTerminal(terminalRun("r-6", "https://host.internal/cb"));
        WebhookDelivery d = awaitState("r-6", WebhookDelivery.State.DELIVERED, 5000);
        assertEquals(WebhookDelivery.State.DELIVERED, d.state);
        assertEquals(Integer.valueOf(202), d.lastStatus);
        assertTrue(client.calls.get() >= 1);
        assertEquals("https://host.internal/cb", client.lastUrl);
        assertEquals("r-6", client.lastDeliveryId);
        assertTrue(client.lastBody.contains("\"runId\""));
    }

    @Test
    public void retriesOnFailureWithoutDelivering() throws Exception {
        client.status = 500;
        dispatcher.start(50);
        dispatcher.onRunTerminal(terminalRun("r-7", "https://host.internal/cb"));
        // first attempt should fail: state stays PENDING, attempts incremented, lastStatus recorded
        long deadline = System.currentTimeMillis() + 5000;
        WebhookDelivery d = null;
        while (System.currentTimeMillis() < deadline) {
            d = store.load("r-7");
            if (d != null && d.attempts >= 1) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(d);
        assertTrue("attempts should increment", d.attempts >= 1);
        assertEquals(WebhookDelivery.State.PENDING, d.state);
        assertEquals(Integer.valueOf(500), d.lastStatus);
    }

    private WebhookDelivery awaitState(String runId, WebhookDelivery.State state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WebhookDelivery d = store.load(runId);
            if (d != null && d.state == state) {
                return d;
            }
            Thread.sleep(50);
        }
        WebhookDelivery d = store.load(runId);
        throw new AssertionError("delivery " + runId + " never reached " + state
            + " (current=" + (d != null ? d.state : "<missing>") + ")");
    }
}
