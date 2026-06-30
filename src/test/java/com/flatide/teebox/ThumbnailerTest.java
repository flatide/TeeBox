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

    /** Allowed-roots policy with {@code root} as the only permitted root. */
    private StreamResultSupport policy(File root) {
        return new StreamResultSupport(Arrays.asList(root));
    }

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

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 20), policy(dir));

        assertEquals(20, r.get("width"));      // 100x50 bounded by 20 -> 20x10 (aspect preserved)
        assertEquals(10, r.get("height"));
        assertTrue(dest.isFile());             // parent dir auto-created (within the root)
        BufferedImage out = ImageIO.read(dest);
        assertEquals(20, out.getWidth());
        assertEquals(10, out.getHeight());
    }

    @Test
    public void separateMaxWidthAndHeightUseTighterBound() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File src = makePng(dir, "src.png", 100, 100);
        File dest = new File(dir, "thumb.png");

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 80, 40), policy(dir));

        assertEquals(40, r.get("width"));      // square bounded by 80x40 -> 40x40 (height is the tighter bound)
        assertEquals(40, r.get("height"));
    }

    @Test
    public void neverUpscalesASmallImage() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File src = makePng(dir, "src.png", 10, 10);
        File dest = new File(dir, "thumb.png");

        Map<String, Object> r = Thumbnailer.create(Arrays.asList(src.getPath(), dest.getPath(), 100), policy(dir));

        assertEquals(10, r.get("width"));
        assertEquals(10, r.get("height"));
    }

    @Test
    public void errorsOnNonImageInput() throws Exception {
        File dir = Files.createTempDirectory("thumb").toFile();
        File notImage = new File(dir, "notes.txt");
        Files.write(notImage.toPath(), "hello".getBytes("UTF-8"));
        try {
            Thumbnailer.create(Arrays.asList(notImage.getPath(), new File(dir, "t.png").getPath(), 20), policy(dir));
            fail("expected a RuntimeException for a non-image input");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a readable image"));
        }
    }

    @Test
    public void errorsOnMissingArguments() {
        try {
            Thumbnailer.create(Arrays.asList((Object) "only-src.png"), policy(new File(".")));
            fail("expected a RuntimeException for too few arguments");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("requires"));
        }
    }

    @Test
    public void rejectsSrcOutsideAllowedRoots() throws Exception {
        File root = Files.createTempDirectory("thumb-root").toFile();
        File outside = Files.createTempDirectory("thumb-outside").toFile();
        File src = makePng(outside, "src.png", 10, 10);   // a real image, but OUTSIDE the allowed root
        try {
            Thumbnailer.create(Arrays.asList(src.getPath(), new File(root, "t.png").getPath(), 20), policy(root));
            fail("expected rejection for a src outside the allowed roots");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("outside the allowed roots"));
        }
    }

    @Test
    public void rejectsDestOutsideAllowedRoots() throws Exception {
        File root = Files.createTempDirectory("thumb-root").toFile();
        File outside = Files.createTempDirectory("thumb-outside").toFile();
        File src = makePng(root, "src.png", 10, 10);       // src inside the root
        try {
            Thumbnailer.create(Arrays.asList(src.getPath(), new File(outside, "t.png").getPath(), 20), policy(root));
            fail("expected rejection for a dest outside the allowed roots");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("outside the allowed roots"));
        }
    }
}
