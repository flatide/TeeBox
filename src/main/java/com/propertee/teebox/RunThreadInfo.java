package com.propertee.teebox;

public class RunThreadInfo {
    public int threadId;
    public String name;
    public String state;
    public Integer parentId;
    public boolean inThreadContext;
    public Long sleepUntil;
    public boolean asyncPending;
    public String resultKeyName;
    public String resultSummary;
    public String errorMessage;
    public long updatedAt;

    public RunThreadInfo copy() {
        RunThreadInfo copy = new RunThreadInfo();
        copy.threadId = threadId;
        copy.name = name;
        copy.state = state;
        copy.parentId = parentId;
        copy.inThreadContext = inThreadContext;
        copy.sleepUntil = sleepUntil;
        copy.asyncPending = asyncPending;
        copy.resultKeyName = resultKeyName;
        copy.resultSummary = resultSummary;
        copy.errorMessage = errorMessage;
        copy.updatedAt = updatedAt;
        return copy;
    }
}
