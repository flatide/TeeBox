package com.flatide.teebox;

import com.flatide.core.ScriptParser;
import com.flatide.interpreter.BuiltinFunctions;
import com.flatide.interpreter.ProperTeeInterpreter;
import com.flatide.parser.ProperTeeParser;
import com.flatide.platform.PlatformProvider;
import com.flatide.runtime.TypeChecker;
import com.flatide.scheduler.Scheduler;
import com.flatide.scheduler.SchedulerListener;
import com.flatide.scheduler.ThreadContext;
import com.flatide.task.TaskRunner;

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

    public ScriptExecutor(PlatformProvider platformProvider) {
        this(platformProvider, null);
    }

    public ScriptExecutor(PlatformProvider platformProvider, StreamResultSupport streamSupport) {
        this.platformProvider = platformProvider;
        this.streamSupport = streamSupport;
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
        ProperTeeInterpreter visitor = null;
        try {
            String scriptText = readFile(scriptFile);
            List<String> errors = new ArrayList<String>();
            ProperTeeParser.RootContext tree = ScriptParser.parse(scriptText, errors);
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
            // Reserved `_SYS`: TeeBox system variables for the run, exposed as a global object so a
            // script can read its own run id (e.g. _SYS.runId, or ::_SYS.runId inside a function).
            // Injected as a global variable — NOT into properties — so `_PROPS` stays user-input only.
            Map<String, Object> sys = new java.util.LinkedHashMap<String, Object>();
            sys.put("runId", runId != null ? runId : "");
            sys.put("scriptId", scriptId != null ? scriptId : "");
            sys.put("version", version != null ? version : "");
            visitor.variables.put("_SYS", sys);
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
    }

    public static class ExecutionResult {
        public final boolean success;
        public final boolean hasExplicitReturn;
        public final Object resultData;
        public final String errorMessage;

        private ExecutionResult(boolean success, boolean hasExplicitReturn, Object resultData, String errorMessage) {
            this.success = success;
            this.hasExplicitReturn = hasExplicitReturn;
            this.resultData = resultData;
            this.errorMessage = errorMessage;
        }

        public static ExecutionResult completed(boolean hasExplicitReturn, Object resultData) {
            return new ExecutionResult(true, hasExplicitReturn, resultData, null);
        }

        public static ExecutionResult failed(String errorMessage) {
            return new ExecutionResult(false, false, null, errorMessage);
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
