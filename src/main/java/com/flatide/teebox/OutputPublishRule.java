package com.flatide.teebox;

public class OutputPublishRule {
    public String stream = "stdout";
    public String pattern;
    public int captureGroup = 1;
    public String publishKey;
    /** Which task this rule watches, by SHELL() execution order within the run: 0 (default) =
     *  the run's first task, 1 = the second, etc. Note: with SHELLs running in parallel
     *  threads (multi/thread), creation order is not deterministic. */
    public int taskIndex = 0;
    /** How many matches to capture: 1 (default) = the first match only, 0 = unlimited
     *  (capture until the task terminates), N = up to N matches. */
    public int maxCaptures = 1;
    /** Deprecated pre-1.18 mode flag, retained ONLY so persisted 1.17.x rules keep their
     *  meaning on load (1.17.x wrote maxCaptures=0 even for first-only rules). Mapped into
     *  maxCaptures by {@link #normalize()} and nulled, so re-saves drop it. */
    @Deprecated
    public Boolean firstOnly;

    /** Fold the deprecated firstOnly flag into maxCaptures and clamp negatives. Idempotent;
     *  called on registry load and API/form parse so the rest of the code sees one knob. */
    public void normalize() {
        if (firstOnly != null) {
            if (firstOnly.booleanValue()) {
                maxCaptures = 1;
            }
            // firstOnly=false: maxCaptures already carries the 1.17.x intent (0 = unlimited)
            firstOnly = null;
        }
        if (maxCaptures < 0) maxCaptures = 0;
        if (taskIndex < 0) taskIndex = 0;
        // A negative group would make matcher.group(g) throw on every match — and a throwing
        // watcher used to wedge the run's terminal transition. 0 = the full match.
        if (captureGroup < 0) captureGroup = 0;
    }

    public OutputPublishRule copy() {
        OutputPublishRule copy = new OutputPublishRule();
        copy.stream = stream;
        copy.pattern = pattern;
        copy.captureGroup = captureGroup;
        copy.publishKey = publishKey;
        copy.taskIndex = taskIndex;
        copy.maxCaptures = maxCaptures;
        copy.firstOnly = firstOnly;
        return copy;
    }
}
