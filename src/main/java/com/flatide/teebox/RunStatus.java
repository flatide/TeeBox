package com.flatide.teebox;

public enum RunStatus {
    QUEUED,      // in global thread pool queue
    PENDING,     // blocked by per-script concurrency limit
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,   // stopped by operator/client cancel or run timeout (terminal; reason in errorMessage)
    SERVER_RESTARTED
}
