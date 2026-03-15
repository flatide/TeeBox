package com.propertee.teebox;

import com.propertee.runtime.TypeChecker;

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
    public long createdAt;
    public Long startedAt;
    public Long endedAt;
    public int maxIterations;
    public String iterationLimitBehavior;
    public boolean hasExplicitReturn;
    public Object resultData;
    public String resultSummary;
    public String errorMessage;
    public Map<String, Object> properties = new LinkedHashMap<String, Object>();
    public List<RunThreadInfo> threads = new ArrayList<RunThreadInfo>();
    public List<String> stdoutLines = new ArrayList<String>();
    public List<String> stderrLines = new ArrayList<String>();

    public RunInfo copy() {
        RunInfo copy = new RunInfo();
        copy.runId = runId;
        copy.scriptPath = scriptPath;
        copy.scriptId = scriptId;
        copy.version = version;
        copy.scriptAbsolutePath = scriptAbsolutePath;
        copy.status = status;
        copy.archived = archived;
        copy.createdAt = createdAt;
        copy.startedAt = startedAt;
        copy.endedAt = endedAt;
        copy.maxIterations = maxIterations;
        copy.iterationLimitBehavior = iterationLimitBehavior;
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
