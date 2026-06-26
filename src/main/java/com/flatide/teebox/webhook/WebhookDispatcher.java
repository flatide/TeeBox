package com.flatide.teebox.webhook;

import com.flatide.teebox.RunInfo;
import com.flatide.teebox.RunStatus;
import com.flatide.teebox.TeeBoxLog;
import com.google.gson.Gson;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Server-side durable webhook delivery: on run terminal, enqueue a {@link WebhookDelivery} to the
 * {@link WebhookStore} outbox, then retry POSTing it (backoff) until a 2xx (DELIVERED) or the
 * attempt budget is exhausted (DEAD). Survives restart: PENDING records resume, and a reconcile
 * re-enqueues any terminal run that has a callback but no delivery record.
 *
 * <p>Scope = MVP (see docs/TEEBOX-WEBHOOK-DESIGN.ko.md). No HMAC, custom headers, payloadMode,
 * advanced SSRF, or admin surface — the allowlist + scheme gate enforced at submit time is the
 * security baseline.
 */
public class WebhookDispatcher {
    private static final String COMPONENT = "Webhook";

    private static final long BACKOFF_BASE_MS = 5000L;
    private static final long BACKOFF_CAP_MS = 10L * 60L * 1000L;     // 10 min
    private static final int MAX_ATTEMPTS = 12;
    private static final long DEFAULT_SCAN_INTERVAL_MS = 2000L;

    private final WebhookStore store;
    private final WebhookHttpClient http;
    private final int timeoutMs;
    private final Set<String> allowlist;          // lowercased "host" or "host:port" entries
    // Terminal (DELIVERED/DEAD) records are kept at least this long. Set > the run purge horizon so a
    // tombstone always outlives its run — this is what lets reconcile tell "never enqueued" (no record)
    // from "already delivered" (record present) without a time-window heuristic, even after restart.
    private final long tombstoneRetentionMs;
    private final Gson gson = new Gson();

    private final ScheduledExecutorService scanScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor workerPool;
    private final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public WebhookDispatcher(WebhookStore store, WebhookHttpClient http, String allowlistSetting,
                             int timeoutMs, int concurrency, long tombstoneRetentionMs) {
        this.store = store;
        this.http = http;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 10000;
        this.allowlist = parseAllowlist(allowlistSetting);
        this.tombstoneRetentionMs = tombstoneRetentionMs;
        this.workerPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, concurrency));
    }

    // ---- submit-time validation (throws IllegalArgumentException -> HTTP 400) ----

    /** Validate a callback target at submit time: scheme http/https + host on the allowlist. */
    public void validateTarget(WebhookTarget target) {
        if (target == null || target.url == null || target.url.trim().length() == 0) {
            throw new IllegalArgumentException("callback.url is required");
        }
        URI uri;
        try {
            uri = new URI(target.url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("callback.url is not a valid URL: " + target.url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("callback.url must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("callback.url has no host: " + target.url);
        }
        if (allowlist.isEmpty()) {
            throw new IllegalArgumentException(
                "webhook callbacks require propertee.teebox.webhookUrlAllowlist to be configured");
        }
        if (!isAllowed(host, uri.getPort())) {
            throw new IllegalArgumentException("callback.url host is not on the webhook allowlist: " + host);
        }
    }

    private boolean isAllowed(String host, int port) {
        String h = host.toLowerCase();
        if (allowlist.contains(h)) {
            return true;            // host-only entry matches any port
        }
        if (port >= 0 && allowlist.contains(h + ":" + port)) {
            return true;            // host:port entry
        }
        return false;
    }

    private static Set<String> parseAllowlist(String setting) {
        Set<String> set = new LinkedHashSet<String>();
        if (setting == null) return set;
        for (String part : setting.split(",")) {
            String e = part.trim().toLowerCase();
            if (e.length() > 0) {
                set.add(e);
            }
        }
        return set;
    }

    // ---- lifecycle ----

    public void start() {
        start(DEFAULT_SCAN_INTERVAL_MS);
    }

    public void start(long scanIntervalMs) {
        long interval = scanIntervalMs > 0 ? scanIntervalMs : DEFAULT_SCAN_INTERVAL_MS;
        scanScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    scan();
                } catch (Exception e) {
                    TeeBoxLog.warn(COMPONENT, "Webhook scan failed", e);
                }
            }
        }, 1000L, interval, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scanScheduler.shutdownNow();
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- enqueue (called from the run terminal hook) ----

    /** Enqueue a delivery for a terminal run that carries a callback. Idempotent; never throws. */
    public void onRunTerminal(RunInfo run) {
        try {
            if (run == null || run.callback == null || run.callback.url == null) {
                return;
            }
            if (!isTerminal(run.status)) {
                return;
            }
            if (store.exists(run.runId)) {
                return;     // already enqueued / delivered / dead
            }
            long now = System.currentTimeMillis();
            WebhookDelivery d = new WebhookDelivery();
            d.deliveryId = run.runId;
            d.runId = run.runId;
            d.scriptId = run.scriptId;
            d.version = run.version;
            d.url = run.callback.url.trim();
            d.attempts = 0;
            d.createdAt = now;
            d.payload = buildPayload(run);
            // Re-validate at enqueue: a persisted/tampered/legacy run may carry a URL that the
            // current allowlist no longer permits. Record it DEAD (auditable) rather than send it.
            try {
                validateTarget(new WebhookTarget(d.url));
                d.state = WebhookDelivery.State.PENDING;
                d.nextAttemptAt = now;
            } catch (IllegalArgumentException blocked) {
                d.state = WebhookDelivery.State.DEAD;
                d.lastError = "blocked: " + blocked.getMessage();
                TeeBoxLog.warn(COMPONENT, "AUDIT BLOCKED webhook enqueue for run " + run.runId
                    + ": " + blocked.getMessage());
            }
            store.save(d);
        } catch (Exception e) {
            // A webhook fault must never affect run execution.
            TeeBoxLog.warn(COMPONENT, "Failed to enqueue webhook for run "
                + (run != null ? run.runId : "?"), e);
        }
    }

    /**
     * Re-enqueue terminal runs that have a callback but no delivery record (covers the
     * run-persist .. enqueue gap and SERVER_RESTARTED runs). Called at startup and periodically.
     */
    /**
     * Enqueue any run in {@code terminalRuns} that has a callback but no delivery record yet.
     * Re-enqueue of an already-delivered run is prevented by the persisted tombstone
     * ({@code store.exists}), which outlives the run — so the caller is free to pass the full set of
     * non-purged terminal runs (startup) or a recent slice (periodic) without risking duplicates.
     */
    public void reconcile(List<RunInfo> terminalRuns) {
        if (terminalRuns == null) return;
        for (RunInfo run : terminalRuns) {
            if (run != null && run.callback != null && run.callback.url != null
                    && isTerminal(run.status) && !store.exists(run.runId)) {
                onRunTerminal(run);
            }
        }
    }

    // ---- delivery loop ----

    private void scan() {
        long now = System.currentTimeMillis();
        List<WebhookDelivery> all = store.loadAll();
        for (final WebhookDelivery d : all) {
            if (d.state == WebhookDelivery.State.PENDING) {
                if (d.nextAttemptAt <= now && inFlight.add(d.runId)) {
                    workerPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                deliver(d);
                            } finally {
                                inFlight.remove(d.runId);
                            }
                        }
                    });
                }
            } else if (d.state == WebhookDelivery.State.DELIVERED) {
                if (d.deliveredAt != null && now - d.deliveredAt.longValue() > tombstoneRetentionMs) {
                    store.delete(d.deliveryId);
                }
            } else if (d.state == WebhookDelivery.State.DEAD) {
                if (now - d.createdAt > tombstoneRetentionMs) {
                    store.delete(d.deliveryId);
                }
            }
        }
    }

    private void deliver(WebhookDelivery d) {
        // Re-validate just before sending: the allowlist may have changed while this record sat
        // PENDING across retries. A now-disallowed target dies instead of being delivered.
        try {
            validateTarget(new WebhookTarget(d.url));
        } catch (IllegalArgumentException blocked) {
            d.state = WebhookDelivery.State.DEAD;
            d.lastError = "blocked: " + blocked.getMessage();
            TeeBoxLog.warn(COMPONENT, "AUDIT BLOCKED webhook delivery for run " + d.runId
                + ": " + blocked.getMessage());
            persist(d);
            return;
        }
        try {
            int status = http.post(d.url, gson.toJson(d.payload), d.runId, timeoutMs);
            d.lastStatus = Integer.valueOf(status);
            if (status >= 200 && status < 300) {
                d.state = WebhookDelivery.State.DELIVERED;
                d.deliveredAt = Long.valueOf(System.currentTimeMillis());
                d.lastError = null;
                TeeBoxLog.info(COMPONENT, "Delivered webhook for run " + d.runId + " (HTTP " + status + ")");
            } else {
                fail(d, "HTTP " + status);
            }
        } catch (Exception e) {
            d.lastStatus = Integer.valueOf(0);
            fail(d, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        persist(d);
    }

    private void persist(WebhookDelivery d) {
        try {
            store.save(d);
        } catch (Exception e) {
            TeeBoxLog.warn(COMPONENT, "Failed to persist webhook delivery " + d.runId, e);
        }
    }

    private void fail(WebhookDelivery d, String error) {
        d.attempts++;
        d.lastError = error;
        if (d.attempts >= MAX_ATTEMPTS) {
            d.state = WebhookDelivery.State.DEAD;
            TeeBoxLog.warn(COMPONENT, "Webhook for run " + d.runId + " gave up after "
                + d.attempts + " attempts: " + error);
        } else {
            d.nextAttemptAt = System.currentTimeMillis() + backoff(d.attempts);
        }
    }

    private static long backoff(int attempts) {
        double base = BACKOFF_BASE_MS * Math.pow(2.0, attempts - 1);
        long capped = (long) Math.min(base, (double) BACKOFF_CAP_MS);
        double jitter = 0.8 + (Math.random() * 0.4);    // +/-20%
        return (long) (capped * jitter);
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
            || status == RunStatus.SERVER_RESTARTED;
    }

    private static Map<String, Object> buildPayload(RunInfo run) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("event", "run.terminal");
        p.put("runId", run.runId);
        p.put("scriptId", run.scriptId);
        p.put("version", run.version);
        p.put("status", run.status != null ? run.status.name() : null);
        p.put("endedAt", run.endedAt);
        p.put("errorMessage", run.errorMessage);
        p.put("resultSummary", run.resultSummary);
        p.put("published", run.published);
        return p;
    }

    // ---- introspection ----

    public List<WebhookDelivery> listDeliveries() {
        return new ArrayList<WebhookDelivery>(store.loadAll());
    }
}
