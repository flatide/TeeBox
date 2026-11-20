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
    /** Runs whose last save failed and has been reported; each is reported once, not at every flush. */
    private final Set<String> unsavedRunIds = ConcurrentHashMap.newKeySet();
    /** Archived runs whose input could not be read and has been reported; once each, not at every look. */
    private final Set<String> unreadableInputRunIds = ConcurrentHashMap.newKeySet();

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

    /** Registers and saves a new run; an id that is already registered is an error, never a replacement. */
    public void register(RunInfo run) {
        if (!registerIfAbsent(run)) {
            throw new IllegalStateException("Run id is already registered: " + run.runId);
        }
    }

    /**
     * Registers and saves a new run unless its id is taken; false then, with the registered run
     * untouched. The caller draws another id — an id clash must not let one run replace another
     * in the registry and in its file.
     */
    public boolean registerIfAbsent(RunInfo run) {
        if (runs.putIfAbsent(run.runId, run) != null) {
            return false;
        }
        try {
            writeRun(run);
        } catch (RuntimeException e) {
            runs.remove(run.runId, run);   // a run that cannot be saved is not left behind, QUEUED forever
            throw e;
        }
        return true;
    }

    /** Register a script-editor debug attempt in memory only. It participates in the normal run
     *  lifecycle while the session is executing, but is never written or exposed by list/count.
     *  False when the id is taken, as in {@link #registerIfAbsent}. */
    public boolean registerTransientDebug(RunInfo run) {
        if (run == null || run.runId == null || !run.debug || run.debugOf != null) {
            throw new IllegalArgumentException("Transient debug run requires debug=true and no source Run");
        }
        run.transientDebug = true;
        return runs.putIfAbsent(run.runId, run) == null;
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

    /**
     * As {@link #getRun}, with the input properties also when the run is archived. Archiving takes
     * the input out of memory and leaves it in the run's file, so this reads that file: meant for
     * a detail view of one run, not for status polling or listings.
     *
     * @throws RunStore.RunFileUnreadableException when that file does not give the input — it is
     *         gone, cannot be read, or does not hold this run's record: the input is then
     *         unknown, not empty; {@link #getRun} still answers with the rest of the run
     */
    public RunInfo getRunWithInput(String runId) {
        RunInfo run = getRun(runId);
        if (run != null && run.archived && run.properties.isEmpty()) {
            try {
                run.properties = runStore.loadProperties(runId);
            } catch (RunStore.RunFileUnreadableException e) {
                // Reported here, once for as long as it lasts: a run page left open asks again at
                // every refresh, and so may a client that polls the detail.
                if (unreadableInputRunIds.add(runId)) {
                    if (runs.containsKey(runId)) {
                        TeeBoxLog.warn("RunRegistry", "The input of run " + runId
                                + " cannot be shown: " + e.getMessage());
                    } else {
                        unreadableInputRunIds.remove(runId);   // purged meanwhile: its file is gone with it
                    }
                }
                throw e;
            }
            if (unreadableInputRunIds.remove(runId)) {
                TeeBoxLog.info("RunRegistry", "The input of run " + runId + " can be read again");
            }
        }
        return run;
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

    // ---- re-using a debug Run for another execution ----
    //
    // A terminal debug Run is executed again under the id it has. That is a full reset of the
    // attempt: no result, output, thread, archive, callback or execution timestamp of the earlier
    // attempt may bleed into the new one (createdAt stays — it is the same Run identity, and the
    // run list sorts by it). It is done in two steps, with the caller's own work between them
    // (RunManager.prepareDebugRun clears the ended attempt's tasks there):
    //
    //   saveDebugReset      the record of the next attempt goes to the run's file; memory is as it was
    //   completeDebugReset  the run in memory becomes that record, and its next attempt begins
    //   abandonDebugReset   instead of completing: the file goes back to what memory still holds
    //
    // Whatever refuses the new attempt — the save, or the caller's work — the run is then what
    // its last attempt left. (The record used to be reset in memory before it was saved. A save
    // that failed left a QUEUED run without a result that nothing would ever execute, so every
    // later reset was refused as "still QUEUED", and the next flush wrote that over the result the
    // file still held.)

    /**
     * First step of re-using a debug Run: writes the record of its next attempt to the run's
     * file. Nothing in memory changes, and the caller follows with {@link #completeDebugReset} or
     * {@link #abandonDebugReset}.
     *
     * @throws IllegalStateException when the run is not terminal
     * @throws RuntimeException      when the record could not be saved; nothing has changed
     */
    public void saveDebugReset(RunInfo existing, RunInfo prepared) {
        requireSameRun(existing, prepared);
        synchronized (existing) {
            requireTerminal(existing);
            if (existing.transientDebug) {
                return;   // never in a file
            }
            RunInfo reset = existing.copy();
            applyDebugReset(reset, prepared);
            runStore.save(reset);
        }
    }

    /**
     * Second step: the run in memory becomes the record {@link #saveDebugReset} wrote, and its
     * next {@link RunInfo#attempt} begins. Returns the registry-owned run.
     *
     * <p>Nothing is written here, so nothing can fail on the disk. The file already holds this
     * record — unless something wrote it in between (a flush or an archive of the ended record)
     * or deleted it (a purge), which is why the run is left marked for the next flush: the file
     * comes to say what memory holds, as after every change. The first thing the new attempt does
     * (its start, or its being called off) saves the run anyway.
     *
     * @throws IllegalStateException when the run is no longer terminal
     */
    public RunInfo completeDebugReset(RunInfo existing, RunInfo prepared) {
        requireSameRun(existing, prepared);
        synchronized (existing) {
            requireTerminal(existing);
            applyDebugReset(existing, prepared);
            existing.attempt++;
            // A maintenance purge may have removed this terminal candidate after findDebugRun()
            // returned it: the run is registered again.
            runs.put(existing.runId, existing);
            markDirty(existing.runId);
            return existing;
        }
    }

    /**
     * Instead of the second step: the attempt {@link #saveDebugReset} wrote down is not going to
     * begin, and the run's file has to say again what the run still is in memory. A save that
     * fails here is retried by the flush like any other. (An archived run's input comes back from
     * the record being replaced, which holds the same input: a debug attempt runs on the input of
     * its source Run, every time.) Does not throw.
     */
    public void abandonDebugReset(RunInfo existing) {
        if (existing == null || existing.runId == null) {
            return;
        }
        synchronized (existing) {
            if (existing.transientDebug) {
                return;
            }
            if (runs.get(existing.runId) != existing) {
                // Purged while the reset was on its way: the reset record is all the disk has of
                // this run, and nothing in memory is left to put in its place.
                try {
                    runStore.delete(existing.runId);
                } catch (RuntimeException e) {
                    TeeBoxLog.warn("RunRegistry", "Run " + existing.runId + " was purged while a debug"
                            + " re-run of it was refused, and the record of that re-run could not be"
                            + " deleted: " + e.getMessage());
                }
                return;
            }
            persistOrRetryLater(existing);
        }
    }

    private static void requireSameRun(RunInfo existing, RunInfo prepared) {
        if (existing == null || prepared == null || existing.runId == null
                || !existing.runId.equals(prepared.runId)) {
            throw new IllegalArgumentException("Debug run reset requires the same runId");
        }
    }

    /** The caller holds the run's monitor. */
    private void requireTerminal(RunInfo existing) {
        if (!isTerminal(existing.status)) {
            throw new IllegalStateException("Debug run " + existing.runId + " is still "
                + existing.status + " and cannot be re-used yet");
        }
    }

    /** Makes {@code run} the record of a debug attempt that has not started: nothing of an earlier attempt stays. */
    private static void applyDebugReset(RunInfo run, RunInfo prepared) {
        run.scriptPath = prepared.scriptPath;
        run.scriptId = prepared.scriptId;
        run.version = prepared.version;
        run.scriptAbsolutePath = prepared.scriptAbsolutePath;
        run.imports = new ArrayList<ResolvedModuleInfo>();
        run.status = RunStatus.QUEUED;
        run.archived = false;
        run.immediate = false;
        run.submittedBy = prepared.submittedBy;
        run.origin = "debug";
        run.submittedFrom = null;
        run.debug = true;
        run.debugOf = prepared.debugOf;
        run.startedAt = null;
        run.endedAt = null;
        run.maxIterations = prepared.maxIterations;
        run.iterationLimitBehavior = prepared.iterationLimitBehavior;
        run.timeoutMs = 0;
        run.stdoutTotalLines = 0;
        run.stderrTotalLines = 0;
        run.hasExplicitReturn = false;
        run.resultData = null;
        run.resultSummary = null;
        run.errorMessage = null;
        run.properties = prepared.properties;
        run.threads = new ArrayList<RunThreadInfo>();
        run.stdoutLines = new ArrayList<String>();
        run.stderrLines = new ArrayList<String>();
        run.published = null;
        run.callback = null;
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
            persistOrRetryLater(run);
        }
    }

    public void markQueued(RunInfo run) {
        synchronized (run) {
            run.status = RunStatus.QUEUED;
            persistOrRetryLater(run);
        }
    }

    /**
     * The worker's claim on a run: RUNNING, unless the run is already terminal — then nothing
     * changes and the answer is false. A cancel can reach a queued run in the instant a worker
     * picks it up ({@link #markCancelledIfQueued}); the run's monitor lets exactly one of the two
     * win, and a worker that lost must not bring the cancelled run back to RUNNING.
     */
    public boolean markStarted(RunInfo run) {
        synchronized (run) {
            if (isTerminal(run.status)) {
                return false;
            }
            run.status = RunStatus.RUNNING;
            run.startedAt = Long.valueOf(System.currentTimeMillis());
            if (run.endedAt != null) {
                run.endedAt = null;
            }
            persistRun(run);
            return true;
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
        markFailed(run, message, null);
    }

    /**
     * A failed run. {@code handlerResult} is the value the script's {@code on error} handler
     * returned (spec v0.37.0), kept as the run's result data beside {@code status: FAILED} and
     * {@code errorMessage}; null when no handler ran.
     */
    public void markFailed(RunInfo run, String message, Object handlerResult) {
        synchronized (run) {
            run.status = RunStatus.FAILED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.errorMessage = message != null ? message : "Unknown error";
            if (handlerResult != null) {
                run.resultData = TypeChecker.deepCopy(handlerResult);
                run.resultSummary = StreamResultSupport.isStreamDescriptor(handlerResult)
                    ? StreamResultSupport.summaryFor(handlerResult)
                    : safeSummary(handlerResult);
            } else {
                // A failed run without a handler has no result. Clearing it also lets this record
                // persist when a markCompleted could not be saved: its result stays in memory, and
                // saving it again would fail the same way, leaving the run file at RUNNING.
                run.resultData = null;
                run.resultSummary = null;
            }
            persistRun(run);
        }
    }

    public void markCancelled(RunInfo run, String message) {
        synchronized (run) {
            run.status = RunStatus.CANCELLED;
            run.endedAt = Long.valueOf(System.currentTimeMillis());
            run.errorMessage = message != null ? message : "Cancelled";
            // As in markFailed: a cancel latched while a markCompleted could not be saved must not
            // carry that result into its own save.
            run.resultData = null;
            run.resultSummary = null;
            persistOrRetryLater(run);
        }
    }

    /**
     * The canceller's claim on a queued run: CANCELLED if it is still QUEUED, in one step under
     * the run's monitor; false when a worker has started it (or it has left the queue any other
     * way) — the caller then cancels it as whatever it has become. See {@link #markStarted}.
     */
    public boolean markCancelledIfQueued(RunInfo run, String message) {
        synchronized (run) {
            if (run.status != RunStatus.QUEUED) {
                return false;
            }
            markCancelled(run, message);
            return true;
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
        int next = 0;
        try {
            for (; next < runIds.size(); next++) {
                String runId = runIds.get(next);
                RunInfo run = runs.get(runId);
                if (run == null) {
                    unsavedRunIds.remove(runId);
                    continue;
                }
                synchronized (run) {
                    if (run.transientDebug) {
                        continue;
                    }
                    if (runs.get(runId) != run) {
                        // Purged while this pass waited for the run: there is no run to save any
                        // more. (A save here once put the deleted file back, for a run no sweep
                        // looked at again. It no longer could — an archived run is not saved
                        // without the input its file holds — but it would still be tried, fail,
                        // and be reported as a run waiting to be saved.)
                        unsavedRunIds.remove(runId);
                        continue;
                    }
                    try {
                        runStore.save(storedForm(run));
                        saveSucceeded(runId);
                    } catch (RuntimeException e) {
                        // The file keeps what it has and the run waits for the next flush. Nothing
                        // is thrown: one run that cannot be saved (or whose file cannot be read for
                        // the input it holds) must not cost the others their save, nor fail the run
                        // whose worker happened to call this.
                        saveFailed(runId, e);
                    }
                }
            }
        } finally {
            if (next < runIds.size()) {
                // An Error cut the pass short: what it did not reach still waits to be saved.
                synchronized (dirtyLock) {
                    dirtyRunIds.addAll(runIds.subList(next, runIds.size()));
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
                        try {
                            archiveLocked(run);
                        } catch (RuntimeException e) {
                            // Nothing has changed: the run is as it was, the next sweep takes it
                            // again, and this sweep goes on to the other runs.
                            if (unsavedRunIds.add(run.runId)) {
                                TeeBoxLog.warn("RunRegistry", "Run " + run.runId
                                        + " could not be archived; the next sweep tries again: " + e.getMessage());
                            }
                        }
                    }
                    continue;
                }
                if (runArchiveRetentionMs >= 0 && ageMs >= runArchiveRetentionMs) {
                    purgeIds.add(run.runId);
                }
            }
        }
        List<String> purged = new ArrayList<String>();
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
                        || ageMs < runArchiveRetentionMs || runs.get(runId) != run) {
                    continue;
                }
                // The file first: a run whose file cannot be deleted stays registered, so the
                // next sweep tries again and the sweep goes on. (Taken out of memory first, it
                // left its file behind until a restart, and the failure ended the sweep.)
                try {
                    runStore.delete(runId);
                } catch (RuntimeException e) {
                    if (unsavedRunIds.add(runId)) {
                        TeeBoxLog.warn("RunRegistry", "Run " + runId
                                + " could not be purged; the next sweep tries again: " + e.getMessage());
                    }
                    continue;
                }
                // Out of memory while this thread still holds the run's monitor: a flush that picked
                // the run up before this and waits for the monitor finds it gone (flushDirty).
                runs.remove(runId, run);
                unsavedRunIds.remove(runId);
                unreadableInputRunIds.remove(runId);
                purged.add(runId);
            }
        }
        return purged;
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
                // storedForm: should the run be archived (no step here writes that beside a
                // status that is not terminal, but a file can say it), its input is in the file
                // only and this save must carry it over.
                RunInfo stored;
                try {
                    stored = storedForm(run);
                } catch (RuntimeException e) {
                    // What the file holds cannot be made into a record to save (the input of an
                    // archived run that its file does not give, say). One file must not keep the
                    // server from starting: it is left as it is, and is no run of this server.
                    TeeBoxLog.warn("RunRegistry", "Skipping run " + run.runId + ": it was not finished"
                            + " when the server stopped, and its record cannot be saved as ended: "
                            + e.getMessage());
                    continue;
                }
                runStore.save(stored);
            }
            // An archived run comes without its input: that stays in its file (see archiveLocked),
            // and RunStore.loadAll does not hand it over.
            runs.put(run.runId, run);
        }
    }

    /**
     * Archives a run; the caller holds its monitor. The archived record goes to the run's file
     * first, and only when that is written does the record in memory change — so a save that fails
     * leaves the run exactly as it was, not archived, for the next sweep to take again. (The flag
     * used to be set first: a failed save then left the run archived in memory and not in its
     * file, and no later sweep looked at it.)
     *
     * <p>Archiving trims the bulky diagnostics but keeps resultData intact: the result is the
     * run's product, and callers must be able to fetch it any time before purge (it used to be
     * dropped here, leaving only the 300-char resultSummary). The heap cost of archived results
     * staying resident for the archive window is accepted — scripts with large payloads should
     * return STREAM_FILE (tiny descriptor; bytes stream from disk). published stays (small
     * captured key-values). The input properties can be as large as a result and nothing reads
     * them but a detail view, so they move out of memory — not away: the file is written with
     * them, and from then on it is where they live (getRunWithInput reads them back; storedForm
     * carries them through a later save).
     */
    private void archiveLocked(RunInfo run) {
        RunInfo archived = run.copy();
        archived.archived = true;
        archived.threads = new ArrayList<RunThreadInfo>();
        archived.stdoutLines = trimTail(run.stdoutLines, archivedStdoutLines);
        archived.stderrLines = trimTail(run.stderrLines, archivedStderrLines);
        boolean wasDirty;
        synchronized (dirtyLock) {
            wasDirty = dirtyRunIds.remove(run.runId);
        }
        try {
            runStore.save(archived);   // with the input
        } catch (RuntimeException e) {
            if (wasDirty) {
                markDirty(run.runId);   // what was waiting to be saved still waits
            }
            throw e;
        }
        saveSucceeded(run.runId);
        run.archived = true;
        run.threads = archived.threads;
        run.stdoutLines = archived.stdoutLines;
        run.stderrLines = archived.stderrLines;
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

    /**
     * Saves a run whose record in memory has just changed. A save that fails is reported to the
     * caller, and the run is left waiting for the next flush: what memory holds is what the file
     * must come to say, whether or not the caller does anything about the failure.
     */
    private void persistRun(RunInfo run) {
        try {
            writeRun(run);
        } catch (RuntimeException e) {
            saveFailed(run.runId, e);
            throw e;
        }
    }

    /**
     * As {@link #persistRun}, for a step that queue and slot bookkeeping hangs on — a cancel, a
     * hand-over to the next run of a script. The record in memory is what decides such a step, so
     * a save that fails must not stop the caller half-way through it (a place under a script's
     * limit that is never given back keeps every later run of that script waiting). The failure
     * is logged, and the flush saves the run when the disk takes it again.
     */
    private void persistOrRetryLater(RunInfo run) {
        try {
            persistRun(run);
        } catch (RuntimeException e) {
            // reported by persistRun; the run waits for the next flush
        }
    }

    private void writeRun(RunInfo run) {
        synchronized (dirtyLock) {
            dirtyRunIds.remove(run.runId);
        }
        if (run.transientDebug) {
            return;
        }
        runStore.save(storedForm(run));
        saveSucceeded(run.runId);
    }

    private void saveFailed(String runId, RuntimeException e) {
        markDirty(runId);
        if (unsavedRunIds.add(runId)) {
            TeeBoxLog.warn("RunRegistry", "Run " + runId
                    + " could not be saved; it will be saved by a later flush: " + e.getMessage());
        }
    }

    private void saveSucceeded(String runId) {
        if (unsavedRunIds.remove(runId)) {
            TeeBoxLog.info("RunRegistry", "Run " + runId + " is saved again");
        }
    }

    /**
     * The record to write for a run. An archived run's input is in its file only, so a save from
     * the in-memory record would erase it: the stored input is carried over instead. When the file
     * does not give it — gone, unreadable, not this run's record — there is nothing to carry over,
     * and no record without the input may be written in its place either: the read's failure goes
     * to the caller as the save's.
     */
    private RunInfo storedForm(RunInfo run) {
        RunInfo copy = run.copy();
        if (copy.archived && copy.properties.isEmpty()) {
            copy.properties = runStore.loadProperties(run.runId);
        }
        return copy;
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
            || status == RunStatus.CANCELLED || status == RunStatus.SERVER_RESTARTED;
    }
}
