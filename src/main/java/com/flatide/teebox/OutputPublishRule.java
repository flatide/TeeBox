package com.flatide.teebox;

public class OutputPublishRule {
    public String stream = "stdout";
    public String pattern;
    public int captureGroup = 1;
    public String publishKey;
    public boolean firstOnly = true;
    /** Which task this rule watches: null = the run's first task (legacy behavior);
     *  otherwise the first task launched with env TEEBOX_TASK_KEY equal to this value. */
    public String taskKey;
    /** Continuous mode only (firstOnly=false): stop after this many captures. 0 = unlimited
     *  (capture until the task terminates). Ignored when firstOnly=true. */
    public int maxCaptures = 0;

    public OutputPublishRule copy() {
        OutputPublishRule copy = new OutputPublishRule();
        copy.stream = stream;
        copy.pattern = pattern;
        copy.captureGroup = captureGroup;
        copy.publishKey = publishKey;
        copy.firstOnly = firstOnly;
        copy.taskKey = taskKey;
        copy.maxCaptures = maxCaptures;
        return copy;
    }
}
