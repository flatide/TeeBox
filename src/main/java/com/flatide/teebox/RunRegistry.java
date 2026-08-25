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

    /** Register a script-editor debug attempt in memory only. It participates in the normal run
     *  lifecycle while the session is executing, but is never written or exposed by list/count. */
    public void registerTransientDebug(RunInfo run) {
        if (run == null || run.runId == null || !run.debug || run.debugOf != null) {
            throw new IllegalArgumentException("Transient debug run requires debug=true and no source Run");
        }
        run.transientDebug = true;
        runs.put(run.runId, run);
    }

    /** Remove a finished script-editor debug attempt after its session captured the result. */
    public void discardTransientDebug(String runId) {
        RunInfo run = runId != null ? runs.get(runId) : null;
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (!run.transientDebug) {
                throw new IllegalArgumentException("Run is not transient debug: " + runId);
            }
            runs.remove(runId, run);
        }
        synchronized (dirtyLock) {
            dirtyRunIds.remove(runId);
        }
        // Defensive cleanup for data left by a partially upgraded implementation. Current
        // transient runs never reach RunStore.
        runStore.delete(runId);
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

    /**
     * Newest retained debug Run for {@code sourceRunId}, or {@code null}. Older TeeBox versions
     * could create more than one; choosing the newest gives each source a stable canonical Run
     * from the first re-run after upgrading. The returned object is the registry-owned instance.
     */
    public RunInfo findDebugRun(String sourceRunId) {
        RunInfo found = null;
        long foundCreatedAt = Long.MIN_VALUE;
        String foundRunId = null;
        for (RunInfo candidate : runs.values()) {
            synchronized (candidate) {
                if (!candidate.debug || sourceRunId == null
                        || !sourceRunId.equals(candidate.debugOf)) {
                    continue;
                }
                if (found == null || candidate.createdAt > foundCreatedAt
                        || (candidate.createdAt == foundCreatedAt
                            && candidate.runId.compareTo(foundRunId) > 0)) {
                    found = candidate;
                    foundCreatedAt = candidate.createdAt;
                    foundRunId = candidate.runId;
                }
            }
        }
        return found;
    }

    /**
     * Re-initialize a terminal debug Run for another execution while preserving its runId. This
     * is a full attempt reset: no result, output, thread, archive, callback, or execution timestamp
     * from the previous attempt is allowed to bleed into the new one. {@code createdAt} remains
     * stable because this is the same Run identity (and run-list sorting relies on its immutability).
     */
    public RunInfo resetDebugRun(RunInfo existing, RunInfo prepared) {
        if (existing == null || prepared == null || existing.runId == null
                || !existing.runId.equals(prepared.runId)) {
            throw new IllegalArgumentException("Debug run reset requires the same runId");
        }
        synchronized (existing) {
            if (!isTerminal(existing.status)) {
                throw new IllegalStateException("Debug run " + existing.runId + " is still "
                    + existing.status + " and cannot be re-used yet");
            }
            existing.scriptPath = prepared.scriptPath;
            existing.scriptId = prepared.scriptId;
            existing.version = prepared.version;
            existing.scriptAbsolutePath = prepared.scriptAbsolutePath;
            existing.imports = new ArrayList<ResolvedModuleInfo>();
            existing.status = RunStatus.QUEUED;
            existing.archived = false;
            existing.immediate = false;
            existing.submittedBy = prepared.submittedBy;
            existing.origin = "debug";
            existing.submittedFrom = null;
            existing.debug = true;
            existing.debugOf = prepared.debugOf;
            existing.startedAt = null;
            existing.endedAt = null;
            existing.maxIterations = prepared.maxIterations;
            existing.iterationLimitBehavior = prepared.iterationLimitBehavior;
            existing.timeoutMs = 0;
            existing.stdoutTotalLines = 0;
            existing.stderrTotalLines = 0;
            existing.hasExplicitReturn = false;
            existing.resultData = null;
            existing.resultSummary = null;
            existing.errorMessage = null;
            existing.properties = prepared.properties;
            existing.threads = new ArrayList<RunThreadInfo>();
            existing.stdoutLines = new ArrayList<String>();
            existing.stderrLines = new ArrayList<String>();
            existing.published = null;
            existing.callback = null;
            // A maintenance purge may have removed this terminal candidate after findDebugRun()
            // returned it. Re-publish the canonical object before saving the reset record.
            runs.put(existing.runId, existing);
            persistRun(existing);
            return existing;
        }
    }

    public void markDirty(RunInfo run) {
        if (run != null && run.runId != null) {
            markDirty(run.runId);
        }
    }

    public int countRuns(String status) {
        return countRuns(status, null, null);
    }

    /** Filtered count; {@code immediate}/{@code search} as in
     *  {@link #listRuns(String, String, Boolean, String, String, int, int)}. */
    public int countRuns(String status, Boolean immediate, String search) {
        return countRuns(status, immediate, search, null);
    }

    /** Filtered count with optional comma-separated, case-insensitive run origins. */
    public int countRuns(String status, Boolean immediate, String search, String origin) {
        Set<String> origins = parseOriginFilter(origin);
        int count = 0;
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (matches(run, status, null, immediate, search, origins)) {
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
        return listRuns(status, scriptId, null, null, null, offset, limit);
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
        return listRuns(status, scriptId, immediate, search, null, offset, limit);
    }

    /** As above, with optional comma-separated, case-insensitive run origins. */
    public List<RunInfo> listRuns(String status, String scriptId, Boolean immediate, String search,
                                  String origin, int offset, int limit) {
        Set<String> origins = parseOriginFilter(origin);
        List<RunInfo> matched = new ArrayList<RunInfo>();
        for (RunInfo run : runs.values()) {
            synchronized (run) {
                if (matches(run, status, scriptId, immediate, search, origins)) {
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
                                   Boolean immediate, String search, Set<String> origins) {
        if (run.transientDebug) {
            return false;
        }
        if (status != null && (run.status == null || !status.equalsIgnoreCase(run.status.name()))) {
            return false;
        }
        if (scriptId != null && !scriptId.equals(run.scriptId)) {
            return false;
        }
        if (immediate != null && run.immediate != immediate.booleanValue()) {
            return false;
        }
        if (origins != null
                && !origins.contains(run.effectiveOrigin().toLowerCase(Locale.ROOT))) {
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

    /** Null means no origin filter; an empty set deliberately matches nothing. */
    private static Set<String> parseOriginFilter(String origin) {
        if (origin == null) {
            return null;
        }
        Set<String> origins = new HashSet<String>();
        for (String item : origin.split(",")) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (normalized.length() > 0) {
                origins.add(normalized);
            }
        }
        return origins;
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
                if (run.transientDebug || !isTerminal(run.status)) {
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
                if (!run.transientDebug && isTerminal(run.status)) {
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
                    if (!run.transientDebug) {
                        runStore.save(run.copy());
                    }
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
                if (run.transientDebug || !isTerminal(run.status)) {
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
            RunInfo run = runs.get(runId);
            if (run == null) {
                continue;
            }
            synchronized (run) {
                // Re-check after collecting candidates: a terminal debug Run may have been reset
                // for another execution between the scan and this removal.
                long terminalAt = run.endedAt != null ? run.endedAt.longValue() : run.createdAt;
                long ageMs = System.currentTimeMillis() - terminalAt;
                if (!isTerminal(run.status) || !run.archived || runArchiveRetentionMs < 0
                        || ageMs < runArchiveRetentionMs || !runs.remove(runId, run)) {
                    continue;
                }
                runStore.delete(runId);
            }
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
        RunInfo run = runs.get(runId);
        if (run != null && run.transientDebug) {
            return;
        }
        synchronized (dirtyLock) {
            dirtyRunIds.add(runId);
        }
    }

    private void persistRun(RunInfo run) {
        synchronized (dirtyLock) {
            dirtyRunIds.remove(run.runId);
        }
        if (run.transientDebug) {
            return;
        }
        runStore.save(run.copy());
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
            || status == RunStatus.CANCELLED || status == RunStatus.SERVER_RESTARTED;
    }
}
