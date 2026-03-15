package com.propertee.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.propertee.core.ScriptParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ScriptRegistry {
    private final File registryDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ScriptRegistry(File dataDir) {
        this.registryDir = new File(dataDir, "script-registry");
        if (!registryDir.exists()) {
            registryDir.mkdirs();
        }
    }

    public synchronized List<ScriptInfo> listScripts() {
        File[] dirs = registryDir.listFiles();
        List<ScriptInfo> scripts = new ArrayList<ScriptInfo>();
        if (dirs == null) {
            return scripts;
        }
        Arrays.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            ScriptInfo info = loadScript(dir.getName());
            if (info != null) {
                scripts.add(info);
            }
        }
        return scripts;
    }

    public synchronized ScriptInfo loadScript(String scriptId) {
        validateName("scriptId", scriptId);
        File metaFile = scriptMetaFile(scriptId);
        if (!metaFile.exists()) {
            return null;
        }
        InputStream input = null;
        try {
            input = new FileInputStream(metaFile);
            String json = readAll(input);
            ScriptInfo info = gson.fromJson(json, ScriptInfo.class);
            if (info == null) {
                return null;
            }
            markActiveVersion(info);
            sortVersions(info);
            return info;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    public synchronized ScriptInfo registerVersion(String scriptId,
                                                   String version,
                                                   String content,
                                                   String description,
                                                   List<String> labels,
                                                   boolean activate) {
        validateName("scriptId", scriptId);
        validateName("version", version);
        if (content == null || content.trim().length() == 0) {
            throw new IllegalArgumentException("content is required");
        }
        validateScript(content);

        ScriptInfo info = loadScript(scriptId);
        long now = System.currentTimeMillis();
        if (info == null) {
            info = new ScriptInfo();
            info.scriptId = scriptId;
            info.createdAt = now;
        }
        if (findVersion(info, version) != null) {
            throw new IllegalArgumentException("Script version already exists: " + scriptId + "@" + version);
        }

        File scriptDir = scriptDir(scriptId);
        File versionsDir = new File(scriptDir, "versions");
        if (!versionsDir.exists()) {
            versionsDir.mkdirs();
        }

        File scriptFile = new File(versionsDir, version + ".pt");
        writeFile(scriptFile, content);

        ScriptVersionInfo versionInfo = new ScriptVersionInfo();
        versionInfo.version = version;
        versionInfo.description = description != null ? description : "";
        versionInfo.labels = sanitizeLabels(labels);
        versionInfo.sha256 = sha256(content);
        versionInfo.createdAt = now;
        versionInfo.active = false;
        info.versions.add(versionInfo);
        if (activate || info.activeVersion == null || info.activeVersion.length() == 0) {
            info.activeVersion = version;
        }
        info.updatedAt = now;
        markActiveVersion(info);
        sortVersions(info);
        saveScript(info);
        return info.copy();
    }

    public synchronized ScriptInfo activateVersion(String scriptId, String version) {
        ScriptInfo info = requireScript(scriptId);
        if (findVersion(info, version) == null) {
            throw new IllegalArgumentException("Unknown script version: " + scriptId + "@" + version);
        }
        info.activeVersion = version;
        info.updatedAt = System.currentTimeMillis();
        markActiveVersion(info);
        sortVersions(info);
        saveScript(info);
        return info.copy();
    }

    public synchronized ResolvedScript resolve(String scriptId, String version) {
        ScriptInfo info = requireScript(scriptId);
        String resolvedVersion = version != null && version.trim().length() > 0 ? version.trim() : info.activeVersion;
        if (resolvedVersion == null || resolvedVersion.length() == 0) {
            throw new IllegalArgumentException("No active version for script: " + scriptId);
        }
        ScriptVersionInfo versionInfo = findVersion(info, resolvedVersion);
        if (versionInfo == null) {
            throw new IllegalArgumentException("Unknown script version: " + scriptId + "@" + resolvedVersion);
        }
        File scriptFile = scriptVersionFile(scriptId, resolvedVersion);
        if (!scriptFile.isFile()) {
            throw new IllegalArgumentException("Script content missing for " + scriptId + "@" + resolvedVersion);
        }
        ResolvedScript resolved = new ResolvedScript();
        resolved.scriptId = scriptId;
        resolved.version = resolvedVersion;
        resolved.file = scriptFile;
        resolved.displayPath = scriptId + "@" + resolvedVersion;
        return resolved;
    }

    private ScriptInfo requireScript(String scriptId) {
        validateName("scriptId", scriptId);
        ScriptInfo info = loadScript(scriptId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown scriptId: " + scriptId);
        }
        return info;
    }

    private void saveScript(ScriptInfo info) {
        Writer writer = null;
        try {
            File dir = scriptDir(info.scriptId);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            writer = new OutputStreamWriter(new FileOutputStream(scriptMetaFile(info.scriptId)), "UTF-8");
            gson.toJson(info, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save script metadata for " + info.scriptId + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(writer);
        }
    }

    private void validateScript(String content) {
        List<String> errors = new ArrayList<String>();
        if (ScriptParser.parse(content, errors) == null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append(errors.get(i));
            }
            throw new IllegalArgumentException(sb.toString());
        }
    }

    private void validateName(String fieldName, String value) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(fieldName + " contains unsupported characters: " + value);
        }
    }

    private List<String> sanitizeLabels(List<String> labels) {
        if (labels == null) {
            return new ArrayList<String>();
        }
        List<String> cleaned = new ArrayList<String>();
        for (String label : labels) {
            if (label == null) {
                continue;
            }
            String trimmed = label.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    private void markActiveVersion(ScriptInfo info) {
        if (info == null) {
            return;
        }
        for (ScriptVersionInfo version : info.versions) {
            version.active = info.activeVersion != null && info.activeVersion.equals(version.version);
        }
    }

    private void sortVersions(ScriptInfo info) {
        Collections.sort(info.versions, new Comparator<ScriptVersionInfo>() {
            @Override
            public int compare(ScriptVersionInfo a, ScriptVersionInfo b) {
                if (a.createdAt == b.createdAt) {
                    return a.version.compareTo(b.version);
                }
                return a.createdAt < b.createdAt ? 1 : -1;
            }
        });
    }

    private ScriptVersionInfo findVersion(ScriptInfo info, String version) {
        if (info == null || version == null) {
            return null;
        }
        for (ScriptVersionInfo item : info.versions) {
            if (version.equals(item.version)) {
                return item;
            }
        }
        return null;
    }

    private File scriptDir(String scriptId) {
        return new File(registryDir, scriptId);
    }

    private File scriptMetaFile(String scriptId) {
        return new File(scriptDir(scriptId), "script.json");
    }

    public synchronized String readVersionContent(String scriptId, String version) {
        File file = scriptVersionFile(scriptId, version);
        if (!file.isFile()) {
            return null;
        }
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            return readAll(input);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    private File scriptVersionFile(String scriptId, String version) {
        return new File(new File(scriptDir(scriptId), "versions"), version + ".pt");
    }

    private void writeFile(File file, String content) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + file.getAbsolutePath(), e);
        } finally {
            closeQuietly(writer);
        }
    }

    private String readAll(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len, "UTF-8"));
        }
        return sb.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                sb.append(String.format(Locale.ENGLISH, "%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to hash script", e);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignore) {
            }
        }
    }

    public static class ResolvedScript {
        public String scriptId;
        public String version;
        public String displayPath;
        public File file;
    }
}
