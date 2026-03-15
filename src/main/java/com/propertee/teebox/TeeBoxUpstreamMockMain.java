package com.propertee.teebox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeeBoxUpstreamMockMain {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    public static void main(String[] args) throws Exception {
        UpstreamConfig config = UpstreamConfig.fromSystemProperties();
        TeeBoxClient client = config.createClient();
        execute(client, config, System.out);
    }

    static void execute(TeeBoxClient client, UpstreamConfig config, PrintStream out) throws Exception {
        if (config.scriptFile != null) {
            if (isBlank(config.scriptId) || isBlank(config.version)) {
                throw new IllegalArgumentException("scriptId and version are required when scriptFile is provided");
            }
            Map<String, Object> registered = client.registerScript(
                config.scriptId,
                config.version,
                readText(config.scriptFile),
                config.description,
                config.labels,
                config.activate
            );
            out.println("REGISTERED " + registered.get("scriptId") + " active=" + registered.get("activeVersion"));
        } else if (config.activate) {
            if (isBlank(config.scriptId) || isBlank(config.version)) {
                throw new IllegalArgumentException("scriptId and version are required when activate=true");
            }
            Map<String, Object> activated = client.activateScript(config.scriptId, config.version);
            out.println("ACTIVATED " + activated.get("scriptId") + " active=" + activated.get("activeVersion"));
        }

        if (!config.submit) {
            out.println("SKIP submit");
            return;
        }

        Map<String, Object> submitted;
        if (!isBlank(config.scriptPath)) {
            submitted = client.submitRunByPath(config.scriptPath, config.props);
        } else if (!isBlank(config.scriptId)) {
            submitted = client.submitRun(config.scriptId, config.version, config.props);
        } else {
            throw new IllegalArgumentException("scriptPath or scriptId is required when submit=true");
        }

        String runId = stringValue(submitted.get("runId"));
        out.println("SUBMITTED " + runId);

        if (!config.waitForTerminal) {
            return;
        }

        Map<String, Object> status = client.waitForRunTerminal(runId, config.waitMs);
        out.println("STATUS " + status.get("status"));

        Map<String, Object> result = client.getRunResult(runId);
        out.println("RESULT " + GSON.toJson(result));

        Map<String, Object> taskSummary = client.getRunTaskSummary(runId);
        out.println("TASKS " + GSON.toJson(taskSummary));
    }

    private static String readText(File file) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    static class UpstreamConfig {
        String baseUrl;
        String apiToken;
        String clientApiToken;
        String publisherApiToken;
        String adminApiToken;
        String scriptPath;
        String scriptId;
        String version;
        File scriptFile;
        String description;
        List<String> labels = new ArrayList<String>();
        boolean activate;
        boolean submit = true;
        boolean waitForTerminal = true;
        long waitMs = 10000L;
        Map<String, Object> props = new LinkedHashMap<String, Object>();

        static UpstreamConfig fromSystemProperties() {
            UpstreamConfig config = new UpstreamConfig();
            config.baseUrl = required("propertee.teebox.upstream.baseUrl");
            config.apiToken = optional("propertee.teebox.upstream.apiToken");
            config.clientApiToken = firstNonBlank(optional("propertee.teebox.upstream.clientApiToken"), config.apiToken);
            config.publisherApiToken = firstNonBlank(optional("propertee.teebox.upstream.publisherApiToken"), config.apiToken);
            config.adminApiToken = firstNonBlank(optional("propertee.teebox.upstream.adminApiToken"), config.apiToken);
            config.scriptPath = optional("propertee.teebox.upstream.scriptPath");
            config.scriptId = optional("propertee.teebox.upstream.scriptId");
            config.version = optional("propertee.teebox.upstream.version");
            config.description = optional("propertee.teebox.upstream.description");
            String scriptFile = optional("propertee.teebox.upstream.scriptFile");
            if (!isBlank(scriptFile)) {
                config.scriptFile = new File(scriptFile).getAbsoluteFile();
            }
            config.labels = parseLabels(optional("propertee.teebox.upstream.labels"));
            config.activate = Boolean.parseBoolean(optionalWithDefault("propertee.teebox.upstream.activate", "false"));
            config.submit = Boolean.parseBoolean(optionalWithDefault("propertee.teebox.upstream.submit", "true"));
            config.waitForTerminal = Boolean.parseBoolean(optionalWithDefault("propertee.teebox.upstream.wait", "true"));
            config.waitMs = Long.parseLong(optionalWithDefault("propertee.teebox.upstream.waitMs", "10000"));
            config.props = parseProps(optional("propertee.teebox.upstream.propsJson"));
            return config;
        }

        TeeBoxClient createClient() {
            return new TeeBoxClient(baseUrl, clientApiToken, publisherApiToken, adminApiToken);
        }

        private static Map<String, Object> parseProps(String json) {
            if (isBlank(json)) {
                return new LinkedHashMap<String, Object>();
            }
            Map<String, Object> parsed = GSON.fromJson(json, MAP_TYPE);
            return parsed != null ? parsed : new LinkedHashMap<String, Object>();
        }

        private static List<String> parseLabels(String raw) {
            List<String> parsed = new ArrayList<String>();
            if (isBlank(raw)) {
                return parsed;
            }
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String item = parts[i] != null ? parts[i].trim() : null;
                if (!isBlank(item)) {
                    parsed.add(item);
                }
            }
            return parsed;
        }

        private static String required(String key) {
            String value = optional(key);
            if (isBlank(value)) {
                throw new IllegalArgumentException(key + " is required");
            }
            return value;
        }

        private static String optionalWithDefault(String key, String defaultValue) {
            String value = optional(key);
            return !isBlank(value) ? value : defaultValue;
        }

        private static String optional(String key) {
            String value = System.getProperty(key);
            return !isBlank(value) ? value.trim() : null;
        }

        private static String firstNonBlank(String primary, String fallback) {
            if (!isBlank(primary)) {
                return primary;
            }
            return !isBlank(fallback) ? fallback : null;
        }
    }
}
