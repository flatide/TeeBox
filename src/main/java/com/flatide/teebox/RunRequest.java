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
}
