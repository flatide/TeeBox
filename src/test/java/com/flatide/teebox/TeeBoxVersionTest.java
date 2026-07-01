package com.flatide.teebox;

import java.io.File;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** The build version is baked into a classpath resource by Gradle and surfaced through the runtime. */
public class TeeBoxVersionTest {

    @Test
    public void versionIsResolvedFromBakedResource() {
        String v = TeeBoxVersion.get();
        Assert.assertNotNull(v);
        Assert.assertNotEquals("teebox-version.properties should be baked by processResources", "unknown", v);
        Assert.assertFalse("the ${version} token must be expanded, not left literal", v.contains("${"));
        Assert.assertTrue("should look like a semantic version: " + v, v.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    public void systemInfoCarriesVersion() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-version-sysinfo").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.dataDir = dataDir;
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.maxConcurrentRuns = 1;

        SystemInfo info = new SystemInfoCollector(config).collect();
        Assert.assertEquals("system API should report the build version", TeeBoxVersion.get(), info.teeboxVersion);
        Assert.assertFalse(info.teeboxVersion.isEmpty());
    }
}
