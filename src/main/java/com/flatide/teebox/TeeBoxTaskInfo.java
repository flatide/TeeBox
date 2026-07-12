package com.flatide.teebox;

import com.flatide.propertee2.task.TaskInfo;

/**
 * Extended TaskInfo with lifecycle fields (phase, ownership, lossReason).
 */
public class TeeBoxTaskInfo extends TaskInfo {
    public String phase;
    public String lossReason;
}
