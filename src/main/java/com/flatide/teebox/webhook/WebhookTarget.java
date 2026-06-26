package com.flatide.teebox.webhook;

/**
 * Per-run webhook callback target supplied at submit time
 * ({@code POST /api/client/scripts/{id}/runs} body: {@code "callback": {"url": ...}}).
 *
 * <p>MVP carries only the destination URL. Custom auth headers, HMAC signing and
 * payloadMode selection are intentionally deferred (see docs/TEEBOX-WEBHOOK-DESIGN.ko.md).
 */
public class WebhookTarget {
    public String url;

    public WebhookTarget() {
    }

    public WebhookTarget(String url) {
        this.url = url;
    }
}
