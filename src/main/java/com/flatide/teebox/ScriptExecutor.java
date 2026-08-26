package com.flatide.teebox;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.interpreter.BuiltinFunctions;
import com.flatide.propertee2.interpreter.ProperTeeInterpreter;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.platform.PlatformProvider;
import com.flatide.propertee2.runtime.TypeChecker;
import com.flatide.propertee2.scheduler.Scheduler;
import com.flatide.propertee2.scheduler.SchedulerListener;
import com.flatide.propertee2.scheduler.ThreadContext;
import com.flatide.propertee2.task.TaskRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptExecutor {
    private final PlatformProvider platformProvider;
    private final StreamResultSupport streamSupport;
    private final ScriptRegistry scriptRegistry;

    public ScriptExecutor(PlatformProvider platformProvider) {
        this(platformProvider, null, null);
    }

    public ScriptExecutor(PlatformProvider platformProvider, StreamResultSupport streamSupport) {
        this(platformProvider, streamSupport, null);
    }

    public ScriptExecutor(PlatformProvider platformProvider, StreamResultSupport streamSupport,
                          ScriptRegistry scriptRegistry) {
        this.platformProvider = platformProvider;
        this.streamSupport = streamSupport;
        this.scriptRegistry = scriptRegistry;
    }

    /**
     * Every function name a TeeBox run can call: the engine set (interpreter dispatch + catalog)
     * plus the TeeBox host builtins registered in {@link #execute} — keep the registrations there
     * and the names here in step. Drives the editor's unknown-builtin lint (see ScriptLint), so
     * the lint and the runtime can never disagree about what exists.
     */
    public java.util.Set<String> knownFunctionNames() {
        BuiltinFunctions builtins = new BuiltinFunctions(null, null, null, null, platformProvider);
        java.util.Set<String> names = new java.util.TreeSet<String>(builtins.knownFunctionNames());
        if (streamSupport != null) {
            names.add("STREAM_FILE");
            names.add("THUMBNAIL");
        }
        return names;
    }

    public ExecutionResult execute(File scriptFile,
                                   Map<String, Object> properties,
                                   int maxIterations,
                                   String iterationLimitBehavior,
                                   String runId,
                                   String scriptId,
                                   String version,
                                   TaskRunner taskRunner,
                                   final Callbacks callbacks) {
        return execute(scriptFile, properties, maxIterations, iterationLimitBehavior,
            runId, scriptId, version, taskRunner, callbacks, null);
    }

    /**
     * Debug variant (engine 0.29.0 hooks): with a non-null {@code debugHandler} the run becomes a
     * debug mode — {@code debug} statements, line breakpoints, and stepping pause the main or a
     * {@code multi} worker and hand a thread-identified {@code DebugFrame} to the handler ON the
     * paused engine fiber (the whole run is frozen while the handler blocks; monitor bodies stay
     * excluded). The handler receives the live breakpoint set via
     * {@link Callbacks#onDebugReady} before the run starts. {@code DebugFrame.quit()} ends the run
     * as {@link ExecutionResult#cancelled()} — an operator decision, never a script failure.
     */
    public ExecutionResult execute(File scriptFile,
                                   Map<String, Object> properties,
                                   int maxIterations,
                                   String iterationLimitBehavior,
                                   String runId,
                                   String scriptId,
                                   String version,
                                   TaskRunner taskRunner,
                                   final Callbacks callbacks,
                                   com.flatide.propertee2.interp.DebugHandler debugHandler) {
        return execute(scriptFile, properties, maxIterations, iterationLimitBehavior, runId,
            scriptId, version, taskRunner, callbacks, debugHandler, null);
    }

    /** As above, executing {@code sourceSnapshot} instead of re-reading {@code scriptFile} when
     *  non-null. Debug hosts use this so the displayed source, entry line, and executed AST remain
     *  one immutable launch snapshot even if an operator edits the same version concurrently. */
    public ExecutionResult execute(File scriptFile,
                                   Map<String, Object> properties,
                                   int maxIterations,
                                   String iterationLimitBehavior,
                                   String runId,
                                   String scriptId,
                                   String version,
                                   TaskRunner taskRunner,
                                   final Callbacks callbacks,
                                   com.flatide.propertee2.interp.DebugHandler debugHandler,
                                   String sourceSnapshot) {
        ProperTeeInterpreter visitor = null;
        try {
            String scriptText = sourceSnapshot != null ? sourceSnapshot : readFile(scriptFile);
            List<String> errors = new ArrayList<String>();
            ProperTeeParser.RootContext tree = ScriptParser.parse(scriptText,
                    scriptId + "@" + version, errors);
            if (tree == null) {
                return ExecutionResult.failed(joinErrors(errors));
            }

            BuiltinFunctions.PrintFunction stdout = new BuiltinFunctions.PrintFunction() {
                @Override
                public void print(Object[] args) {
                    if (callbacks != null) {
                        callbacks.onStdout(joinPrintArgs(args));
                    }
                }
            };
            BuiltinFunctions.PrintFunction stderr = new BuiltinFunctions.PrintFunction() {
                @Override
                public void print(Object[] args) {
                    if (callbacks != null) {
                        callbacks.onStderr(joinPrintArgs(args));
                    }
                }
            };

            // The runner is owned by RunManager and shared by every concurrent run. BuiltinFunctions
            // closes its runner when this interpreter finishes, so expose a non-closing per-run view;
            // otherwise one short script clears the task map used by every long-running SHELL().
            TaskRunner runTaskRunner = taskRunner != null ? new NonClosingTaskRunner(taskRunner) : null;
            BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, runId, runTaskRunner, platformProvider);
            if (streamSupport != null) {
                // Host builtin: STREAM_FILE(path, [contentType]) returns a small stream descriptor
                // (raw object, no Result wrapper) that TeeBox streams directly. Lets a script return a
                // large file without reading/parsing it into the engine heap. Throws on a path outside
                // the allowed stream roots or a missing file.
                final StreamResultSupport support = streamSupport;
                builtins.register("STREAM_FILE", new BuiltinFunctions.BuiltinFunction() {
                    @Override
                    public Object call(List<Object> args) {
                        if (args.isEmpty() || !(args.get(0) instanceof String)) {
                            throw new RuntimeException("STREAM_FILE() requires (path, [contentType])");
                        }
                        String contentType = (args.size() > 1 && args.get(1) instanceof String)
                            ? (String) args.get(1) : null;
                        return support.describe((String) args.get(0), contentType);
                    }
                });

                // Host builtin: THUMBNAIL(srcPath, destPath, maxWidth, [maxHeight]) — scales an image
                // (anything ImageIO reads: PNG/JPEG/...) to fit within the bounds preserving aspect ratio,
                // writes a PNG. Registered as BLOCKING so the read/scale/write runs OFF the cooperative
                // baton (real disk + CPU work). src + dest are restricted to the same allowed roots as
                // STREAM_FILE. Returns {path, width, height} (auto-wrapped in a Result) or Result.error.
                builtins.registerBlocking("THUMBNAIL", new BuiltinFunctions.BuiltinFunction() {
                    @Override
                    public Object call(List<Object> args) {
                        return Thumbnailer.create(args, support);
                    }
                });
            }
            visitor = new ProperTeeInterpreter(properties, stdout, stderr, maxIterations, iterationLimitBehavior, builtins);
            if (scriptRegistry != null) {
                final Callbacks importCallbacks = callbacks;
                visitor.setModuleResolver(new TeeBoxModuleResolver(scriptRegistry,
                        module -> { if (importCallbacks != null) importCallbacks.onModuleResolved(module); },
                        (sourceId, source) -> {
                            if (importCallbacks != null) {
                                importCallbacks.onModuleSourceResolved(sourceId, source);
                            }
                        }));
            }
            // Reserved `_SYS`: TeeBox system variables for the run, exposed as a global object so a
            // script can read its own run id (e.g. _SYS.runId, or ::_SYS.runId inside a function).
            // Injected as a global variable — NOT into properties — so `_PROPS` stays user-input only.
            Map<String, Object> sys = new java.util.LinkedHashMap<String, Object>();
            sys.put("runId", runId != null ? runId : "");
            sys.put("scriptId", scriptId != null ? scriptId : "");
            sys.put("version", version != null ? version : "");
            visitor.variables.put("_SYS", sys);
            if (debugHandler != null) {
                visitor.setDebugHandler(debugHandler);
                // Hand out the engine's LIVE breakpoint set (concurrent; mutations before or
                // mid-run take effect) before the cancel handle, so a cancel that fires at
                // registration finds the debug session fully wired.
                if (callbacks != null) {
                    callbacks.onDebugReady(visitor.debugBreakpoints());
                }
            }
            // Hand the host a cancel handle BEFORE the run starts. RunManager re-checks its cancel
            // latch right after storing the handle, so a cancel that raced this registration still
            // aborts the run (the engine-side abort is itself latched and thread-safe).
            if (callbacks != null) {
                final ProperTeeInterpreter running = visitor;
                callbacks.onCancelHandle(new Runnable() {
                    @Override
                    public void run() {
                        running.abort();
                    }
                });
            }
            Scheduler scheduler = new Scheduler(visitor, callbacks != null ? new CallbackSchedulerListener(callbacks) : null);
            ProperTeeInterpreter.RootStepper mainStepper = visitor.createRootStepper(tree);
            Object result = scheduler.run(mainStepper);
            // Resolve the run's result value. ProperTee has "no implicit null" — absence is {} (an empty
            // object), never null — so a script that neither returns nor sets a `result` variable yields
            // {} here, exactly like `return` / `return {}`. (An explicit `return null` still produces the
            // first-class null value; that is a deliberate value, not absence.)
            Object outputData;
            if (mainStepper.hasExplicitReturn()) {
                outputData = TypeChecker.deepCopy(result);
            } else if (visitor.variables.containsKey("result")) {
                outputData = TypeChecker.deepCopy(visitor.variables.get("result"));
            } else {
                outputData = new LinkedHashMap<String, Object>();
            }
            return ExecutionResult.completed(mainStepper.hasExplicitReturn(), outputData);
        } catch (com.flatide.propertee2.runtime.ProperTeeAborted aborted) {
            // Host-initiated cancel — deliberately NOT a script error (the Throwable arm below
            // would misreport it as FAILED); RunManager maps this to RunStatus.CANCELLED.
            return ExecutionResult.cancelled();
        } catch (com.flatide.propertee2.interp.DebugQuit quit) {
            // Operator ended the run from the debugger (DebugFrame.quit()) — like an abort, an
            // operator decision, never a script failure; the Throwable arm would mark it FAILED.
            return ExecutionResult.cancelled();
        } catch (Throwable error) {
            return ExecutionResult.failed(error != null ? error.getMessage() : "Unknown error");
        } finally {
            if (visitor != null) {
                visitor.builtins.shutdown();
            }
        }
    }

    public interface Callbacks {
        void onStdout(String line);
        void onStderr(String line);
        void onThreadCreated(ThreadContext thread);
        void onThreadUpdated(ThreadContext thread);
        void onThreadCompleted(ThreadContext thread);
        void onThreadError(ThreadContext thread);
        /** Receives a handle that cooperatively aborts this execution (thread-safe, callable from
         *  any thread). Delivered once the engine exists, before the run starts; hosts store it to
         *  implement run cancel. Default: ignored, so existing Callbacks impls keep compiling. */
        default void onCancelHandle(Runnable cancelHandle) { }

        /** Debug runs only: receives the engine's live breakpoint set (1-based lines; concurrent,
         *  mutable before and mid-run) once the engine exists, before the run starts and before
         *  {@link #onCancelHandle}. Default: ignored. */
        default void onDebugReady(java.util.Set<Integer> liveBreakpoints) { }

        /** Called once for each exact imported script version pinned before entry execution. */
        default void onModuleResolved(ResolvedModuleInfo module) { }

        /** Debug runs only: the immutable module source snapshot keyed by its frame sourceId. */
        default void onModuleSourceResolved(String sourceId, String source) { }
    }

    public static class ExecutionResult {
        public final boolean success;
        /** True when the run ended because the host cancelled it (never a script failure). */
        public final boolean cancelled;
        public final boolean hasExplicitReturn;
        public final Object resultData;
        public final String errorMessage;

        private ExecutionResult(boolean success, boolean cancelled, boolean hasExplicitReturn,
                                Object resultData, String errorMessage) {
            this.success = success;
            this.cancelled = cancelled;
            this.hasExplicitReturn = hasExplicitReturn;
            this.resultData = resultData;
            this.errorMessage = errorMessage;
        }

        public static ExecutionResult completed(boolean hasExplicitReturn, Object resultData) {
            return new ExecutionResult(true, false, hasExplicitReturn, resultData, null);
        }

        public static ExecutionResult failed(String errorMessage) {
            return new ExecutionResult(false, false, false, null, errorMessage);
        }

        public static ExecutionResult cancelled() {
            return new ExecutionResult(false, true, false, null, "Run aborted");
        }
    }

    private static class CallbackSchedulerListener implements SchedulerListener {
        private final Callbacks callbacks;

        private CallbackSchedulerListener(Callbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void onThreadCreated(ThreadContext thread) {
            callbacks.onThreadCreated(thread);
        }

        @Override
        public void onThreadUpdated(ThreadContext thread) {
            callbacks.onThreadUpdated(thread);
        }

        @Override
        public void onThreadCompleted(ThreadContext thread) {
            callbacks.onThreadCompleted(thread);
        }

        @Override
        public void onThreadError(ThreadContext thread) {
            callbacks.onThreadError(thread);
        }
    }

    private static String readFile(File file) throws IOException {
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(bytes, 0, offset, Charset.forName("UTF-8"));
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private static String joinErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(errors.get(i));
        }
        return sb.toString();
    }

    private static String joinPrintArgs(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
