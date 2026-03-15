package com.propertee.teebox;

import com.propertee.runtime.TypeChecker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RunRegistry {
    private final int maxLogLines;
    private final int archivedStdoutLines;
    private final int archivedStderrLines;
    private final long runRetentionMs;
    private final long runArchiveRetentionMs;
    private final RunStore runStore;
    private final ConcurrentHashMap<String, RunInfo> runs = new ConcurrentHashMap<String, RunInfo>();
    private final Set<String> dirtyRunIds = new HashSet<String>();
    private final Set<String> indexDirtyRunIds = new HashSet<String>();
    private final Object dirtyLock = new Object();

    public RunRegistry(File dataDir,
                       int maxLogLines,
                       int archivedStdoutLines,
                       int archivedStderrLines,
                       long runRetentionMs,
                       long runArchiveRetentionMs) {
        this.maxLogLines = maxLogLines;
        this.archivedStdoutLines = archivedStdoutLines;
        this.archivedStderrLines = archivedStderrLines;
        this.runRetentionMs = runRetentionMs;
        this.runArchiveRetentionMs = runArchiveRetentionMs;
        this.runStore = new RunStore(dataDir);
        loadPersistedRuns();
    }

    public void register(RunInfo run) {
        runs.put(run.runId, run);
        saveRunWithIndex(run);
    }

    public RunInfo getRun(String runId) {
        RunInfo run = runs.get(runId);
        return run != null ? copyRun(run) : null;
    }

    public RunInfo requireRun(String runId) {
        return runs.get(runId);
    }

    public List<RunInfo> listRuns(String status, int offset, int limit) {
        return listRuns(status, null, offset, limit);
    }

    public List<RunInfo> listRuns(String status, String scriptId, int offset, int limit) {
        flushDirty();
        List<RunInfo> loaded = runStore.query(status, scriptId, offset, limit);
        List<RunInfo> copy = new ArrayList<RunInfo>();
        for (RunInfo run : loaded) {
            RunInfo current = runs.get(run.runId);
            copy.add(copyRun(current != null ? current : run));
        }
        return copy;
    }

    public List<RunThreadInfo> listThreads(String runId) {
        RunInfo run = runs.get(runId);
        if (run == null) {
            return new ArrayList<RunThreadInfo>();
        }
        synchronized (run) {
            return copyThreads(run.threads);
        }
    }

    public void markStarted(RunInfo run) {
        synchronized (run) {
            run.status = RunStatus.RUNNING;
            run.startedAt = Long.valueOf(System.currentTimeMillis());
            if (run.endedAt != null) {
                run.endedAt = null;
            }
            saveRunWithIndex(run);
        }
    }

    public void markCompleted(RunInfo run, boolean hasExplicitReturn, Object resultData) {
        synchronized (run) {
            run.status = RunStatus.COMPLETED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.hasExplicitReturn = hasExplicitReturn;
            run.resultData = TypeChecker.deepCopy(resultData);
            run.resultSummary = safeSummary(resultData);
            saveRunWithIndex(run);
        }
    }

    public void markFailed(RunInfo run, String message) {
        synchronized (run) {
            run.status = RunStatus.FAILED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.errorMessage = message != null ? message : "Unknown error";
            saveRunWithIndex(run);
        }
    }

    public void appendLog(RunInfo run, boolean stdout, String line) {
        synchronized (run) {
            List<String> target = stdout ? run.stdoutLines : run.stderrLines;
            target.add(line);
            while (target.size() > maxLogLines) {
                target.remove(0);
            }
            markDirty(run.runId);
        }
    }

    public void upsertThread(RunInfo run, RunThreadInfo threadInfo) {
        synchronized (run) {
            int idx = findThreadIndex(run.threads, threadInfo.threadId);
            if (idx >= 0) {
                run.threads.set(idx, threadInfo);
            } else {
                run.threads.add(threadInfo);
                java.util.Collections.sort(run.threads, new java.util.Comparator<RunThreadInfo>() {
                    @Override
                    public int compare(RunThreadInfo a, RunThreadInfo b) {
                        return a.threadId < b.threadId ? -1 : (a.threadId == b.threadId ? 0 : 1);
                    }
                });
            }
            markDirty(run.runId);
        }
    }

    public void flushDirty() {
        List<String> runIds;
        List<String> indexIds;
        synchronized (dirtyLock) {
            if (dirtyRunIds.isEmpty() && indexDirtyRunIds.isEmpty()) {
                return;
            }
            runIds = new ArrayList<String>(dirtyRunIds);
            dirtyRunIds.clear();
            indexIds = new ArrayList<String>(indexDirtyRunIds);
            indexDirtyRunIds.clear();
        }
        for (String runId : runIds) {
            RunInfo run = runs.get(runId);
            if (run != null) {
                synchronized (run) {
                    runStore.saveRunFile(run.copy());
                }
            }
        }
        for (String runId : indexIds) {
            RunInfo run = runs.get(runId);
            if (run != null) {
                synchronized (run) {
                    runStore.save(run.copy());
                }
            }
        }
    }

    public List<String> maintainRuns() {
        flushDirty();
        long now = System.currentTimeMillis();
        List<String> purgeIds = new ArrayList<String>();
        for (RunInfo run : new ArrayList<RunInfo>(runs.values())) {
            synchronized (run) {
                if (!isTerminal(run.status)) {
                    continue;
                }
                long terminalAt = run.endedAt != null ? run.endedAt.longValue() : run.createdAt;
                long ageMs = now - terminalAt;
                if (!run.archived) {
                    if (runRetentionMs >= 0 && ageMs >= runRetentionMs) {
                        archiveRunLocked(run);
                        saveRunWithIndex(run);
                    }
                    continue;
                }
                if (runArchiveRetentionMs >= 0 && ageMs >= runArchiveRetentionMs) {
                    purgeIds.add(run.runId);
                }
            }
        }
        for (String runId : purgeIds) {
            runs.remove(runId);
            runStore.delete(runId);
        }
        return purgeIds;
    }

    private void loadPersistedRuns() {
        List<RunInfo> existing = runStore.loadAll();
        long now = System.currentTimeMillis();
        for (RunInfo run : existing) {
            if (run.status != null && !isTerminal(run.status)) {
                run.status = RunStatus.SERVER_RESTARTED;
                if (run.endedAt == null) {
                    run.endedAt = Long.valueOf(now);
                }
                if (run.errorMessage == null || run.errorMessage.length() == 0) {
                    run.errorMessage = "Server restarted before run finished";
                }
                runStore.save(run);
            }
            runs.put(run.runId, run);
        }
    }

    private void archiveRunLocked(RunInfo run) {
        if (run.archived) {
            return;
        }
        run.archived = true;
        run.threads = new ArrayList<RunThreadInfo>();
        run.stdoutLines = trimTail(run.stdoutLines, archivedStdoutLines);
        run.stderrLines = trimTail(run.stderrLines, archivedStderrLines);
    }

    private List<String> trimTail(List<String> lines, int maxLines) {
        List<String> source = lines != null ? lines : new ArrayList<String>();
        if (source.size() <= maxLines) {
            return new ArrayList<String>(source);
        }
        return new ArrayList<String>(source.subList(source.size() - maxLines, source.size()));
    }

    private int findThreadIndex(List<RunThreadInfo> threads, int threadId) {
        for (int i = 0; i < threads.size(); i++) {
            if (threads.get(i).threadId == threadId) {
                return i;
            }
        }
        return -1;
    }

    private String safeSummary(Object value) {
        try {
            String formatted = TypeChecker.formatValue(value);
            if (formatted != null && formatted.length() > 300) {
                return formatted.substring(0, 300) + "...";
            }
            return formatted;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private List<RunThreadInfo> copyThreads(List<RunThreadInfo> threads) {
        List<RunThreadInfo> copy = new ArrayList<RunThreadInfo>();
        for (RunThreadInfo thread : threads) {
            copy.add(thread.copy());
        }
        return copy;
    }

    private RunInfo copyRun(RunInfo run) {
        synchronized (run) {
            return run.copy();
        }
    }

    private void markDirty(String runId) {
        synchronized (dirtyLock) {
            dirtyRunIds.add(runId);
        }
    }

    private void saveRunWithIndex(RunInfo run) {
        synchronized (dirtyLock) {
            dirtyRunIds.remove(run.runId);
            indexDirtyRunIds.remove(run.runId);
        }
        runStore.save(run.copy());
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.SERVER_RESTARTED;
    }
}
