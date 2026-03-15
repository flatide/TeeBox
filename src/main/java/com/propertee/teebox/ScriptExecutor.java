package com.propertee.teebox;

import com.propertee.core.ScriptParser;
import com.propertee.interpreter.BuiltinFunctions;
import com.propertee.interpreter.ProperTeeInterpreter;
import com.propertee.parser.ProperTeeParser;
import com.propertee.runtime.TypeChecker;
import com.propertee.scheduler.Scheduler;
import com.propertee.scheduler.SchedulerListener;
import com.propertee.scheduler.ThreadContext;
import com.propertee.task.TaskEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScriptExecutor {
    public ExecutionResult execute(File scriptFile,
                                   Map<String, Object> properties,
                                   int maxIterations,
                                   String iterationLimitBehavior,
                                   String runId,
                                   TaskEngine taskEngine,
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

            BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, runId, taskEngine);
            visitor = new ProperTeeInterpreter(properties, stdout, stderr, maxIterations, iterationLimitBehavior, builtins);
            Scheduler scheduler = new Scheduler(visitor, callbacks != null ? new CallbackSchedulerListener(callbacks) : null);
            ProperTeeInterpreter.RootStepper mainStepper = visitor.createRootStepper(tree);
            Object result = scheduler.run(mainStepper);
            Object outputData = null;
            if (mainStepper.hasExplicitReturn()) {
                outputData = TypeChecker.deepCopy(result);
            } else if (visitor.variables.containsKey("result")) {
                outputData = TypeChecker.deepCopy(visitor.variables.get("result"));
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
