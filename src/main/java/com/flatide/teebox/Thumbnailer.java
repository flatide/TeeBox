package com.flatide.teebox;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Host implementation of the ProperTee {@code THUMBNAIL(srcPath, destPath, maxWidth, [maxHeight])} builtin
 * (registered as a BLOCKING host function in {@link ScriptExecutor}, so this runs off the engine's
 * cooperative baton). Reads any image ImageIO supports (PNG/JPEG/...), scales it to fit within
 * {@code maxWidth x maxHeight} preserving aspect ratio (never upscaling), and writes a PNG.
 *
 * <p>Returns {@code {path, width, height}} on success (the engine wraps it in {@code Result.ok}); any
 * thrown {@link RuntimeException} becomes a script-level {@code Result.error(message)}.
 */
final class Thumbnailer {

    private Thumbnailer() {}

    static Map<String, Object> create(List<Object> args, StreamResultSupport policy) {
        if (args.size() < 3) {
            throw new RuntimeException("THUMBNAIL() requires (srcPath, destPath, maxWidth, [maxHeight])");
        }
        if (!(args.get(0) instanceof String)) throw new RuntimeException("THUMBNAIL() srcPath must be a string");
        if (!(args.get(1) instanceof String)) throw new RuntimeException("THUMBNAIL() destPath must be a string");
        String srcPath = (String) args.get(0);
        String destPath = (String) args.get(1);
        int maxW = intArg(args.get(2), "maxWidth");
        int maxH = args.size() > 3 ? intArg(args.get(3), "maxHeight") : maxW;
        if (maxW < 1 || maxH < 1) throw new RuntimeException("THUMBNAIL() maxWidth/maxHeight must be >= 1");

        // Path policy: src and dest must canonicalize within the configured allowed roots (the same
        // filesystem boundary STREAM_FILE enforces). src must be an existing file; dest may not exist yet.
        File srcFile = policy.requireWithinRoots(srcPath, "THUMBNAIL", true);
        File destFile = policy.requireWithinRoots(destPath, "THUMBNAIL", false);

        BufferedImage src;
        try {
            src = ImageIO.read(srcFile);
        } catch (IOException e) {
            throw new RuntimeException("THUMBNAIL() cannot read " + srcPath + ": " + e.getMessage());
        }
        if (src == null) throw new RuntimeException("THUMBNAIL() not a readable image: " + srcPath);

        int sw = src.getWidth();
        int sh = src.getHeight();
        double scale = Math.min((double) maxW / sw, (double) maxH / sh);
        if (scale > 1.0) scale = 1.0;                       // never upscale
        int tw = Math.max(1, (int) Math.round(sw * scale));
        int th = Math.max(1, (int) Math.round(sh * scale));

        BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }

        File parent = destFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            if (!ImageIO.write(thumb, "png", destFile)) {
                throw new RuntimeException("THUMBNAIL() no PNG writer available");
            }
        } catch (IOException e) {
            throw new RuntimeException("THUMBNAIL() cannot write " + destPath + ": " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("path", destFile.getAbsolutePath());
        result.put("width", tw);
        result.put("height", th);
        return result;
    }

    private static int intArg(Object value, String name) {
        if (value instanceof Number) return ((Number) value).intValue();
        throw new RuntimeException("THUMBNAIL() " + name + " must be a number");
    }
}
