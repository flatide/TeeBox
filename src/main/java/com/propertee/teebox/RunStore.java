package com.propertee.teebox;

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
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File indexFile;
    private final File indexTmpFile;

    public RunStore(File dataDir) {
        this.runsDir = new File(dataDir, "runs");
        if (!runsDir.exists()) {
            runsDir.mkdirs();
        }
        this.indexFile = new File(runsDir, "index.json");
        this.indexTmpFile = new File(runsDir, "index.json.tmp");
    }

    public synchronized void save(RunInfo run) {
        saveRunFile(run);
        updateIndex(run);
    }

    public synchronized void saveRunFile(RunInfo run) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(fileFor(run.runId)), "UTF-8");
            gson.toJson(run, writer);
        } catch (IOException e) {
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
            return gson.fromJson(json, RunInfo.class);
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
            throw new RuntimeException("Failed to write run index: " + e.getMessage(), e);
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
