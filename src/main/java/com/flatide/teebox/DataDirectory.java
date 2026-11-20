package com.flatide.teebox;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * One of the directories a server keeps under its data directory — {@code runs}, {@code tasks},
 * {@code script-registry}, {@code webhooks}, {@code users} — and the question its store puts to
 * it before relying on what it finds there: does it give up what is in it?
 *
 * <p>A directory that does not — closed to listing or to searching, or no directory at all — is
 * no bad file to be skipped: the start is refused. It used not to be noticed. Asked through
 * {@code java.io.File}, such a directory does not fail: {@code listFiles()} answers null, and
 * {@code isFile()}, {@code isDirectory()} and {@code exists()} answer false for everything in it,
 * which read as "nothing there". A server that started on that answer ran as if it had no runs,
 * no tasks, no scripts — or no users, and without a roster of users the admin UI asks nobody to
 * log in — with every file still on the disk. So the directory is asked by doing the things
 * themselves, the listing and a look, in the way that says why one fails — not by asking for its
 * permissions beforehand.
 */
public final class DataDirectory {

    /**
     * The name looked for to find out whether a directory can be searched. Nothing has to be
     * there under it; it is not followed if it is a link.
     */
    public static final String SEARCH_PROBE = ".teebox-search-probe";

    /** What the user the server runs as must be able to do with a directory that is listed, and with one that is not. */
    private static final String LISTED = "list and search it (r-x on a POSIX system)";
    private static final String SEARCHED = "search it (--x on a POSIX system)";

    private final File dir;
    private final String name;
    private final String consequence;

    /**
     * @param dir         the directory
     * @param name        what it is the directory of, as in "The <i>runs</i> directory"
     * @param consequence what a server that started without seeing into it would do: a sentence
     *                    to follow "refusing to start: "
     */
    public DataDirectory(File dir, String name, String consequence) {
        this.dir = dir;
        this.name = name;
        this.consequence = consequence;
    }

    /**
     * For a store that lists its directory: it is a directory, can be listed, and can be searched.
     *
     * @throws IllegalStateException when it is not: the start is refused
     */
    public void requireReadable() {
        requireDirectory(LISTED);
        try {
            // Opening the listing is where a directory that may not be listed says so.
            closeQuietly(Files.newDirectoryStream(dir.toPath()));
        } catch (IOException e) {
            throw refusal("cannot be listed (" + reason(e) + ")", LISTED, e);
        }
        requireSearch(LISTED);
    }

    /**
     * For a store that asks for its files by name and never lists them: it is a directory, and
     * can be searched.
     *
     * @throws IllegalStateException when it is not: the start is refused
     */
    public void requireSearchable() {
        requireDirectory(SEARCHED);
        requireSearch(SEARCHED);
    }

    /**
     * For a listing that {@code java.io} gave as null, with no word on why: the refusal to throw.
     * The directory is asked again in the way that says why, and that refusal is thrown when
     * there is one.
     */
    public IllegalStateException unlisted() {
        requireReadable();
        return refusal("gave no listing", LISTED, null);
    }

    private void requireDirectory(String needed) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(dir.toPath(), BasicFileAttributes.class);
        } catch (IOException e) {
            throw refusal("cannot be looked at (" + reason(e) + ")", needed, e);
        }
        if (!attributes.isDirectory()) {
            // Asked before a listing is opened: an open of what stands there instead may never
            // return (a FIFO waits for a writer).
            throw new IllegalStateException("The " + name + " directory " + dir.getAbsolutePath()
                    + " is not a directory — refusing to start: something else stands where the"
                    + " server keeps its " + name + ", and it could neither find what was there"
                    + " nor save anything.");
        }
    }

    private void requireSearch(String needed) {
        Path probe = dir.toPath().resolve(SEARCH_PROBE);
        try {
            // Looking at an entry needs the directory to be searchable; listing it does not. Any
            // name will do, there or not: "no such file" is an answer too, and one that only a
            // directory that can be searched gives. A link under that name is not followed: it
            // is the directory that is asked, not the place a name in it leads to.
            Files.readAttributes(probe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException searched) {
            // searched, and not found
        } catch (IOException e) {
            throw refusal("cannot be searched (" + reason(e) + " on looking for a file in it)", needed, e);
        }
    }

    private IllegalStateException refusal(String what, String needed, IOException cause) {
        return new IllegalStateException("The " + name + " directory " + dir.getAbsolutePath() + " " + what
                + " — refusing to start: " + consequence + " The user the server runs as must be able to "
                + needed + ", and to write to it to save anything.", cause);
    }

    /** Closes a listing that has given what it had to give; a close that fails takes none of it back. */
    private static void closeQuietly(DirectoryStream<Path> listing) {
        try {
            listing.close();
        } catch (IOException ignore) {
        }
    }

    /**
     * What went wrong, without the path it went wrong on: the message names the directory, and
     * the path of the look into it is that of a file nobody has to have.
     */
    private static String reason(IOException e) {
        if (e instanceof FileSystemException) {
            String reason = ((FileSystemException) e).getReason();
            return e.getClass().getName() + (reason != null ? ": " + reason : "");
        }
        return e.toString();
    }
}
