package com.propertee.teebox;

import java.util.ArrayList;
import java.util.List;

public class ScriptInfo {
    public String scriptId;
    public String activeVersion;
    public long createdAt;
    public long updatedAt;
    public List<ScriptVersionInfo> versions = new ArrayList<ScriptVersionInfo>();

    public ScriptInfo copy() {
        ScriptInfo copy = new ScriptInfo();
        copy.scriptId = scriptId;
        copy.activeVersion = activeVersion;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        for (ScriptVersionInfo version : versions) {
            copy.versions.add(version.copy());
        }
        return copy;
    }
}
