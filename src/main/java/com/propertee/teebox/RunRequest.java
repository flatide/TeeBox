package com.propertee.teebox;

import java.util.LinkedHashMap;
import java.util.Map;

public class RunRequest {
    public String scriptPath;
    public String scriptId;
    public String version;
    public Map<String, Object> props = new LinkedHashMap<String, Object>();
    public int maxIterations = 1000;
    public boolean warnLoops = false;
}
