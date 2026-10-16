package com.flatide.teebox;

/** Public input contract for one resolved version, without source or administrative settings. */
public final class ScriptInputs {
    public final String scriptId;
    public final String alias;
    public final String version;
    public final boolean hasInputSchema;
    public final ScriptInputSchema inputSchema;

    ScriptInputs(ScriptInfo script, ScriptVersionInfo selected) {
        scriptId = script.scriptId;
        alias = script.alias;
        version = selected.version;
        hasInputSchema = selected.inputSchema != null;
        inputSchema = hasInputSchema ? selected.inputSchema.copy() : null;
    }
}
