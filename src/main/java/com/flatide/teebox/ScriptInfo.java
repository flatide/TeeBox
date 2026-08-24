package com.flatide.teebox;

import java.util.ArrayList;
import java.util.List;

public class ScriptInfo {
    public String scriptId;
    /** Optional human-readable script name. Script identity and execution still use scriptId. */
    public String alias;
    public String activeVersion;
    public long createdAt;
    public long updatedAt;
    public int maxConcurrentRuns;   // 0 = unlimited (use global limit)
    public boolean immediate;       // bypass global queue
    public long deletedAt;          // 0 = active; > 0 = soft-deleted timestamp (ms)
    public String owner;            // admin-UI username of the first registrant; null = legacy/API (admin-only in UI)
    public List<ScriptVersionInfo> versions = new ArrayList<ScriptVersionInfo>();

    public ScriptInfo copy() {
        ScriptInfo copy = new ScriptInfo();
        copy.scriptId = scriptId;
        copy.alias = alias;
        copy.activeVersion = activeVersion;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.maxConcurrentRuns = maxConcurrentRuns;
        copy.immediate = immediate;
        copy.deletedAt = deletedAt;
        copy.owner = owner;
        for (ScriptVersionInfo version : versions) {
            copy.versions.add(version.copy());
        }
        return copy;
    }
}
