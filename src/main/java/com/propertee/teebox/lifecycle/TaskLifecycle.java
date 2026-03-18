package com.propertee.teebox.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.propertee.task.Task;
import com.propertee.task.TaskStatus;

/**
 * 3-axis lifecycle model for managed tasks.
 *
 * Axes: phase, terminalState, lossReason.
 *
 * Invariants:
 * - ACTIVE -> terminalState == null
 * - TERMINAL -> terminalState != null
 * - lossReason != null -> terminalState == LOST
 * - terminal monotonicity: once TERMINAL, never back to ACTIVE
 * - kill-wins: KILLED overrides other terminal states (pre-persist)
 * - first-terminal-wins: COMPLETED/FAILED/LOST do not override each other
 */
public class TaskLifecycle {

    private TaskPhase phase;
    private TaskTerminalState terminalState;
    private TaskLossReason lossReason;
    private boolean persisted;

    private TaskLifecycle() {}

    // ---- Factories ----

    public static TaskLifecycle createActive() {
        TaskLifecycle lc = new TaskLifecycle();
        lc.phase = TaskPhase.ACTIVE;
        lc.terminalState = null;
        lc.lossReason = null;
        lc.persisted = false;
        return lc;
    }

    public static TaskLifecycle createTerminal(TaskTerminalState terminalState, TaskLossReason lossReason) {
        if (terminalState == null) throw new IllegalArgumentException("terminalState required for TERMINAL");
        TaskLifecycle lc = new TaskLifecycle();
        lc.phase = TaskPhase.TERMINAL;
        lc.terminalState = terminalState;
        lc.lossReason = lossReason;
        lc.persisted = false;
        lc.validate();
        return lc;
    }

    // ---- Transition methods (return true if transition occurred) ----

    public boolean tryTransitionToKilled() {
        if (phase == TaskPhase.TERMINAL && persisted) return false;
        phase = TaskPhase.TERMINAL;
        terminalState = TaskTerminalState.KILLED;
        lossReason = null;
        validate();
        return true;
    }

    public boolean tryTransitionToCompleted() {
        if (phase == TaskPhase.TERMINAL) return false;
        phase = TaskPhase.TERMINAL;
        terminalState = TaskTerminalState.COMPLETED;
        validate();
        return true;
    }

    public boolean tryTransitionToFailed() {
        if (phase == TaskPhase.TERMINAL) return false;
        phase = TaskPhase.TERMINAL;
        terminalState = TaskTerminalState.FAILED;
        validate();
        return true;
    }

    public boolean tryTransitionToLost(TaskLossReason reason) {
        if (phase == TaskPhase.TERMINAL) return false;
        phase = TaskPhase.TERMINAL;
        terminalState = TaskTerminalState.LOST;
        lossReason = reason;
        validate();
        return true;
    }

    public void markPersisted() {
        if (phase != TaskPhase.TERMINAL) {
            throw new IllegalStateException("Cannot mark persisted: not terminal");
        }
        persisted = true;
    }

    // ---- Validation ----

    public void validate() {
        if (phase == TaskPhase.ACTIVE) {
            if (terminalState != null) {
                throw new IllegalStateException("ACTIVE phase must not have terminalState");
            }
        }
        if (phase == TaskPhase.TERMINAL) {
            if (terminalState == null) {
                throw new IllegalStateException("TERMINAL phase requires terminalState");
            }
        }
        if (lossReason != null && terminalState != TaskTerminalState.LOST) {
            throw new IllegalStateException("lossReason requires terminalState=LOST");
        }
    }

    // ---- Accessors ----

    public TaskPhase getPhase() { return phase; }
    public TaskTerminalState getTerminalState() { return terminalState; }
    public TaskLossReason getLossReason() { return lossReason; }
    public boolean isPersisted() { return persisted; }

    public boolean isTerminal() { return phase == TaskPhase.TERMINAL; }
    public boolean isActive() { return phase == TaskPhase.ACTIVE; }

    // ---- Legacy status derivation ----

    /**
     * Maps lifecycle state to legacy TaskStatus.value() string.
     */
    public String deriveLegacyStatus() {
        if (phase == TaskPhase.ACTIVE) {
            return "running";
        }
        // TERMINAL
        switch (terminalState) {
            case COMPLETED: return "completed";
            case FAILED: return "failed";
            case KILLED: return "killed";
            case LOST: return "lost";
            default: return "unknown";
        }
    }

    // ---- JSON serialization ----

    public void writeToJson(JsonObject obj) {
        obj.addProperty("phase", phase.name());
        if (terminalState != null) {
            obj.addProperty("terminalState", terminalState.name());
        }
        if (lossReason != null) {
            obj.addProperty("lossReason", lossReason.name());
        }
        obj.addProperty("persisted", persisted);
    }

    public static TaskLifecycle readFromJson(JsonObject obj) {
        JsonElement phaseEl = obj.get("phase");
        if (phaseEl == null || phaseEl.isJsonNull()) return null;

        TaskLifecycle lc = new TaskLifecycle();
        lc.phase = TaskPhase.valueOf(phaseEl.getAsString());

        JsonElement termEl = obj.get("terminalState");
        lc.terminalState = (termEl != null && !termEl.isJsonNull())
                ? TaskTerminalState.valueOf(termEl.getAsString()) : null;

        JsonElement lossEl = obj.get("lossReason");
        lc.lossReason = (lossEl != null && !lossEl.isJsonNull())
                ? TaskLossReason.valueOf(lossEl.getAsString()) : null;

        JsonElement persistedEl = obj.get("persisted");
        lc.persisted = (persistedEl != null && !persistedEl.isJsonNull()) && persistedEl.getAsBoolean();

        lc.validate();
        return lc;
    }

    // ---- Normalize from core Task ----

    /**
     * Creates a lifecycle from the current state of a core Task object.
     * Used when first receiving a task from DefaultTaskRunner or migrating
     * existing tasks that lack lifecycle metadata.
     */
    public static TaskLifecycle normalizeFromRunner(Task task) {
        if (task.status == null) {
            return createActive();
        }
        switch (task.status) {
            case STARTING:
            case RUNNING:
            case DETACHED:
                return createActive();
            case COMPLETED:
                return createTerminal(TaskTerminalState.COMPLETED, null);
            case FAILED:
                return createTerminal(TaskTerminalState.FAILED, null);
            case KILLED:
                return createTerminal(TaskTerminalState.KILLED, null);
            case LOST:
                return createTerminal(TaskTerminalState.LOST, TaskLossReason.PROCESS_MISSING);
            default:
                return createActive();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TaskLifecycle{");
        sb.append(phase);
        if (terminalState != null) sb.append(", ").append(terminalState);
        if (lossReason != null) sb.append(", reason=").append(lossReason);
        if (persisted) sb.append(", persisted");
        sb.append('}');
        return sb.toString();
    }
}
