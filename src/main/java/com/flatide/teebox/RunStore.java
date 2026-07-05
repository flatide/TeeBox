package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class RunStore {
    private final File runsDir;
    // The engine's first-class null (JsonNull.NULL) must persist as JSON null, not reflect into {} —
    // same boundary rule as the API Gson. See JsonNullGsonAdapter and parseRun for the load side.
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(com.flatide.propertee2.value.JsonNull.class, new JsonNullGsonAdapter())
            .create();
    private final File indexFile;
    private final File indexTmpFile;

    public RunStore(File dataDir) {
        this.runsDir = new File(dataDir, "runs");
        if (!runsDir.exists() && !runsDir.mkdirs()) {
            throw new IllegalStateException("Failed to create runs directory: " + runsDir.getAbsolutePath());
        }
        this.indexFile = new File(runsDir, "index.json");
        this.indexTmpFile = new File(runsDir, "index.json.tmp");
    }

    public synchronized void save(RunInfo run) {
        saveRunFile(run);
        updateIndex(run);
    }

    public synchronized void saveRunFile(RunInfo run) {
        File target = fileFor(run.runId);
        File tmp = new File(runsDir, run.runId + ".json.tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            gson.toJson(run, writer);
            writer.close();
            writer = null;
            moveAtomically(tmp.toPath(), target.toPath());
        } catch (IOException e) {
            if (tmp.exists()) {
                tmp.delete();
            }
            throw new RuntimeException("Failed to save run " + run.runId + ": " + e.getMessage(), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public synchronized RunInfo load(String runId) {
        File file = fileFor(runId);
        if (!file.exists()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            String json = readAll(fis);
            return parseRun(json);
        } catch (IOException e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Deserialize a run file, reconstructing {@code resultData} as the engine-shaped value tree it was
     * saved from. Gson's generic {@code Object} mapping corrupts the result on reload: JSON {@code null}
     * becomes a Java null (so the engine's first-class {@code null} vanishes when the run is served
     * again) and every number becomes a {@code Double} ({@code "n": 1} would be served as {@code 1.0}
     * after a restart). Re-parsing the {@code resultData} subtree with the engine's own
     * {@code JsonParser} restores exactly the shapes the engine produced — {@code Integer} vs
     * {@code Double} by literal, {@code JsonNull.NULL}, {@code LinkedHashMap}/{@code ArrayList} — so a
     * run's result reads back identical across restarts. A run file with no {@code resultData} key is a
     * value-less legacy run (Gson omits Java-null fields on write) and stays null.
     */
    private RunInfo parseRun(String json) {
        com.google.gson.JsonElement tree;
        try {
            tree = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            return null;   // a corrupt run file is skipped (callers already handle a null load)
        }
        RunInfo run = gson.fromJson(tree, RunInfo.class);
        if (run == null || !tree.isJsonObject()) {
            return run;
        }
        com.google.gson.JsonElement resultData = tree.getAsJsonObject().get("resultData");
        if (resultData != null) {
            try {
                // A present "resultData": null is the engine's first-class null (the adapter wrote it).
                run.resultData = com.flatide.propertee2.value.JsonParser.parse(resultData.toString());
            } catch (RuntimeException e) {
                // keep Gson's best-effort shape rather than dropping the whole run
            }
        }
        return run;
    }

    public synchronized int count(String status) {
        List<RunIndexEntry> entries = loadIndexEntries();
        if (status == null) {
            return entries.size();
        }
        int count = 0;
        for (RunIndexEntry entry : entries) {
            if (status.equalsIgnoreCase(entry.status)) {
                count++;
            }
        }
        return count;
    }

    public synchronized List<RunInfo> loadAll() {
        return query(null, 0, -1);
    }

    public synchronized List<RunInfo> query(String status, int offset, int limit) {
        return query(status, null, offset, limit);
    }

    public synchronized List<RunInfo> query(String status, String scriptId, int offset, int limit) {
        List<RunIndexEntry> entries = loadIndexEntries();
        List<RunInfo> runs = new ArrayList<RunInfo>();
        int safeOffset = offset < 0 ? 0 : offset;
        int matched = 0;
        for (RunIndexEntry entry : entries) {
            if (status != null && !status.equalsIgnoreCase(entry.status)) {
                continue;
            }
            if (scriptId != null && !scriptId.equals(entry.scriptId)) {
                continue;
            }
            if (matched++ < safeOffset) {
                continue;
            }
            if (limit > 0 && runs.size() >= limit) {
                break;
            }
            RunInfo run = load(entry.runId);
            if (run != null) {
                runs.add(run);
            }
        }
        return runs;
    }

    public synchronized void delete(String runId) {
        if (runId == null) {
            return;
        }
        File file = fileFor(runId);
        if (file.exists() && !file.delete() && file.exists()) {
            throw new RuntimeException("Failed to delete run " + runId);
        }
        removeIndex(runId);
    }

    private File fileFor(String runId) {
        return new File(runsDir, runId + ".json");
    }

    private String stripSuffix(String value, String suffix) {
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private void updateIndex(RunInfo run) {
        if (run == null || run.runId == null) {
            return;
        }
        List<RunIndexEntry> entries = loadIndexEntries();
        RunIndexEntry updated = RunIndexEntry.fromRun(run);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).runId.equals(run.runId)) {
                entries.set(i, updated);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(updated);
        }
        sortIndexEntries(entries);
        writeIndex(entries);
    }

    private void removeIndex(String runId) {
        List<RunIndexEntry> entries = loadIndexEntries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).runId.equals(runId)) {
                entries.remove(i);
            }
        }
        writeIndex(entries);
    }

    private List<RunIndexEntry> loadIndexEntries() {
        if (!indexFile.exists()) {
            return rebuildIndex();
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(indexFile);
            String json = readAll(fis);
            RunIndexEntry[] parsed = gson.fromJson(json, RunIndexEntry[].class);
            if (parsed == null) {
                return rebuildIndex();
            }
            List<RunIndexEntry> entries = new ArrayList<RunIndexEntry>(Arrays.asList(parsed));
            sortIndexEntries(entries);
            return entries;
        } catch (Exception e) {
            return rebuildIndex();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private List<RunIndexEntry> rebuildIndex() {
        File[] files = runsDir.listFiles();
        List<RunIndexEntry> entries = new ArrayList<RunIndexEntry>();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".json") || "index.json".equals(file.getName())) {
                    continue;
                }
                RunInfo run = load(stripSuffix(file.getName(), ".json"));
                if (run != null) {
                    entries.add(RunIndexEntry.fromRun(run));
                }
            }
        }
        sortIndexEntries(entries);
        writeIndex(entries);
        return entries;
    }

    private void sortIndexEntries(List<RunIndexEntry> entries) {
        java.util.Collections.sort(entries, new Comparator<RunIndexEntry>() {
            @Override
            public int compare(RunIndexEntry a, RunIndexEntry b) {
                if (a.createdAt == b.createdAt) {
                    return a.runId.compareTo(b.runId);
                }
                return a.createdAt < b.createdAt ? 1 : -1;
            }
        });
    }

    private void writeIndex(List<RunIndexEntry> entries) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(indexTmpFile), "UTF-8");
            gson.toJson(entries, writer);
            writer.close();
            writer = null;
            moveAtomically(indexTmpFile.toPath(), indexFile.toPath());
        } catch (IOException e) {
            TeeBoxLog.error("RunStore", "Failed to write run index", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static class RunIndexEntry {
        String runId;
        String status;
        boolean archived;
        long createdAt;
        Long endedAt;
        String scriptPath;
        String scriptId;

        static RunIndexEntry fromRun(RunInfo run) {
            RunIndexEntry entry = new RunIndexEntry();
            entry.runId = run.runId;
            entry.status = run.status != null ? run.status.name() : null;
            entry.archived = run.archived;
            entry.createdAt = run.createdAt;
            entry.endedAt = run.endedAt;
            entry.scriptPath = run.scriptPath;
            entry.scriptId = run.scriptId;
            return entry;
        }
    }
}
