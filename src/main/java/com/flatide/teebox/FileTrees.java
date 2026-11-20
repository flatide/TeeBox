package com.flatide.teebox;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Deleting a directory with what is in it — and nothing else.
 *
 * <p>The purges went down a tree by asking {@code File.isDirectory()}, which is true of a link
 * to a directory as well: they went through the link and deleted what was on the other side of
 * it, outside what the server keeps. Here a link is a name like any file's: it is removed, and
 * what it points to is left alone.
 */
final class FileTrees {

    private FileTrees() {
    }

    /** Best effort: deletes a file, a link, or a directory and everything in it; nothing through a link. */
    static void delete(File root) {
        if (root != null) {
            delete(root.toPath());
        }
    }

    private static void delete(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return;   // not there, or not to be looked at
        }
        if (attributes.isDirectory()) {   // itself a directory: a link to one is not, asked this way
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    delete(entry);
                }
            } catch (IOException e) {
                // what could not be listed is left
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // left where it is
        }
    }
}
