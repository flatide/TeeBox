package com.flatide.teebox;

import com.flatide.propertee2.module.ModuleRequest;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TeeBoxImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void moduleIdResolvesToScriptIdAndActiveOrExactNumericVersion() throws Exception {
        ScriptRegistry registry = new ScriptRegistry(temp.newFolder("data"));
        registry.registerVersion("util.file", "1", "function v() do return 1 end\n",
                "one", Collections.<String>emptyList(), true);
        registry.registerVersion("util.file", "2", "function v() do return 2 end\n",
                "two", Collections.<String>emptyList(), false);

        TeeBoxModuleResolver resolver = new TeeBoxModuleResolver(registry, null);
        Assert.assertEquals(Integer.valueOf(1),
                resolver.resolve(new ModuleRequest("util.file", null)).version());
        Assert.assertEquals(Integer.valueOf(2),
                resolver.resolve(new ModuleRequest("util.file", Integer.valueOf(2))).version());
    }

    @Test public void versionsAreNumericAndDeletedNumbersAreNeverReused() throws Exception {
        ScriptRegistry registry = new ScriptRegistry(temp.newFolder("versions"));
        ScriptRegistry.RegisteredVersion one = registry.registerVersionDetailed("tool", null,
                "return 1\n", "", Collections.<String>emptyList(), true, null, null);
        ScriptRegistry.RegisteredVersion two = registry.registerVersionDetailed("tool", null,
                "return 2\n", "", Collections.<String>emptyList(), true, null, null);
        Assert.assertEquals("1", one.version);
        Assert.assertEquals("2", two.version);

        registry.deleteVersion("tool", "1");
        ScriptRegistry.RegisteredVersion three = registry.registerVersionDetailed("tool", null,
                "return 3\n", "", Collections.<String>emptyList(), false, null, null);
        Assert.assertEquals("3", three.version);
        Assert.assertEquals(3, registry.loadScript("tool").lastAllocatedVersion);

        try {
            registry.registerVersion("tool", "v4", "return 4\n", "",
                    Collections.<String>emptyList(), false);
            Assert.fail("non-numeric versions must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("canonical positive integer"));
        }

        try {
            registry.resolve("tool", "v1");
            Assert.fail("non-numeric versions must not be executable");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("canonical positive integer"));
        }
    }

    @Test public void executorRunsRegistryModuleAndReportsPinnedIdentity() throws Exception {
        File data = temp.newFolder("execute");
        ScriptRegistry registry = new ScriptRegistry(data);
        registry.registerVersion("util.answer", "1", "" +
                "function get() do return 42 end\n", "", Collections.<String>emptyList(), true);
        File entry = temp.newFile("entry.tee");
        Files.writeString(entry.toPath(), "import util.answer as answer\nPRINT(answer::get())\n",
                StandardCharsets.UTF_8);

        final List<String> stdout = new ArrayList<String>();
        final List<ResolvedModuleInfo> resolved = new ArrayList<ResolvedModuleInfo>();
        ScriptExecutor executor = new ScriptExecutor(
                new com.flatide.propertee2.platform.DefaultPlatformProvider(), null, registry);
        ScriptExecutor.ExecutionResult result = executor.execute(entry, Collections.<String, Object>emptyMap(),
                1000, "error", "run-1", "entry", "1",
                new com.flatide.propertee2.task.UnsupportedTaskRunner(), new ScriptExecutor.Callbacks() {
                    @Override public void onStdout(String line) { stdout.add(line); }
                    @Override public void onStderr(String line) { }
                    @Override public void onThreadCreated(com.flatide.propertee2.scheduler.ThreadContext thread) { }
                    @Override public void onThreadUpdated(com.flatide.propertee2.scheduler.ThreadContext thread) { }
                    @Override public void onThreadCompleted(com.flatide.propertee2.scheduler.ThreadContext thread) { }
                    @Override public void onThreadError(com.flatide.propertee2.scheduler.ThreadContext thread) { }
                    @Override public void onModuleResolved(ResolvedModuleInfo module) { resolved.add(module); }
                });

        Assert.assertTrue(result.errorMessage, result.success);
        Assert.assertEquals(Collections.singletonList("42"), stdout);
        Assert.assertEquals(1, resolved.size());
        Assert.assertEquals("util.answer", resolved.get(0).scriptId);
        Assert.assertEquals("1", resolved.get(0).version);
        Assert.assertNotNull(resolved.get(0).sha256);
    }
}
