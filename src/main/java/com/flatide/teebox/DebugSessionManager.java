package com.flatide.teebox;

import com.flatide.propertee2.interp.DebugCallSite;
import com.flatide.propertee2.interp.DebugFrame;
import com.flatide.propertee2.interp.DebugHandler;
import com.flatide.propertee2.runtime.TypeChecker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Interactive debug re-runs of finished runs, over the engine's debug hooks (propertee2 0.26.0:
 * {@code ProperTeeInterpreter.setDebugHandler}/{@code debugBreakpoints()}). Deliberately separate
 * from {@link RunManager}'s run pool: a debug run pauses at breaks for as long as the operator
 * thinks — parked on the engine fiber, holding the run frozen — so it executes on this manager's
 * own small dedicated executor and never occupies a production run slot, and abandonment is
 * handled by an idle timeout (any API touch, polling included, keeps a session alive) instead of
 * the run execution timeout.
 *
 * <p>Threading: the engine calls {@link DebugHandler#onBreak} ON the paused fiber, and every
 * {@code DebugFrame} access is confined to that thread. HTTP threads therefore never touch the
 * frame — they enqueue {@link Command}s that the handler (blocked on the session's queue) executes
 * in place, and read immutable pause snapshots the handler published.
 *
 * <p>Kill semantics: a paused run sits inside the handler, not at a cooperative checkpoint, so an
 * engine abort alone cannot end it. {@link RunManager}'s cancel path registers a composite handle
 * for debug runs — engine abort (covers running-between-breaks) plus {@link Session#wakeForCancel}
 * (a QUIT command that makes the handler throw {@code DebugQuit}, covering paused). Both unwind to
 * a CANCELLED run, never FAILED.
 *
 * <p>A debug re-run is a real execution: SHELL/HTTP/file side effects happen again.
 */
public class DebugSessionManager {

    public static final int DEFAULT_MAX_SESSIONS = 2;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 30L * 60L * 1000L;
    /** How long an ENDED session stays queryable before the sweeper drops it. */
    private static final long ENDED_RETENTION_MS = 15L * 60L * 1000L;
    /** How long a command endpoint waits for the handler (an eval can legitimately run long —
     *  it may SLEEP or SHELL; on timeout the command stays queued and executes when reached). */
    private static final long COMMAND_WAIT_MS = 15000L;
    private static final int VALUE_DISPLAY_MAX = 300;
    private static final int EVAL_DISPLAY_MAX = 2000;

    private final RunManager runManager;
    private final int maxSessions;
    private final long idleTimeoutMs;
    private final ThreadPoolExecutor debugExecutor;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final Object openLock = new Object();
    private volatile boolean shutdownRequested = false;

    public DebugSessionManager(RunManager runManager, int maxSessions, long idleTimeoutMs) {
        this.runManager = runManager;
        this.maxSessions = maxSessions > 0 ? maxSessions : DEFAULT_MAX_SESSIONS;
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : DEFAULT_IDLE_TIMEOUT_MS;
        this.debugExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(this.maxSessions,
            new ThreadFactory() {
                private int n = 0;
                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "teebox-debug-run-" + (++n));
                    t.setDaemon(true);
                    return t;
                }
            });
        runManager.addMaintenanceTask(new Runnable() {
            @Override
            public void run() {
                sweep();
            }
        });
    }

    // ===================== public API =====================

    /**
     * Open a debug session re-running {@code sourceRunId} (must be terminal and not archived).
     * When the source run FAILED with a positioned error, a breakpoint is pre-set on the failing
     * line so the run pauses just before the statement that failed; {@code breakpoints} adds more.
     * The session starts immediately on the dedicated executor (capacity permitting).
     *
     * @throws IllegalArgumentException unknown source run
     * @throws IllegalStateException    capacity reached, non-terminal/archived source, shutdown
     */
    public Session open(String sourceRunId, String requestedBy, List<Integer> breakpoints) {
        synchronized (openLock) {
            if (shutdownRequested) {
                throw new IllegalStateException("Server is shutting down; debug sessions are rejected");
            }
            if (activeCount() >= maxSessions) {
                throw new IllegalStateException("Debug session limit reached (" + maxSessions
                    + ") — close or quit an existing session first");
            }
            RunInfo source = runManager.getRun(sourceRunId);
            RunManager.DebugTarget target = runManager.prepareDebugRun(sourceRunId, requestedBy);
            final Session session = new Session(createSessionId(), target.run.runId, sourceRunId);
            if (breakpoints != null) {
                for (Integer line : breakpoints) {
                    if (line != null && line.intValue() > 0) {
                        session.breakpoints.add(line);
                    }
                }
            }
            Integer failedLine = parseErrorLine(source != null ? source.errorMessage : null);
            if (failedLine != null) {
                session.breakpoints.add(failedLine);
            }
            sessions.put(session.sessionId, session);
            final RunManager.DebugTarget finalTarget = target;
            try {
                debugExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            runManager.executeDebugRun(finalTarget.run, finalTarget.scriptFile,
                                session.new Attach());
                        } finally {
                            finalizeSession(session);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                sessions.remove(session.sessionId);
                // The run was already registered — terminalize it, or it sits QUEUED forever.
                runManager.abandonDebugRun(finalTarget.run,
                    "Server shutting down — debug session rejected before start");
                throw new IllegalStateException("Server is shutting down; debug sessions are rejected");
            }
            TeeBoxLog.info("DebugSession", "Opened " + session.sessionId + " re-running "
                + sourceRunId + " as " + session.runId + " (breakpoints " + session.breakpoints + ")");
            return session;
        }
    }

    /** The session, or null. Any successful lookup via the status API counts as activity. */
    public Session find(String sessionId) {
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /** Status snapshot for the API/UI; touches the session (polling keeps it alive). Null if unknown. */
    public Map<String, Object> status(String sessionId) {
        Session session = find(sessionId);
        if (session == null) {
            return null;
        }
        session.touch();
        return statusMap(session);
    }

    /** All sessions, newest first (no touch — listing is not per-session activity). */
    public List<Map<String, Object>> list() {
        List<Session> all = new ArrayList<Session>(sessions.values());
        java.util.Collections.sort(all, new java.util.Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Session session : all) {
            result.add(statusMap(session));
        }
        return result;
    }

    /**
     * Execute a debugger command. {@code op}: {@code continue}, {@code stepOver}, {@code stepIn},
     * {@code stepOut}, {@code eval} (with {@code source}), {@code quit}. All but {@code quit}
     * require the session to be paused at a break. Commands queue FIFO to the paused handler; the
     * call waits up to {@value #COMMAND_WAIT_MS} ms for completion (a long-running eval reports
     * {@code timedOut} and still executes).
     *
     * @throws IllegalArgumentException unknown session or op, missing eval source
     * @throws IllegalStateException    session ended, or not paused for a pause-only op
     */
    public Map<String, Object> command(String sessionId, String op, String source) {
        Session session = find(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Debug session not found: " + sessionId);
        }
        session.touch();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.sessionId);
        result.put("op", op);
        if ("quit".equals(op)) {
            boolean applied = kill(sessionId, "Ended from the debugger");
            result.put("accepted", Boolean.valueOf(applied));
            return result;
        }
        if (Session.ENDED.equals(session.state)) {
            throw new IllegalStateException("Debug session has ended");
        }
        int kind;
        if ("continue".equals(op)) {
            kind = Command.CONTINUE;
        } else if ("stepOver".equals(op)) {
            kind = Command.STEP_OVER;
        } else if ("stepIn".equals(op)) {
            kind = Command.STEP_IN;
        } else if ("stepOut".equals(op)) {
            kind = Command.STEP_OUT;
        } else if ("eval".equals(op)) {
            if (source == null || source.trim().length() == 0) {
                throw new IllegalArgumentException("eval needs a non-empty source");
            }
            kind = Command.EVAL;
        } else {
            throw new IllegalArgumentException("Unknown debug op: " + op);
        }
        if (!Session.PAUSED.equals(session.state)) {
            throw new IllegalStateException("Debug session is not paused (state " + session.state + ")");
        }
        // Read the generation AFTER the pause check: if the session resumes in between, the
        // command carries the old generation and the pump refuses it at the next pause instead
        // of applying it there (duplicate-Continue / retried-eval guard).
        Command cmd = new Command(kind, source,
            "c" + session.commandSeq.incrementAndGet(), session.pauseGeneration);
        session.commands.add(cmd);
        result.put("commandId", cmd.commandId);
        boolean done;
        try {
            done = cmd.done.await(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            result.put("timedOut", Boolean.TRUE);
            result.put("message", "Command not finished after " + COMMAND_WAIT_MS
                + " ms — it stays queued; fetch the outcome later via GET .../command/"
                + cmd.commandId + " instead of retrying");
            return result;
        }
        if (cmd.conflict) {
            result.put("accepted", Boolean.FALSE);
            result.put("conflict", Boolean.TRUE);
            result.put("error", cmd.error);
            return result;
        }
        result.put("accepted", Boolean.TRUE);
        if (kind == Command.EVAL) {
            if (cmd.error != null) {
                result.put("error", cmd.error);
            } else {
                result.put("result", cmd.result);
            }
        }
        return result;
    }

    /**
     * The outcome of an earlier command by id — for callers whose {@link #command} wait timed out
     * (retrying a queued command would double-execute it). {@code done=false} means still queued/
     * executing, or an unknown id (indistinguishable by design: results are bounded).
     */
    public Map<String, Object> commandResult(String sessionId, String commandId) {
        Session session = find(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Debug session not found: " + sessionId);
        }
        session.touch();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.sessionId);
        result.put("commandId", commandId);
        Command cmd = commandId != null ? session.commandResults.get(commandId) : null;
        if (cmd == null) {
            result.put("done", Boolean.FALSE);
            return result;
        }
        result.put("done", Boolean.TRUE);
        if (cmd.conflict) {
            result.put("conflict", Boolean.TRUE);
        }
        if (cmd.error != null) {
            result.put("error", cmd.error);
        } else if (cmd.kind == Command.EVAL) {
            result.put("result", cmd.result);
        }
        return result;
    }

    /** Replace the session's breakpoint set (1-based lines; effective immediately, mid-run too). */
    public Map<String, Object> setBreakpoints(String sessionId, List<Integer> lines) {
        Session session = find(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Debug session not found: " + sessionId);
        }
        session.touch();
        synchronized (session) {
            Set<Integer> target = session.breakpoints;
            target.clear();
            if (lines != null) {
                for (Integer line : lines) {
                    if (line != null && line.intValue() > 0) {
                        target.add(line);
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.sessionId);
        result.put("breakpoints", sortedBreakpoints(session));
        return result;
    }

    /**
     * End a session's run: cancel latch + engine abort + paused-handler wake, via
     * {@link RunManager#cancelRun} (which also kills the run's SHELL tasks). The run unwinds to
     * CANCELLED with {@code reason}; the session becomes ENDED when it does.
     */
    public boolean kill(String sessionId, String reason) {
        Session session = find(sessionId);
        if (session == null || Session.ENDED.equals(session.state)) {
            return false;
        }
        return runManager.cancelRun(session.runId, reason);
    }

    /** Sessions whose run has not ended yet (the capacity the executor is holding). */
    public int activeCount() {
        int count = 0;
        for (Session session : sessions.values()) {
            if (!Session.ENDED.equals(session.state)) {
                count++;
            }
        }
        return count;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    /** Kill every live session and stop the executor. Call BEFORE RunManager.shutdown() — the
     *  unwinding debug runs still use the shared task engine and registries. */
    public void shutdown() {
        // Flag flip + executor close under the SAME lock open() holds across prepare+submit:
        // otherwise an open could pass the flag check, register its run, and then hit the closed
        // executor — leaving the registered run QUEUED forever (the catch below is belt-and-braces).
        synchronized (openLock) {
            shutdownRequested = true;
            debugExecutor.shutdown();   // already-submitted sessions still run; kills unwind them
        }
        for (Session session : sessions.values()) {
            if (!Session.ENDED.equals(session.state)) {
                kill(session.sessionId, "Server shutting down");
            }
        }
        try {
            if (!debugExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                debugExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            debugExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ===================== session/command internals =====================

    /** One debug session: a debug re-run plus its pause/command state. Fields the HTTP threads
     *  read are volatile or immutable-once-published; the frame stays fiber-confined. */
    public class Session {
        public static final String RUNNING = "RUNNING";
        public static final String PAUSED = "PAUSED";
        public static final String ENDED = "ENDED";

        public final String sessionId;
        public final String runId;
        public final String sourceRunId;
        public final long createdAt = System.currentTimeMillis();
        final BlockingQueue<Command> commands = new LinkedBlockingQueue<Command>();
        /** Pre-seeded before the engine exists; swapped for the engine's live set at onDebugReady
         *  (contents carried over), so mutations always reach the set the engine consults. */
        volatile Set<Integer> breakpoints = ConcurrentHashMap.newKeySet();
        volatile String state = RUNNING;
        /** Immutable pause description (built on the fiber, replaced wholesale). Null unless paused. */
        volatile Map<String, Object> paused;
        /** Bumped by the fiber at each pause. A queued command carries the generation it was
         *  issued against; the pump refuses a command from an earlier pause, so a duplicate
         *  Continue (or a retried eval) can never silently consume a LATER pause. */
        volatile long pauseGeneration;
        volatile long lastActivityAt = System.currentTimeMillis();
        volatile long endedAt;
        final java.util.concurrent.atomic.AtomicLong commandSeq =
            new java.util.concurrent.atomic.AtomicLong();
        /** Finished commands by id, oldest evicted — lets a caller whose command() wait timed out
         *  re-query the eventual outcome instead of retrying (and double-executing) it. */
        final Map<String, Command> commandResults = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, Command>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Command> eldest) {
                    return size() > 64;
                }
            });

        Session(String sessionId, String runId, String sourceRunId) {
            this.sessionId = sessionId;
            this.runId = runId;
            this.sourceRunId = sourceRunId;
        }

        void touch() {
            lastActivityAt = System.currentTimeMillis();
        }

        /** Enqueue a QUIT so a handler blocked at a break unwinds (see class doc, kill semantics).
         *  Generation-exempt: a kill must land no matter which pause the handler is in. */
        void wakeForCancel() {
            commands.add(new Command(Command.QUIT, null, "cancel-" + commandSeq.incrementAndGet(), -1L));
        }

        /** The RunManager wiring for this session's run. */
        class Attach implements RunManager.DebugAttach {
            @Override
            public DebugHandler handler() {
                return new SessionHandler(Session.this);
            }

            @Override
            public void onDebugReady(Set<Integer> liveBreakpoints) {
                synchronized (Session.this) {
                    liveBreakpoints.addAll(breakpoints);
                    breakpoints = liveBreakpoints;
                }
            }

            @Override
            public void onCancelWake() {
                wakeForCancel();
            }
        }
    }

    /** A queued debugger command; the paused handler executes it on the engine fiber. */
    static class Command {
        static final int CONTINUE = 0;
        static final int STEP_OVER = 1;
        static final int STEP_IN = 2;
        static final int STEP_OUT = 3;
        static final int EVAL = 4;
        static final int QUIT = 5;

        final int kind;
        final String source;
        final String commandId;
        /** The pause this command was issued against ({@link Session#pauseGeneration}; -1 = any). */
        final long generation;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;    // eval echo in display form (null = no echo)
        volatile String error;     // eval/refusal error message
        volatile boolean conflict; // refused: issued against an earlier pause

        Command(int kind, String source, String commandId, long generation) {
            this.kind = kind;
            this.source = source;
            this.commandId = commandId;
            this.generation = generation;
        }
    }

    /** Runs on the paused engine fiber: publishes the pause snapshot, then pumps commands. */
    private class SessionHandler implements DebugHandler {
        private final Session session;

        SessionHandler(Session session) {
            this.session = session;
        }

        @Override
        public void onBreak(DebugFrame frame) {
            session.pauseGeneration++;   // single writer: only this fiber pauses the session
            session.paused = buildSnapshot(frame);
            session.state = Session.PAUSED;
            while (true) {
                Command cmd;
                try {
                    cmd = session.commands.take();
                } catch (InterruptedException e) {
                    // Executor teardown — end the run rather than resume it headless.
                    Thread.currentThread().interrupt();
                    session.state = Session.RUNNING;
                    session.paused = null;
                    frame.quit();
                    return;
                }
                session.touch();
                if (cmd.kind != Command.QUIT && cmd.generation != session.pauseGeneration) {
                    // Issued against an EARLIER pause (e.g. a duplicate Continue, or a retried
                    // eval) — executing it here would silently consume this pause / run in the
                    // wrong frame. Refuse it and keep waiting.
                    cmd.conflict = true;
                    cmd.error = "Superseded: the session resumed and paused again after this command was issued";
                    finishCommand(session, cmd);
                    continue;
                }
                switch (cmd.kind) {
                    case Command.EVAL:
                        try {
                            Object echo = frame.eval(cmd.source);
                            cmd.result = echo != null ? display(echo, EVAL_DISPLAY_MAX) : null;
                        } catch (RuntimeException e) {
                            // TeeError / SyntaxException — the paused script itself is intact.
                            cmd.error = e.getMessage() != null ? e.getMessage() : e.toString();
                        }
                        // The eval may have written locals/globals — republish the snapshot.
                        session.paused = buildSnapshot(frame);
                        finishCommand(session, cmd);
                        break;   // stay paused
                    case Command.STEP_OVER:
                        frame.stepOver();
                        resume(cmd);
                        return;
                    case Command.STEP_IN:
                        frame.stepInto();
                        resume(cmd);
                        return;
                    case Command.STEP_OUT:
                        frame.stepOut();
                        resume(cmd);
                        return;
                    case Command.QUIT:
                        session.state = Session.RUNNING;
                        session.paused = null;
                        finishCommand(session, cmd);
                        frame.quit();   // throws DebugQuit — unwinds the run
                        return;
                    case Command.CONTINUE:
                    default:
                        resume(cmd);
                        return;
                }
            }
        }

        private void resume(Command cmd) {
            session.state = Session.RUNNING;
            session.paused = null;
            finishCommand(session, cmd);
        }
    }

    /** Record the finished command for later {@link #commandResult} queries, then release the
     *  waiter. Record-first: a caller woken by the latch must find the result already stored. */
    private void finishCommand(Session session, Command cmd) {
        session.commandResults.put(cmd.commandId, cmd);
        cmd.done.countDown();
    }

    private void finalizeSession(Session session) {
        session.state = Session.ENDED;
        session.paused = null;
        session.endedAt = System.currentTimeMillis();
        // Free any HTTP thread still waiting on a queued command.
        while (true) {
            Command cmd = session.commands.poll();
            if (cmd == null) {
                break;
            }
            cmd.error = "Debug session ended";
            finishCommand(session, cmd);
        }
        RunInfo run = runManager.getRun(session.runId);
        TeeBoxLog.info("DebugSession", "Session " + session.sessionId + " ended — run "
            + session.runId + " " + (run != null ? run.status : "?"));
    }

    /** Maintenance tick: drop stale ENDED sessions, kill abandoned live ones (no API activity —
     *  status polling counts as activity, so "abandoned" means nobody is even watching). Runs on
     *  the RunManager maintenance interval; public so tests can force a tick. */
    public void sweep() {
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            if (Session.ENDED.equals(session.state)) {
                if (now - session.endedAt > ENDED_RETENTION_MS) {
                    sessions.remove(session.sessionId);
                }
            } else if (now - session.lastActivityAt > idleTimeoutMs) {
                TeeBoxLog.warn("DebugSession", "Session " + session.sessionId
                    + " idle for over " + idleTimeoutMs + " ms — killing");
                kill(session.sessionId, "Debug session idle timeout (" + idleTimeoutMs + " ms)");
            }
        }
    }

    // ===================== snapshots / helpers =====================

    private Map<String, Object> buildSnapshot(DebugFrame frame) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("reason", frame.reason().name());
        snapshot.put("line", Integer.valueOf(frame.line()));
        snapshot.put("column", Integer.valueOf(frame.column()));
        snapshot.put("statement", frame.statementText());
        List<Map<String, Object>> stack = new ArrayList<Map<String, Object>>();
        for (DebugCallSite site : frame.callStack()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("function", site.function());
            entry.put("line", Integer.valueOf(site.line()));
            entry.put("column", Integer.valueOf(site.column()));
            stack.add(entry);
        }
        snapshot.put("callStack", stack);
        snapshot.put("locals", displayMap(frame.locals()));
        snapshot.put("globals", displayMap(frame.globals()));
        return snapshot;
    }

    private Map<String, String> displayMap(Map<String, Object> values) {
        Map<String, String> display = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            display.put(entry.getKey(), display(entry.getValue(), VALUE_DISPLAY_MAX));
        }
        return display;
    }

    private String display(Object value, int maxChars) {
        String formatted;
        try {
            formatted = TypeChecker.formatValue(value);
        } catch (RuntimeException e) {
            formatted = String.valueOf(value);
        }
        if (formatted != null && formatted.length() > maxChars) {
            return formatted.substring(0, maxChars) + "...";
        }
        return formatted;
    }

    Map<String, Object> statusMap(Session session) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("sessionId", session.sessionId);
        map.put("sourceRunId", session.sourceRunId);
        map.put("runId", session.runId);
        map.put("state", session.state);
        map.put("createdAt", Long.valueOf(session.createdAt));
        map.put("lastActivityAt", Long.valueOf(session.lastActivityAt));
        map.put("breakpoints", sortedBreakpoints(session));
        Map<String, Object> pausedSnapshot = session.paused;
        if (pausedSnapshot != null) {
            map.put("paused", pausedSnapshot);
        }
        RunInfo run = runManager.getRun(session.runId);
        if (run != null) {
            map.put("runStatus", run.status != null ? run.status.name() : null);
            if (Session.ENDED.equals(session.state)) {
                map.put("endedAt", Long.valueOf(session.endedAt));
                if (run.errorMessage != null) {
                    map.put("errorMessage", run.errorMessage);
                }
                if (run.resultSummary != null) {
                    map.put("resultSummary", run.resultSummary);
                }
            }
        }
        return map;
    }

    private List<Integer> sortedBreakpoints(Session session) {
        List<Integer> lines = new ArrayList<Integer>(session.breakpoints);
        java.util.Collections.sort(lines);
        return lines;
    }

    /** The failing line of a positioned engine error ("... at line L:C: ..."), else null. */
    static Integer parseErrorLine(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("at line (\\d+):(\\d+)").matcher(errorMessage);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String createSessionId() {
        return "dbg-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ENGLISH).format(new Date())
            + "-" + Integer.toHexString((int) (System.nanoTime() & 0xffff));
    }
}
