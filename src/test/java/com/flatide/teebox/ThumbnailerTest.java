package com.flatide.teebox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;

public class ThumbnailerTest {

    private File makePng(File dir, String name, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
        File f = new File(dir, name);
        ImageIO.write(img, "png", f);
        return f;
    }

    @Test
    public void scalesToFitPreservingAspectRatio() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File src = makePng(dir, "src.png", 100, 50);
        File dest = new File(dir, "out/thumb.png");

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 20));

        assertEquals(20, r.get("width"));      // 100x50 bounded by 20 -> 20x10 (aspect preserved)
        assertEquals(10, r.get("height"));
        assertTrue(dest.isFile());             // parent dir auto-created
        BufferedImage out = ImageIO.read(dest);
        assertEquals(20, out.getWidth());
        assertEquals(10, out.getHeight());
    }

    @Test
    public void separateMaxWidthAndHeightUseTighterBound() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File src = makePng(dir, "src.png", 100, 100);
        File dest = new File(dir, "thumb.png");

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 80, 40));

        assertEquals(40, r.get("width"));      // square bounded by 80x40 -> 40x40 (height is the tighter bound)
        assertEquals(40, r.get("height"));
    }

    @Test
    public void neverUpscalesASmallImage() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File src = makePng(dir, "src.png", 10, 10);
        File dest = new File(dir, "thumb.png");

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 100));

        assertEquals(10, r.get("width"));
        assertEquals(10, r.get("height"));
    }

    @Test
    public void errorsOnNonImageInput() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File notImage = new File(dir, "notes.txt");
        Files.write(notImage.toPath(), "hello".getBytes("UTF-8"));
        try {
            Thumbnailer.create(Arrays.asList(notImage.getPath(), new File(dir, "t.png").getPath(), 20));
            fail("expected a RuntimeException for a non-image input");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a readable image"));
        }
    }

    @Test
    public void errorsOnMissingArguments() {
        try {
            Thumbnailer.create(Arrays.asList("only-src.png"));
            fail("expected a RuntimeException for too few arguments");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("requires"));
        }
    }
}
