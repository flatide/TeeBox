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
import java.util.List;
import java.util.Map;

/**
 * Per-run file persistence ({@code dataDir/runs/<runId>.json}). Listing, counting, and filtering
 * are served by {@link RunRegistry} from its in-memory map — which holds every non-purged run —
 * so there is no on-disk run index; startup recovery scans the directory instead.
 */
public class RunStore {
    private final File runsDir;
    // The engine's first-class null (JsonNull.NULL) must persist as JSON null, not reflect into {} —
    // same boundary rule as the API Gson. See JsonNullGsonAdapter and parseRun for the load side.
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(com.flatide.propertee2.value.JsonNull.class, new JsonNullGsonAdapter())
            .create();

    private static final String LEGACY_INDEX_FILE = "index.json";

    public RunStore(File dataDir) {
        this.runsDir = new File(dataDir, "runs");
        if (!runsDir.exists() && !runsDir.mkdirs()) {
            throw new IllegalStateException("Failed to create runs directory: " + runsDir.getAbsolutePath());
        }
        deleteLegacyIndexFiles();
    }

    // Pre-1.14 TeeBox kept a runs/index.json rewritten on every state transition (O(all retained
    // runs) per write). Nothing reads it anymore; delete a leftover so a rollback to an older
    // version rebuilds a fresh index from the run files instead of trusting a stale one that would
    // hide (and never purge) runs written since. The delete must not fail silently: if the file
    // survived while this version kept writing runs, that rollback hazard becomes real — so after
    // retries, refuse to start (a file we cannot delete in runsDir means run writes are likely
    // broken too).
    private void deleteLegacyIndexFiles() {
        File index = new File(runsDir, LEGACY_INDEX_FILE);
        if (index.isFile()) {
            deleteInsistently(index);
            if (index.exists()) {
                throw new IllegalStateException("Failed to delete legacy run index "
                        + index.getAbsolutePath() + " — refusing to start: run listing no longer"
                        + " maintains this file, and a stale copy would make a rolled-back (pre-1.14)"
                        + " TeeBox hide runs written since. Remove the file manually.");
            }
            TeeBoxLog.info("RunStore", "Removed legacy run index (runs are listed from memory now): "
                    + index.getAbsolutePath());
        }
        // A leftover index.json.tmp is harmless to a rollback (an old version overwrites it before
        // renaming), so best-effort only.
        File indexTmp = new File(runsDir, LEGACY_INDEX_FILE + ".tmp");
        if (indexTmp.isFile()) {
            indexTmp.delete();
        }
    }

    // Same transient-hold reasoning as moveAtomically: an external scanner (antivirus, indexer)
    // may briefly hold the file on Windows, so retry with backoff (20/40/80/160ms between the 5
    // attempts, no sleep after the last) before giving up — the caller re-checks existence.
    private void deleteInsistently(File file) {
        long delayMs = MOVE_RETRY_BASE_DELAY_MS;
        for (int attempt = 1; ; attempt++) {
            if (file.delete() || !file.exists()) {
                return;
            }
            if (attempt >= MOVE_ATTEMPTS) {
                return;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            delayMs *= 2;
        }
    }

    public synchronized void save(RunInfo run) {
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
        RunInfo run;
        try {
            run = gson.fromJson(tree, RunInfo.class);
        } catch (RuntimeException wrongShape) {
            return null;   // valid JSON but not a RunInfo (e.g. "[]") — skipped like a corrupt file
        }
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
        normalizePublishedNumbers(run.published);
        return run;
    }

    /**
     * Gson's generic Object mapping reads every JSON number as Double, so a reloaded
     * published map would serve {@code key.detectedAt}/{@code key.count} as 1.7E12 / 3.0
     * after a restart. Captured values themselves are Strings; only the numeric metadata
     * keys need restoring — convert whole-number Doubles back to Long.
     */
    private static void normalizePublishedNumbers(Map<String, Object> published) {
        if (published == null) return;
        for (Map.Entry<String, Object> entry : published.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Double) {
                double d = (Double) value;
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    entry.setValue(Long.valueOf((long) d));
                }
            }
        }
    }

    /**
     * Load every persisted run by scanning the runs directory. One bad file must never block
     * startup recovery: corrupt, foreign (parseable JSON that is not a run), or unreadable files
     * are skipped with a warning. The JSON's {@code runId} must equal the filename: the runId is
     * reused as the write path (see {@link #fileFor}), so a crafted file ({@code "runId":
     * "../outside"}) could otherwise make startup recovery write outside the runs directory, and
     * a duplicate runId in a second file could silently shadow the real run.
     */
    public synchronized List<RunInfo> loadAll() {
        File[] files = runsDir.listFiles();
        List<RunInfo> runs = new ArrayList<RunInfo>();
        if (files == null) {
            return runs;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")
                    || LEGACY_INDEX_FILE.equals(file.getName())) {
                continue;
            }
            String fileRunId = stripSuffix(file.getName(), ".json");
            RunInfo run = load(fileRunId);
            if (run == null) {
                TeeBoxLog.warn("RunStore", "Skipping run file that is not a parseable run: "
                        + file.getAbsolutePath());
                continue;
            }
            if (!fileRunId.equals(run.runId)) {
                TeeBoxLog.warn("RunStore", "Skipping run file whose runId '" + run.runId
                        + "' does not match its filename: " + file.getAbsolutePath());
                continue;
            }
            runs.add(run);
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

    // On Windows an external scanner (antivirus real-time protection, the search indexer) briefly
    // holds freshly written files, and replacing a held file fails with a sharing violation
    // ("the process cannot access the file because it is being used by another process") — POSIX
    // renames over open files, so this never shows elsewhere. The hold is transient (typically
    // milliseconds), so retry with a short backoff before giving up: 5 attempts, 20/40/80/160 ms.
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_BASE_DELAY_MS = 20;

    private void moveAtomically(Path source, Path target) throws IOException {
        long delayMs = MOVE_RETRY_BASE_DELAY_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                moveOnce(source, target);
                if (attempt > 1) {
                    TeeBoxLog.warn("RunStore", "Move of " + target.getFileName()
                        + " succeeded after " + attempt + " attempts (file was transiently held)");
                }
                return;
            } catch (IOException e) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                delayMs *= 2;
            }
        }
    }

    private void moveOnce(Path source, Path target) throws IOException {
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
}
