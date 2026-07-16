package com.flatide.teebox;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class TeeBoxConfig {
    public String bindAddress = "127.0.0.1";
    public int port = 18080;
    public File dataDir;
    public int maxConcurrentRuns = 64;
    public String apiToken;
    public String clientApiToken;
    public String publisherApiToken;
    public String adminApiToken;
    public String adminUser;
    public String adminPassword;
    /** Allowed roots for STREAM_FILE results (File.pathSeparator list). Empty ⇒ default to [dataDir]. */
    public String streamRoots;
    /** Enable the run-terminal webhook delivery subsystem (opt-in). */
    public boolean webhookEnabled = false;
    /** Comma-separated host[:port] allowlist for webhook callback URLs (required when enabled). */
    public String webhookUrlAllowlist;
    /** Per-POST connect/read timeout (ms) for webhook delivery. */
    public int webhookTimeoutMs = 10000;
    /** Server-default run execution timeout (ms, measured from RUNNING; duration syntax accepted,
     *  e.g. "30m"). 0 = off. A per-run {@code timeoutMs} request field overrides it. */
    public long runTimeoutMs = 0;
    /** Retained script-output ring size per run (stdout and stderr each). 0 = default (200). */
    public int runOutputMaxLines = 0;
    public static TeeBoxConfig fromArgs(String[] args) {
        File configFile = resolveConfigFile(args);
        Properties fileProps = loadProperties(configFile);
        return fromSources(fileProps);
    }

    public static TeeBoxConfig fromSystemProperties() {
        return fromSources(new Properties());
    }

    private static TeeBoxConfig fromSources(Properties fileProps) {
        TeeBoxConfig config = new TeeBoxConfig();
        String bind = getSetting("bind", fileProps);
        if (bind != null && bind.trim().length() > 0) {
            config.bindAddress = bind.trim();
        }
        String port = getSetting("port", fileProps);
        if (port != null && port.trim().length() > 0) {
            config.port = Integer.parseInt(port.trim());
        }
        String dataDir = getSetting("dataDir", fileProps);
        if (dataDir == null || dataDir.trim().length() == 0) {
            throw new IllegalArgumentException("TeeBox setting propertee.teebox.dataDir is required");
        }
        String maxRuns = getSetting("maxRuns", fileProps);
        if (maxRuns != null && maxRuns.trim().length() > 0) {
            config.maxConcurrentRuns = Integer.parseInt(maxRuns.trim());
        }
        String apiToken = getSetting("apiToken", fileProps);
        if (apiToken != null && apiToken.trim().length() > 0) {
            config.apiToken = apiToken.trim();
        }
        String clientApiToken = getSetting("clientApiToken", fileProps);
        if (clientApiToken != null && clientApiToken.trim().length() > 0) {
            config.clientApiToken = clientApiToken.trim();
        }
        String publisherApiToken = getSetting("publisherApiToken", fileProps);
        if (publisherApiToken != null && publisherApiToken.trim().length() > 0) {
            config.publisherApiToken = publisherApiToken.trim();
        }
        String adminApiToken = getSetting("adminApiToken", fileProps);
        if (adminApiToken != null && adminApiToken.trim().length() > 0) {
            config.adminApiToken = adminApiToken.trim();
        }
        config.dataDir = canonicalFile(new File(dataDir.trim()));
        String adminUser = getSetting("adminUser", fileProps);
        if (adminUser != null && adminUser.trim().length() > 0) {
            config.adminUser = adminUser.trim();
        }
        String adminPassword = getSetting("adminPassword", fileProps);
        if (adminPassword != null && adminPassword.trim().length() > 0) {
            config.adminPassword = adminPassword.trim();
        }
        String streamRoots = getSetting("streamRoots", fileProps);
        if (streamRoots != null && streamRoots.trim().length() > 0) {
            config.streamRoots = streamRoots.trim();
        }
        String webhookEnabled = getSetting("webhookEnabled", fileProps);
        if (webhookEnabled != null) {
            config.webhookEnabled = Boolean.parseBoolean(webhookEnabled.trim());
        }
        String webhookUrlAllowlist = getSetting("webhookUrlAllowlist", fileProps);
        if (webhookUrlAllowlist != null && webhookUrlAllowlist.trim().length() > 0) {
            config.webhookUrlAllowlist = webhookUrlAllowlist.trim();
        }
        String webhookTimeoutMs = getSetting("webhookTimeoutMs", fileProps);
        if (webhookTimeoutMs != null && webhookTimeoutMs.trim().length() > 0) {
            config.webhookTimeoutMs = Integer.parseInt(webhookTimeoutMs.trim());
        }
        String runTimeoutMs = getSetting("runTimeoutMs", fileProps);
        if (runTimeoutMs != null && runTimeoutMs.trim().length() > 0) {
            config.runTimeoutMs = DurationParser.parseMillis(runTimeoutMs.trim());
        }
        String runOutputMaxLines = getSetting("runOutputMaxLines", fileProps);
        if (runOutputMaxLines != null && runOutputMaxLines.trim().length() > 0) {
            config.runOutputMaxLines = Integer.parseInt(runOutputMaxLines.trim());
        }
        return config;
    }

    public String tokenForClientApi() {
        return firstNonBlank(clientApiToken, apiToken);
    }

    public String tokenForPublisherApi() {
        return firstNonBlank(publisherApiToken, apiToken);
    }

    public String tokenForAdminApi() {
        return firstNonBlank(adminApiToken, apiToken);
    }

    private static String getSetting(String suffix, Properties fileProps) {
        String sysValue = System.getProperty("propertee.teebox." + suffix);
        if (sysValue != null && sysValue.trim().length() > 0) {
            return sysValue;
        }
        String fileValue = fileProps.getProperty("propertee.teebox." + suffix);
        if (fileValue != null && fileValue.trim().length() > 0) {
            return fileValue;
        }
        return null;
    }

    private static File resolveConfigFile(String[] args) {
        String configured = System.getProperty("propertee.teebox.config");
        if (configured != null && configured.trim().length() > 0) {
            return canonicalFile(new File(configured.trim()));
        }
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) || "-c".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " requires a file path");
                }
                return canonicalFile(new File(args[i + 1]));
            }
            if (arg != null && arg.startsWith("--config=")) {
                return canonicalFile(new File(arg.substring("--config=".length())));
            }
        }
        return null;
    }

    private static Properties loadProperties(File file) {
        Properties props = new Properties();
        if (file == null) {
            return props;
        }
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("TeeBox server config file not found: " + file.getPath());
        }
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            props.load(input);
            return props;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read TeeBox server config: " + file.getPath(), e);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private static File canonicalFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + file.getPath(), e);
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && primary.trim().length() > 0) {
            return primary.trim();
        }
        if (fallback != null && fallback.trim().length() > 0) {
            return fallback.trim();
        }
        return null;
    }
}
