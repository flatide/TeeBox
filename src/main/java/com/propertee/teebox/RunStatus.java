package com.propertee.teebox;

public enum RunStatus {
    QUEUED,      // in global thread pool queue
    PENDING,     // blocked by per-script concurrency limit
    RUNNING,
    COMPLETED,
    FAILED,
    SERVER_RESTARTED
}
