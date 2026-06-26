package com.flatide.teebox.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Minimal outbound HTTP POST used only for webhook delivery. Kept separate from the
 * ProperTee runtime's {@code TeeBoxPlatformProvider.httpRequest} so webhook-specific policy
 * (redirects disabled, mandatory timeouts) lives in one place.
 *
 * <p>MVP: JSON POST with connect/read timeouts and {@code followRedirects=false}. Advanced
 * SSRF defenses (private-IP blocking, redirect re-validation) are deferred to the allowlist
 * gate enforced at submit time.
 */
public class WebhookHttpClient {

    /**
     * POST {@code jsonBody} to {@code url}; returns the HTTP status code. Throws on transport failure.
     * {@code deliveryId} (the runId) is sent as {@code X-TeeBox-Delivery} so the receiver can dedup
     * (delivery is at-least-once).
     */
    public int post(String url, String jsonBody, String deliveryId, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-TeeBox-Event", "run.terminal");
            if (deliveryId != null) {
                conn.setRequestProperty("X-TeeBox-Delivery", deliveryId);
            }

            byte[] body = jsonBody != null ? jsonBody.getBytes("UTF-8") : new byte[0];
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            drain(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return status;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) {
                // discard — we only care about the status code
            }
        } catch (IOException ignore) {
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
            }
        }
    }
}
