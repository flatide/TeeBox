package com.flatide.teebox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * THUMBNAIL must not depend on the shell that started the server. A JVM started with a DISPLAY
 * variable in its environment (a terminal with X forwarding) is not headless by default: its first
 * drawing call tries to open that X display, and when that fails, that call and every later one fail
 * — the later ones with "Could not initialize class java.awt.GraphicsEnvironment$LocalGE".
 */
public class ThumbnailHeadlessTest {

    @Test
    public void thumbnailWorksWhenTheServerWasStartedWithADisplayItCannotReach() throws Exception {
        List<String> out = probe();

        assertTrue("the probe must start with the property unset: " + out, out.contains("before=null"));
        assertTrue(out.toString(), out.contains("width=20"));
        assertTrue("THUMBNAIL must leave the JVM headless: " + out, out.contains("headless=true"));
    }

    @Test
    public void anExplicitSettingIsLeftAlone() throws Exception {
        // Headless either way here, so the call works on every machine; what is checked is that the
        // operator's own value is not replaced.
        List<String> out = probe("-Djava.awt.headless=TRUE");

        assertTrue(out.toString(), out.contains("width=20"));
        assertTrue(out.toString(), out.contains("property=TRUE"));
    }

    private List<String> probe(String... jvmOptions) throws Exception {
        File dir = Files.createTempDirectory("thumb-headless").toFile();
        File src = new File(dir, "src.png");
        ImageIO.write(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), "png", src);
        File dest = new File(dir, "thumb.png");

        List<String> command = new ArrayList<String>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.addAll(Arrays.asList(jvmOptions));
        command.addAll(Arrays.asList("-cp", System.getProperty("java.class.path"),
            ThumbnailHeadlessProbe.class.getName(), src.getPath(), dest.getPath()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // A display nothing listens on, as left behind by a closed X-forwarding session.
        builder.environment().put("DISPLAY", "127.0.0.1:4999");
        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, len);
        }
        String output = bytes.toString("UTF-8");
        assertEquals("probe output:\n" + output, 0, process.waitFor());
        assertTrue("no thumbnail was written:\n" + output, dest.isFile());
        return Arrays.asList(output.split("\\R"));
    }
}
