package com.flatide.teebox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Atomic file writes (temp file + rename) for the persistence layers. An in-place
 * {@code FileOutputStream} truncates first, so a crash or full disk mid-write destroys the
 * previous good content — for script sources that is a lost version, for task meta it is a lost
 * process-tracking record after restart. RunStore and UserStore carry their own equivalent logic;
 * this helper serves the remaining writers (ScriptRegistry, ManagedTaskEngine).
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Write {@code content} as UTF-8 to {@code file} atomically. Throws IOException on failure;
     *  the previous file content is preserved whenever the write does not complete. */
    static void write(File file, String content) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temp), "UTF-8");
            writer.write(content);
            writer.close();
            writer = null;
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }
}
