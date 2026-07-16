package com.flatide.teebox;

import com.flatide.propertee2.runtime.TypeChecker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        persistRun(run);
    }

    public RunInfo getRun(String runId) {
        RunInfo run = runs.get(runId);
        return run != null ? copyRun(run) : null;
    }

    public RunInfo requireRun(String runId) {
        return runs.get(runId);
    }

    /** Returns the raw (non-copied) RunInfo for in-place mutation by watchers. */
    public RunInfo getRawRun(String runId) {
        return runs.get(runId);
    }

    public void markDirty(RunInfo run) {
        if (run != null && run.runId != null) {
            markDirty(run.runId);
        }
    }

    public int countRuns(String status) {
        return countRuns(status, null, null);
    }

    /** Filtered count; {@code immediate}/{@code search} as in {@link #listRuns(String, String, Boolean, String, int, int)}. */
    public int countRuns(String status, Boolean immediate, String search) {
        int count = 0;
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (matches(run, status, null, immediate, search)) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<RunInfo> listRuns(String status, int offset, int limit) {
        return listRuns(status, null, offset, limit);
    }

    public List<RunInfo> listRuns(String status, String scriptId, int offset, int limit) {
        return listRuns(status, scriptId, null, null, offset, limit);
    }

    /**
     * Filtered listing, newest first (createdAt desc, runId tiebreak), served entirely from the
     * in-memory map — it holds every non-purged run (startup load + register), so no disk is
     * touched. {@code status} matches case-insensitively; {@code immediate}: null = all, TRUE =
     * instant runs only, FALSE = exclude instant runs; {@code search}: case-insensitive substring
     * on the runId OR the scriptId; {@code limit <= 0} = unlimited. Only the returned page is
     * deep-copied.
     */
    public List<RunInfo> listRuns(String status, String scriptId, Boolean immediate, String search,
                                  int offset, int limit) {
        List<RunInfo> matched = new ArrayList<RunInfo>();
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (matches(run, status, scriptId, immediate, search)) {
                    matched.add(run);
                }
            }
        }
        sortNewestFirst(matched);
        int safeOffset = offset < 0 ? 0 : offset;
        List<RunInfo> page = new ArrayList<RunInfo>();
        for (int i = safeOffset; i < matched.size(); i++) {
            if (limit > 0 && page.size() >= limit) {
                break;
            }
            page.add(copyRun(matched.get(i)));
        }
        return page;
    }

    // Callers must hold the run's monitor: status transitions are written under synchronized(run)
    // (the mark* methods), so filtering needs the same monitor for visibility of the latest state.
    // The sort runs outside it — createdAt/runId are immutable after register and safely published
    // by the ConcurrentHashMap put.
    private static boolean matches(RunInfo run, String status, String scriptId,
                                   Boolean immediate, String search) {
        if (status != null && (run.status == null || !status.equalsIgnoreCase(run.status.name()))) {
            return false;
        }
        if (scriptId != null && !scriptId.equals(run.scriptId)) {
            return false;
        }
        if (immediate != null && run.immediate != immediate.booleanValue()) {
            return false;
        }
        if (search != null) {
            String needle = search.toLowerCase(Locale.ROOT);
            boolean inRunId = run.runId != null && run.runId.toLowerCase(Locale.ROOT).contains(needle);
            boolean inScriptId = run.scriptId != null && run.scriptId.toLowerCase(Locale.ROOT).contains(needle);
            if (!inRunId && !inScriptId) {
                return false;
            }
        }
        return true;
    }

    private static void sortNewestFirst(List<RunInfo> list) {
        java.util.Collections.sort(list, new java.util.Comparator<RunInfo>() {
            @Override
            public int compare(RunInfo a, RunInfo b) {
                if (a.createdAt == b.createdAt) {
                    return a.runId.compareTo(b.runId);
                }
                return a.createdAt < b.createdAt ? 1 : -1;
            }
        });
    }

    /**
     * Copies of terminal runs (from the in-memory cache, no disk reload) that ended at/after
     * {@code sinceMs}. Used by periodic webhook reconcile to cheaply find recently-terminal runs.
     */
    public List<RunInfo> listCachedRunsEndedSince(long sinceMs) {
        List<RunInfo> out = new ArrayList<RunInfo>();
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (!isTerminal(run.status)) {
                    continue;
                }
                long terminalAt = run.endedAt != null ? run.endedAt.longValue() : run.createdAt;
                if (terminalAt >= sinceMs) {
                    out.add(copyRun(run));
                }
            }
        }
        return out;
    }

    /**
     * Copies of all non-purged terminal runs (from the in-memory cache). Used by the one-time
     * startup webhook reconcile, which must consider every recoverable run regardless of age.
     */
    public List<RunInfo> listCachedTerminalRuns() {
        List<RunInfo> out = new ArrayList<RunInfo>();
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (isTerminal(run.status)) {
                    out.add(copyRun(run));
                }
            }
        }
        return out;
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

    public void markPending(RunInfo run) {
        synchronized (run) {
            run.status = RunStatus.PENDING;
            persistRun(run);
        }
    }

    public void markQueued(RunInfo run) {
        synchronized (run) {
            run.status = RunStatus.QUEUED;
            persistRun(run);
        }
    }

    public void markStarted(RunInfo run) {
        synchronized (run) {
            run.status = RunStatus.RUNNING;
            run.startedAt = Long.valueOf(System.currentTimeMillis());
            if (run.endedAt != null) {
                run.endedAt = null;
            }
            persistRun(run);
        }
    }

    public void markCompleted(RunInfo run, boolean hasExplicitReturn, Object resultData) {
        synchronized (run) {
            run.status = RunStatus.COMPLETED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.hasExplicitReturn = hasExplicitReturn;
            run.resultData = TypeChecker.deepCopy(resultData);
            // A stream descriptor carries an absolute server path; never let it reach resultSummary
            // (exposed by the run summary endpoints). Use a redacted form instead.
            run.resultSummary = StreamResultSupport.isStreamDescriptor(resultData)
                ? StreamResultSupport.summaryFor(resultData)
                : safeSummary(resultData);
            persistRun(run);
        }
    }

    public void markFailed(RunInfo run, String message) {
        synchronized (run) {
            run.status = RunStatus.FAILED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.errorMessage = message != null ? message : "Unknown error";
            persistRun(run);
        }
    }

    public void markCancelled(RunInfo run, String message) {
        synchronized (run) {
            run.status = RunStatus.CANCELLED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.errorMessage = message != null ? message : "Cancelled";
            persistRun(run);
        }
    }

    public void appendLog(RunInfo run, boolean stdout, String line) {
        synchronized (run) {
            List<String> target = stdout ? run.stdoutLines : run.stderrLines;
            // Count every appended line so API consumers can tell "exactly N" from "N of many"
            // after the ring below drops older lines.
            if (stdout) run.stdoutTotalLines++; else run.stderrTotalLines++;
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
        synchronized (dirtyLock) {
            if (dirtyRunIds.isEmpty()) {
                return;
            }
            runIds = new ArrayList<String>(dirtyRunIds);
            dirtyRunIds.clear();
        }
        for (String runId : runIds) {
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
                        persistRun(run);
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
        // Trim the bulky diagnostics but keep resultData intact: the result is the run's product,
        // and callers must be able to fetch it any time before purge (it used to be dropped here,
        // leaving only the 300-char resultSummary). The heap cost of archived results staying
        // resident for the archive window is accepted — scripts with large payloads should return
        // STREAM_FILE (tiny descriptor; bytes stream from disk). Input properties can be equally
        // large but are diagnostics, not the product; published stays (small captured key-values).
        run.properties = new LinkedHashMap<String, Object>();
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

    private void persistRun(RunInfo run) {
        synchronized (dirtyLock) {
            dirtyRunIds.remove(run.runId);
        }
        runStore.save(run.copy());
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
            || status == RunStatus.CANCELLED || status == RunStatus.SERVER_RESTARTED;
    }
}
