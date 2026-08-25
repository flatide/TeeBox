package com.flatide.teebox;

/** Immutable module version pinned into a run at import resolution time. */
public class ResolvedModuleInfo {
    public String scriptId;
    public String version;
    public String sha256;

    public ResolvedModuleInfo copy() {
        ResolvedModuleInfo copy = new ResolvedModuleInfo();
        copy.scriptId = scriptId;
        copy.version = version;
        copy.sha256 = sha256;
        return copy;
    }
}
