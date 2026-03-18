package com.propertee.tests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.propertee.task.Task;
import com.propertee.task.TaskStatus;
import com.propertee.teebox.lifecycle.TaskLifecycle;
import com.propertee.teebox.lifecycle.TaskLossReason;
import com.propertee.teebox.lifecycle.TaskPhase;
import com.propertee.teebox.lifecycle.TaskTerminalState;

import org.junit.Assert;
import org.junit.Test;

public class TaskLifecycleTest {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ---- Invariant tests ----

    @Test(expected = IllegalArgumentException.class)
    public void terminalWithNullTerminalStateShouldThrow() {
        TaskLifecycle.createTerminal(null, null);
    }

    @Test(expected = IllegalStateException.class)
    public void lossReasonWithNonLostTerminalShouldThrow() {
        TaskLifecycle.createTerminal(TaskTerminalState.COMPLETED, TaskLossReason.PROCESS_MISSING);
    }

    // ---- Transition tests ----

    @Test
    public void activeToKilledTransition() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        boolean transitioned = lc.tryTransitionToKilled();
        Assert.assertTrue(transitioned);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.KILLED, lc.getTerminalState());
    }

    @Test
    public void activeToCompletedTransition() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        boolean transitioned = lc.tryTransitionToCompleted();
        Assert.assertTrue(transitioned);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
    }

    @Test
    public void activeToFailedTransition() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        boolean transitioned = lc.tryTransitionToFailed();
        Assert.assertTrue(transitioned);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.FAILED, lc.getTerminalState());
    }

    @Test
    public void activeToLostTransition() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        boolean transitioned = lc.tryTransitionToLost(TaskLossReason.PROCESS_MISSING);
        Assert.assertTrue(transitioned);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.LOST, lc.getTerminalState());
        Assert.assertEquals(TaskLossReason.PROCESS_MISSING, lc.getLossReason());
    }

    @Test
    public void killWinsPrePersist() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToCompleted();
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
        Assert.assertFalse(lc.isPersisted());

        boolean transitioned = lc.tryTransitionToKilled();
        Assert.assertTrue(transitioned);
        Assert.assertEquals(TaskTerminalState.KILLED, lc.getTerminalState());
    }

    @Test
    public void killBlockedAfterPersist() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToCompleted();
        lc.markPersisted();

        boolean transitioned = lc.tryTransitionToKilled();
        Assert.assertFalse(transitioned);
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
    }

    @Test
    public void killBlockedAfterPersistedFailed() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToFailed();
        lc.markPersisted();

        boolean transitioned = lc.tryTransitionToKilled();
        Assert.assertFalse(transitioned);
        Assert.assertEquals(TaskTerminalState.FAILED, lc.getTerminalState());
    }

    @Test
    public void killBlockedAfterPersistedLost() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToLost(TaskLossReason.PROCESS_MISSING);
        lc.markPersisted();

        boolean transitioned = lc.tryTransitionToKilled();
        Assert.assertFalse(transitioned);
        Assert.assertEquals(TaskTerminalState.LOST, lc.getTerminalState());
    }

    @Test
    public void firstTerminalWins() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToCompleted();
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());

        boolean transitioned = lc.tryTransitionToFailed();
        Assert.assertFalse(transitioned);
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
    }

    @Test
    public void completedThenLostIsNoOp() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToCompleted();

        boolean transitioned = lc.tryTransitionToLost(TaskLossReason.PROCESS_MISSING);
        Assert.assertFalse(transitioned);
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
    }

    @Test
    public void lostWithPidReusedReason() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.tryTransitionToLost(TaskLossReason.PID_REUSED);
        Assert.assertEquals(TaskTerminalState.LOST, lc.getTerminalState());
        Assert.assertEquals(TaskLossReason.PID_REUSED, lc.getLossReason());
    }

    @Test(expected = IllegalStateException.class)
    public void markPersistedOnActiveThrows() {
        TaskLifecycle lc = TaskLifecycle.createActive();
        lc.markPersisted();
    }

    // ---- Derivation tests ----

    @Test
    public void normalizeFromRunnerStartingMapsToActive() {
        Task task = new Task();
        task.status = TaskStatus.STARTING;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }

    @Test
    public void normalizeFromRunnerRunningMapsToActive() {
        Task task = new Task();
        task.status = TaskStatus.RUNNING;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }

    @Test
    public void normalizeFromRunnerDetachedMapsToActive() {
        Task task = new Task();
        task.status = TaskStatus.DETACHED;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }

    @Test
    public void normalizeFromRunnerCompleted() {
        Task task = new Task();
        task.status = TaskStatus.COMPLETED;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.COMPLETED, lc.getTerminalState());
    }

    @Test
    public void normalizeFromRunnerFailed() {
        Task task = new Task();
        task.status = TaskStatus.FAILED;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.FAILED, lc.getTerminalState());
    }

    @Test
    public void normalizeFromRunnerKilled() {
        Task task = new Task();
        task.status = TaskStatus.KILLED;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.KILLED, lc.getTerminalState());
    }

    @Test
    public void normalizeFromRunnerLost() {
        Task task = new Task();
        task.status = TaskStatus.LOST;
        TaskLifecycle lc = TaskLifecycle.normalizeFromRunner(task);
        Assert.assertTrue(lc.isTerminal());
        Assert.assertEquals(TaskTerminalState.LOST, lc.getTerminalState());
        Assert.assertEquals(TaskLossReason.PROCESS_MISSING, lc.getLossReason());
    }

    @Test
    public void deriveLegacyStatusActive() {
        Assert.assertEquals("running", TaskLifecycle.createActive().deriveLegacyStatus());
    }

    @Test
    public void deriveLegacyStatusTerminal() {
        Assert.assertEquals("completed",
                TaskLifecycle.createTerminal(TaskTerminalState.COMPLETED, null).deriveLegacyStatus());
        Assert.assertEquals("failed",
                TaskLifecycle.createTerminal(TaskTerminalState.FAILED, null).deriveLegacyStatus());
        Assert.assertEquals("killed",
                TaskLifecycle.createTerminal(TaskTerminalState.KILLED, null).deriveLegacyStatus());
        Assert.assertEquals("lost",
                TaskLifecycle.createTerminal(TaskTerminalState.LOST, TaskLossReason.PROCESS_MISSING).deriveLegacyStatus());
    }

    // ---- JSON round-trip tests ----

    @Test
    public void jsonRoundTripActive() {
        TaskLifecycle original = TaskLifecycle.createActive();
        JsonObject obj = new JsonObject();
        original.writeToJson(obj);

        TaskLifecycle restored = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(restored);
        Assert.assertEquals(original.getPhase(), restored.getPhase());
        Assert.assertNull(restored.getTerminalState());
        Assert.assertNull(restored.getLossReason());
        Assert.assertFalse(restored.isPersisted());
    }

    @Test
    public void jsonRoundTripTerminal() {
        TaskLifecycle original = TaskLifecycle.createTerminal(TaskTerminalState.LOST, TaskLossReason.PID_REUSED);
        original.markPersisted();
        JsonObject obj = new JsonObject();
        original.writeToJson(obj);

        TaskLifecycle restored = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(restored);
        Assert.assertEquals(TaskPhase.TERMINAL, restored.getPhase());
        Assert.assertEquals(TaskTerminalState.LOST, restored.getTerminalState());
        Assert.assertEquals(TaskLossReason.PID_REUSED, restored.getLossReason());
        Assert.assertTrue(restored.isPersisted());
    }

    @Test
    public void jsonRoundTripKilled() {
        TaskLifecycle original = TaskLifecycle.createTerminal(TaskTerminalState.KILLED, null);
        JsonObject obj = new JsonObject();
        original.writeToJson(obj);

        TaskLifecycle restored = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(restored);
        Assert.assertEquals(TaskTerminalState.KILLED, restored.getTerminalState());
        Assert.assertNull(restored.getLossReason());
    }

    @Test
    public void readFromJsonWithoutLifecycleReturnsNull() {
        JsonObject obj = new JsonObject();
        obj.addProperty("taskId", "test-123");
        obj.addProperty("command", "echo hello");

        TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
        Assert.assertNull(lc);
    }

    @Test
    public void jsonRoundTripWithTaskFields() {
        JsonObject obj = new JsonObject();
        obj.addProperty("taskId", "task-abc");
        obj.addProperty("command", "sleep 10");
        obj.addProperty("pid", 12345);
        obj.addProperty("phase", "ACTIVE");
        obj.addProperty("persisted", false);

        Task task = gson.fromJson(obj, Task.class);
        Assert.assertEquals("task-abc", task.taskId);

        TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(lc);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }

    @Test
    public void readFromJsonWithLegacyExecutionStateIsIgnored() {
        // Backward compat: old meta.json files may have executionState field — should not crash
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", "ACTIVE");
        obj.addProperty("executionState", "RUNNING");
        obj.addProperty("persisted", false);

        TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(lc);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }

    @Test
    public void readFromJsonWithLegacyOwnershipFieldIsIgnored() {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", "ACTIVE");
        obj.addProperty("ownership", "ATTACHED");
        obj.addProperty("persisted", false);

        TaskLifecycle lc = TaskLifecycle.readFromJson(obj);
        Assert.assertNotNull(lc);
        Assert.assertEquals(TaskPhase.ACTIVE, lc.getPhase());
    }
}
