package com.flatide.teebox;

import com.flatide.propertee2.runtime.TypeChecker;
import com.flatide.teebox.webhook.WebhookTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RunInfo {
    public String runId;
    public String scriptPath;
    public String scriptId;
    public String version;
    public String scriptAbsolutePath;
    public RunStatus status;
    public boolean archived;
    /** True when the run's script had immediate=true at submit time (an "instant run" — bypasses the
     *  global queue). Recorded per run so the Runs UI/API can filter; legacy runs read back as false. */
    public boolean immediate;
    /** Who submitted the run (null = anonymous): the caller-supplied X-TeeBox-User header on API
     *  submits, or the admin-UI session username. Display/audit only — not an auth identity. */
    public String submittedBy;
    /** How the run was submitted: "ui" (TeeBox admin UI) or "api" (external client API). Drives
     *  the regular-user kill/cancel permission (own UI runs only) and the runs-list origin tag.
     *  Null on runs persisted before the field existed (treated like external). */
    public String origin;
    /** Caller IP at submit time (first X-Forwarded-For hop when present, else the socket peer;
     *  null = unknown). Shown on the run detail page; audit only. */
    public String submittedFrom;
    /** True for a debugger re-run (created from a terminal run via the debug API): runs on the
     *  dedicated debug executor, exempt from the run timeout and per-script concurrency, no
     *  webhook callback. Legacy persisted runs read back as false. */
    public boolean debug;
    /** The source run this debug run re-executes (null on normal runs). */
    public String debugOf;
    public long createdAt;
    public Long startedAt;
    public Long endedAt;
    public int maxIterations;
    public String iterationLimitBehavior;
    /** Per-run execution timeout (ms, from RUNNING); 0 = server default. */
    public long timeoutMs;
    /** Total script stdout/stderr lines ever appended (the retained lists are ring-capped);
     *  legacy persisted runs read back as 0. */
    public int stdoutTotalLines;
    public int stderrTotalLines;
    public boolean hasExplicitReturn;
    public Object resultData;
    public String resultSummary;
    public String errorMessage;
    public Map<String, Object> properties = new LinkedHashMap<String, Object>();
    public List<RunThreadInfo> threads = new ArrayList<RunThreadInfo>();
    public List<String> stdoutLines = new ArrayList<String>();
    public List<String> stderrLines = new ArrayList<String>();
    public Map<String, Object> published;
    /** Run-terminal webhook callback target carried from submit (null = none). Persisted. */
    public WebhookTarget callback;

    public RunInfo copy() {
        RunInfo copy = new RunInfo();
        copy.runId = runId;
        copy.scriptPath = scriptPath;
        copy.scriptId = scriptId;
        copy.version = version;
        copy.scriptAbsolutePath = scriptAbsolutePath;
        copy.status = status;
        copy.archived = archived;
        copy.immediate = immediate;
        copy.submittedBy = submittedBy;
        copy.origin = origin;
        copy.submittedFrom = submittedFrom;
        copy.debug = debug;
        copy.debugOf = debugOf;
        copy.createdAt = createdAt;
        copy.startedAt = startedAt;
        copy.endedAt = endedAt;
        copy.maxIterations = maxIterations;
        copy.iterationLimitBehavior = iterationLimitBehavior;
        copy.timeoutMs = timeoutMs;
        copy.stdoutTotalLines = stdoutTotalLines;
        copy.stderrTotalLines = stderrTotalLines;
        copy.hasExplicitReturn = hasExplicitReturn;
        copy.resultData = copyValue(resultData);
        copy.resultSummary = resultSummary;
        copy.errorMessage = errorMessage;
        copy.properties = copyMap(properties);
        copy.threads = new ArrayList<RunThreadInfo>();
        for (RunThreadInfo thread : threads) {
            copy.threads.add(thread.copy());
        }
        copy.stdoutLines = new ArrayList<String>(stdoutLines);
        copy.stderrLines = new ArrayList<String>(stderrLines);
        copy.published = published != null ? copyMap(published) : null;
        copy.callback = callback != null ? new WebhookTarget(callback.url) : null;
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) return new LinkedHashMap<String, Object>();
        return (Map<String, Object>) TypeChecker.deepCopy(source);
    }

    private static Object copyValue(Object value) {
        return TypeChecker.deepCopy(value);
    }
}
