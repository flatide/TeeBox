package com.flatide.teebox.webhook;

import java.util.Map;

/**
 * Durable outbox record for one run-terminal webhook delivery. Persisted under
 * {@code ${dataDir}/webhooks/<runId>.json}. {@code deliveryId == runId} (one delivery per run),
 * which makes dedup and restart reconcile a simple file-existence check.
 */
public class WebhookDelivery {

    public enum State {
        PENDING,    // awaiting (re)delivery
        DELIVERED,  // received a 2xx
        DEAD        // gave up (max attempts / age)
    }

    public String deliveryId;   // == runId
    public String runId;
    public String scriptId;
    public String version;
    public String url;

    public State state = State.PENDING;
    public int attempts;
    public long nextAttemptAt;
    public Integer lastStatus;  // last HTTP status (null = never sent; 0 = transport failure)
    public String lastError;

    public long createdAt;
    public Long deliveredAt;

    /** SUMMARY payload snapshot taken at enqueue time (decoupled from run retention). */
    public Map<String, Object> payload;
}
