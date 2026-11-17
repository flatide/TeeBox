package com.flatide.teebox;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.Map;

/**
 * Run in a JVM of its own by {@link ThumbnailHeadlessTest}: whether a JVM draws headless is decided
 * once, at its first use of java.awt, so the question needs a JVM in which THUMBNAIL is that first use.
 * Nothing here may touch java.awt before the THUMBNAIL call.
 */
public final class ThumbnailHeadlessProbe {

    private ThumbnailHeadlessProbe() {}

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File dest = new File(args[1]);
        System.out.println("before=" + System.getProperty("java.awt.headless"));
        Map<String, Object> made = Thumbnailer.create(
            Arrays.<Object>asList(src.getPath(), dest.getPath(), 20),
            new StreamResultSupport(Arrays.asList(src.getParentFile())));
        System.out.println("width=" + made.get("width"));
        System.out.println("property=" + System.getProperty("java.awt.headless"));
        System.out.println("headless=" + GraphicsEnvironment.isHeadless());
    }
}
