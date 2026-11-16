package com.flatide.tests;

import com.flatide.teebox.ScriptInfo;
import com.flatide.teebox.ScriptRegistry;
import com.flatide.teebox.ScriptVersionInfo;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Script files hold text in any language. They used to be decoded 4 KB at a time, which turned
 * every multi-byte character lying across a chunk boundary into U+FFFD. The reader serves the
 * source a run executes and the script's metadata, so a script longer than 4 KB ran with some of
 * its Korean text broken, and each metadata save wrote the damage back to disk.
 */
public class ScriptRegistryTest {

    @Test
    public void aLongScriptWithKoreanTextReadsBackExactly() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-script-text").toFile();
        ScriptRegistry registry = new ScriptRegistry(dataDir);
        StringBuilder script = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            script.append("// ").append(i).append("번째 단계: 고객 주문 내역을 집계한다\n");
            script.append("PRINT(\"처리 중 - ").append(i).append("번 배치\")\n");
        }
        String content = script.toString();
        Assert.assertTrue("the script must span several 4 KB chunks", content.getBytes("UTF-8").length > 16 * 1024);

        registry.registerVersion("report", "1", content, "집계", Arrays.asList("test"), true);

        Assert.assertEquals(content, registry.readVersionContent("report", "1"));
        // What a run executes, and the hash pinned to it, come from the same read.
        ScriptRegistry.ResolvedScript resolved = new ScriptRegistry(dataDir).resolve("report", null);
        Assert.assertEquals(content, resolved.source);
        Assert.assertEquals(registry.loadScript("report").versions.get(0).sha256, resolved.sha256);
    }

    @Test
    public void koreanVersionDescriptionsSurviveLaterSaves() throws Exception {
        File dataDir = Files.createTempDirectory("propertee-teebox-script-meta").toFile();
        ScriptRegistry registry = new ScriptRegistry(dataDir);
        Map<String, String> expected = new LinkedHashMap<String, String>();
        // Every registration loads the metadata and saves it again, so a description broken on
        // one load used to be written back for good.
        for (int v = 1; v <= 60; v++) {
            String description = "3분기 매출 집계 기준 변경 (" + v + "차 수정) — 담당: 운영팀 홍길동";
            expected.put(String.valueOf(v), description);
            registry.registerVersion("report", String.valueOf(v), "return " + v + "\n",
                description, Arrays.asList("test"), true);
        }

        ScriptInfo reloaded = new ScriptRegistry(dataDir).loadScript("report");
        Assert.assertEquals(60, reloaded.versions.size());
        for (ScriptVersionInfo version : reloaded.versions) {
            Assert.assertEquals("description of version " + version.version,
                expected.get(version.version), version.description);
        }
    }
}
