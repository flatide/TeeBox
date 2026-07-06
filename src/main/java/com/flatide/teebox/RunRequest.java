package com.flatide.teebox;

import com.flatide.teebox.webhook.WebhookTarget;

import java.util.LinkedHashMap;
import java.util.Map;

public class RunRequest {
    public String scriptId;
    public String version;
    public Map<String, Object> props = new LinkedHashMap<String, Object>();
    public int maxIterations = 1000;
    public boolean warnLoops = false;
    /** Optional run-terminal webhook callback target (null = none). */
    public WebhookTarget callback;
    /** Optional submitter identity (null = anonymous): the X-TeeBox-User header on API submits, or the
     *  admin-UI session username. Recorded on the run as {@code RunInfo.submittedBy} for display. */
    public String userId;
    /** Caller IP at submit time (X-Forwarded-For-aware; null = unknown). Recorded on the run as
     *  {@code RunInfo.submittedFrom} — shown on the run detail page for audit. */
    public String clientAddress;
}
