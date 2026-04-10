package com.propertee.teebox;

import java.util.ArrayList;
import java.util.List;

public class ScriptVersionInfo {
    public String version;
    public String description;
    public List<String> labels = new ArrayList<String>();
    public String sha256;
    public long createdAt;
    public boolean active;
    public List<OutputPublishRule> outputRules;

    public ScriptVersionInfo copy() {
        ScriptVersionInfo copy = new ScriptVersionInfo();
        copy.version = version;
        copy.description = description;
        copy.labels = new ArrayList<String>(labels);
        copy.sha256 = sha256;
        copy.createdAt = createdAt;
        copy.active = active;
        if (outputRules != null) {
            copy.outputRules = new ArrayList<OutputPublishRule>();
            for (OutputPublishRule rule : outputRules) {
                copy.outputRules.add(rule.copy());
            }
        }
        return copy;
    }
}
