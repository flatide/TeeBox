package com.flatide.teebox;

import com.flatide.propertee2.value.TeeFormat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-run file persistence ({@code dataDir/runs/<runId>.json}). Listing, counting, and filtering
 * are served by {@link RunRegistry} from its in-memory map — which holds every non-purged run —
 * so there is no on-disk run index; startup recovery scans the directory instead.
 */
public class RunStore {
    private final File runsDir;
    private final DataDirectory directory;
    // The engine's first-class null (JsonNull.NULL) must persist as JSON null, not reflect into {} —
    // same boundary rule as the API Gson. See JsonNullGsonAdapter and parseRun for the load side.
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(com.flatide.propertee2.value.JsonNull.class, new JsonNullGsonAdapter())
            .create();

    private static final String LEGACY_INDEX_FILE = "index.json";

    /**
     * A run's file does not give what was asked of it: it is not there, the read failed, or what
     * it holds is not this run's record — cut short, another run's, or with something else than
     * an object where the input belongs. What was asked for is then unknown, which is not the
     * same as empty: a caller must neither show it as empty nor write over the file.
     */
    public static class RunFileUnreadableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RunFileUnreadableException(String runId, IOException cause) {
            super("Failed to read run " + runId + ": " + cause, cause);
        }

        public RunFileUnreadableException(String runId, String reason) {
            super("Failed to read run " + runId + ": " + reason);
        }
    }

    public RunStore(File dataDir) {
        this.runsDir = new File(dataDir, "runs");
        this.directory = new DataDirectory(runsDir, "runs", "the runs in it would be missing from"
                + " this server as if there were none, with their files still on the disk.");
        if (!runsDir.exists() && !runsDir.mkdirs()) {
            throw new IllegalStateException("Failed to create runs directory: " + runsDir.getAbsolutePath());
        }
        // Asked before the store touches anything in it: a start refused here has changed nothing
        // in this directory, not even the legacy index below.
        directory.requireReadable();
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
        } catch (RuntimeException e) {
            // Gson refusing a value (Infinity, NaN) fails mid-write; leave no partial file behind
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
                writer = null;
            }
            if (tmp.exists()) {
                tmp.delete();
            }
            throw e;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /** The run in a file; null when there is no such file, or it cannot be read or is not a run. */
    public RunInfo load(String runId) {
        String json;
        try {
            json = readFile(runId);
        } catch (RunFileUnreadableException e) {
            return null;
        }
        return json != null ? parseRun(json, true) : null;
    }

    /**
     * The input properties in a run's file. An archived run keeps its input in the file only — the
     * registry's in-memory record lets it go — so this is how a detail view, or a later save of
     * that run, gets it back. The values are engine-shaped by literal, as {@link #parseRun} reads
     * them.
     *
     * <p>An empty answer is what the run's own record says: its input is {@code {}}, or it has
     * none (a record written before runs kept their input). A file that does not give the input
     * is not answered with an empty one — the input is then unknown, and the two must be told
     * apart: a later save would write the empty one into the file, and a detail view would show
     * it as what the run was given.
     *
     * @throws RunFileUnreadableException when the file is not there, cannot be read, is not JSON,
     *         holds another run's record (the record's {@code runId} is not this run's), has
     *         something else than an object under {@code properties}, or has a number there that
     *         is out of range (see {@link #parseRun})
     */
    public Map<String, Object> loadProperties(String runId) {
        String json = readFile(runId);
        if (json == null) {
            throw new RunFileUnreadableException(runId, "the file is not there");
        }
        com.google.gson.JsonElement tree;
        try {
            tree = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            throw new RunFileUnreadableException(runId, "the file is not JSON");
        }
        if (!tree.isJsonObject()) {
            throw new RunFileUnreadableException(runId, "the file is not a run record");
        }
        com.google.gson.JsonObject record = tree.getAsJsonObject();
        // Whose record it is, read as loadAll reads it when it takes the file for this run: Gson
        // maps a number or a boolean under "runId" to its text. (A stricter reading here refused
        // the input of a run the start had accepted.)
        com.google.gson.JsonElement recordRunId = record.get("runId");
        if (recordRunId == null || !recordRunId.isJsonPrimitive()
                || !recordRunId.getAsString().equals(runId)) {
            throw new RunFileUnreadableException(runId, "the file holds the record of another run");
        }
        com.google.gson.JsonElement properties = record.get("properties");
        if (!isInput(properties)) {
            throw new RunFileUnreadableException(runId, "the input in the file is not an object");
        }
        if (ValueDepth.exceeds(properties)) {
            throw new RunFileUnreadableException(runId, "the input in the file is " + ValueDepth.tooDeep());
        }
        Map<String, Object> input = inputFrom(properties);
        TeeFormat.NonFinite outOfRange = TeeFormat.firstNonFinite(input, "properties");
        if (outOfRange != null) {
            throw new RunFileUnreadableException(runId, "the input in the file has a number out of range ("
                    + outOfRange.path() + ")");
        }
        return input;
    }

    /**
     * The text of a run's file; null when there is no such file. Decoded once, from all the
     * bytes: decoding each 4 KB chunk on its own broke every multi-byte character (Korean, for
     * one) that straddled a chunk boundary into U+FFFD.
     *
     * <p>Only the read holds the store's lock — so that the store never has a file open while it
     * replaces it, which Windows refuses. Decoding and parsing happen outside it: a detail view of
     * a large archived run must not keep every save waiting.
     */
    private String readFile(String runId) {
        byte[] bytes;
        synchronized (this) {
            try {
                bytes = Files.readAllBytes(fileFor(runId).toPath());
            } catch (java.nio.file.NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                throw new RunFileUnreadableException(runId, e);
            }
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Deserialize a run file, reconstructing {@code resultData} and the input {@code properties}
     * as the engine-shaped value trees they were saved from. Gson's generic {@code Object} mapping
     * corrupts both on reload: JSON {@code null} becomes a Java null (so the engine's first-class
     * {@code null} vanishes when the run is served again) and every number becomes a {@code Double}
     * ({@code "n": 1} would be served as {@code 1.0} after a restart, and a debug re-run would be
     * given {@code 1.0} where the run itself had {@code 1}). Re-parsing those subtrees with the
     * engine's own {@code JsonParser} restores exactly the shapes the engine produced —
     * {@code Integer} vs {@code Double} by literal, {@code JsonNull.NULL},
     * {@code LinkedHashMap}/{@code ArrayList} — so a run reads back identical across restarts. A
     * run file with no {@code resultData} key is a value-less legacy run (Gson omits Java-null
     * fields on write) and stays null.
     *
     * @param withArchivedInput false leaves the input of an archived run where it is, in the file:
     *                          the run then comes with empty {@code properties}. (The input is in
     *                          the parsed text of this one file for as long as this call lasts; it
     *                          is not turned into values and not kept.)
     */
    private RunInfo parseRun(String json, boolean withArchivedInput) {
        com.google.gson.JsonElement tree;
        try {
            tree = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            return null;   // a corrupt run file is skipped (callers already handle a null load)
        }
        // Taken out before Gson maps the rest: its generic mapping would build the whole input
        // once more, in the wrong shapes, only for it to be replaced (or let go) below.
        com.google.gson.JsonElement properties = tree.isJsonObject()
                ? tree.getAsJsonObject().remove("properties") : null;
        if (!isInput(properties)) {
            // Something else than an object where the input belongs: the file is no run record.
            // (Gson's mapping below refuses the same of the other fields — a list where a text
            // belongs, an object where a number does.) Taken for a run without input instead, it
            // was written over as one by the first save: at a start, for every run the server
            // had stopped on.
            return null;
        }
        if (ValueDepth.exceeds(properties) || (tree.isJsonObject()
                && (ValueDepth.exceeds(tree.getAsJsonObject().get("resultData"))
                    || ValueDepth.exceeds(tree.getAsJsonObject().get("published"))))) {
            // An input or a result nested deeper than a run's values may be (see ValueDepth): no
            // run of this server ends with one or is given one. Not a run record; looked at
            // before the mapping below, which goes down one call per level.
            return null;
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
        // A null where a list belongs is a list of nothing. Gson puts the null into the field,
        // and the record then failed wherever it was copied — at a start, in the save of a run
        // the server had stopped on, which kept the server from starting.
        if (run.imports == null) {
            run.imports = new ArrayList<ResolvedModuleInfo>();
        }
        if (run.threads == null) {
            run.threads = new ArrayList<RunThreadInfo>();
        }
        if (run.stdoutLines == null) {
            run.stdoutLines = new ArrayList<String>();
        }
        if (run.stderrLines == null) {
            run.stderrLines = new ArrayList<String>();
        }
        if (withArchivedInput || !run.archived) {
            run.properties = inputFrom(properties);
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
        if (TeeFormat.firstNonFinite(run.properties, "properties") != null
                || TeeFormat.firstNonFinite(run.resultData, "resultData") != null
                || TeeFormat.firstNonFinite(run.published, "published") != null) {
            // A number past the double range: a literal such as 1e999 reads as Infinity, which no
            // JSON writer takes back. Such a record can be neither saved again nor sent in an
            // answer — and a start saves every run it finds unfinished, so one file like that
            // kept the server from starting. No run writes one (a result or an input with such a
            // number is refused before it is saved); the file is not a run record.
            return null;
        }
        return run;
    }

    /**
     * Whether what a run file has under {@code "properties"} can be a run's input: an object, or
     * no value at all — the field left out, or JSON's {@code null} in its place.
     */
    private static boolean isInput(com.google.gson.JsonElement properties) {
        return properties == null || properties.isJsonNull() || properties.isJsonObject();
    }

    /**
     * A run's input from the {@code "properties"} of its file, engine-shaped; empty when the
     * record has none. The caller has checked {@link #isInput}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> inputFrom(com.google.gson.JsonElement properties) {
        if (properties == null || !properties.isJsonObject()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = com.flatide.propertee2.value.JsonParser.parse(properties.toString());
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (RuntimeException e) {
            // not engine-readable: keep Gson's best-effort shape rather than dropping the input
        }
        Map<String, Object> bestEffort = gson.fromJson(properties,
                new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        return bestEffort != null ? bestEffort : new LinkedHashMap<String, Object>();
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
     *
     * <p>The directory is another matter than a file in it. When it cannot be listed, or cannot
     * be searched — and then no file in it is found to be a file — the scan has not all the runs
     * to give, and does not hand over what it has as if it were all: it is refused, and with it
     * the start (see {@link DataDirectory}).
     *
     * <p>An archived run comes without its input ({@code properties} empty): that stays in its
     * file, where {@link #loadProperties} finds it. Each file is read, parsed and let go before
     * the next, so a start holds one run's archived input at a time, and only while that file is
     * parsed — collecting the runs first and dropping those inputs afterwards needed the heap for
     * all of them at once, and with enough archived input on disk the server could not start.
     *
     * @throws IllegalStateException when the runs directory is no directory, cannot be listed, or
     *         cannot be searched
     */
    public synchronized List<RunInfo> loadAll() {
        File[] files = runsDir.listFiles();
        if (files == null) {
            throw directory.unlisted();
        }
        List<RunInfo> runs = new ArrayList<RunInfo>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")
                    || LEGACY_INDEX_FILE.equals(file.getName())) {
                continue;
            }
            String fileRunId = stripSuffix(file.getName(), ".json");
            String json;
            try {
                json = readFile(fileRunId);
            } catch (RunFileUnreadableException e) {
                TeeBoxLog.warn("RunStore", "Skipping run file that cannot be read: "
                        + file.getAbsolutePath() + " (" + e.getCause() + ")");
                continue;
            }
            RunInfo run = json != null ? parseRun(json, false) : null;
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
        // A directory that cannot be searched answers every look above with "no file", and what
        // was found is then not all there is. Asked now that the looks are done, so that this
        // also tells of a directory that was closed while the scan went.
        directory.requireReadable();
        return runs;
    }

    /**
     * Deletes a run's file; a file that is not there is deleted already.
     *
     * <p>"Not there" is what the delete itself finds, not what a look beforehand says: a file
     * that cannot be looked at — its directory closed to searching — answers such a look with "no
     * such file", and was then counted as deleted while it stayed on the disk.
     *
     * @throws RuntimeException when the file is there, or may be, and could not be deleted
     */
    public synchronized void delete(String runId) {
        if (runId == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(runId).toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete run " + runId + ": " + e, e);
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
}
