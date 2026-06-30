package com.flatide.teebox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flatide.platform.DefaultPlatformProvider;
import com.flatide.scheduler.ThreadContext;
import com.flatide.task.UnsupportedTaskRunner;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;

/** End-to-end: a ProperTee script run through TeeBox's ScriptExecutor can call THUMBNAIL and read its Result. */
public class ThumbnailBuiltinIntegrationTest {

    private static ScriptExecutor.Callbacks noopCallbacks() {
        return new ScriptExecutor.Callbacks() {
            @Override public void onStdout(String line) {}
            @Override public void onStderr(String line) {}
            @Override public void onThreadCreated(ThreadContext t) {}
            @Override public void onThreadUpdated(ThreadContext t) {}
            @Override public void onThreadCompleted(ThreadContext t) {}
            @Override public void onThreadError(ThreadContext t) {}
        };
    }

    @Test
    public void scriptCallsThumbnailAndGetsResult() throws Exception {
        File dir = Files.createTempDirectory("thumb-e2e").toFile();
        File src = new File(dir, "src.png");
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", src);
        File dest = new File(dir, "out/thumb.png");

        File script = new File(dir, "s.tee");
        String tee = "r = THUMBNAIL(\"" + src.getPath() + "\", \"" + dest.getPath() + "\", 20)\n"
                + "result = { \"ok\": r.ok, \"w\": r.value.width, \"h\": r.value.height }\n";
        Files.write(script.toPath(), tee.getBytes("UTF-8"));

        // THUMBNAIL is registered only when an allowed-roots policy is configured; allow the temp dir.
        StreamResultSupport policy = new StreamResultSupport(Arrays.asList(dir));
        ScriptExecutor exec = new ScriptExecutor(new DefaultPlatformProvider(), policy);
        ScriptExecutor.ExecutionResult res = exec.execute(
                script, new LinkedHashMap<String, Object>(), 1000, "error",
                "run-1", "thumb", "v1", new UnsupportedTaskRunner(), noopCallbacks());

        assertTrue(res.errorMessage, res.success);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.resultData;
        assertEquals(Boolean.TRUE, data.get("ok"));
        assertEquals(20, data.get("w"));        // 100x50 bounded by 20 -> 20x10
        assertEquals(10, data.get("h"));
        assertTrue(dest.isFile());
    }
}
