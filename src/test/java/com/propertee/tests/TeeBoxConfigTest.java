package com.propertee.tests;

import com.propertee.teebox.TeeBoxConfig;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

public class TeeBoxConfigTest {

    @Test
    public void shouldLoadSettingsFromConfigFile() throws Exception {
        File root = Files.createTempDirectory("propertee-teebox-config").toFile();
        File configFile = new File(root, "teebox.properties");
        write(configFile,
            "propertee.teebox.bind=0.0.0.0\n" +
            "propertee.teebox.port=19090\n" +
            "propertee.teebox.scriptsRoot=" + new File(root, "scripts").getAbsolutePath() + "\n" +
            "propertee.teebox.dataDir=" + new File(root, "data").getAbsolutePath() + "\n" +
            "propertee.teebox.maxRuns=7\n" +
            "propertee.teebox.apiToken=test-token\n");

        String oldConfig = System.getProperty("propertee.teebox.config");
        String oldPort = System.getProperty("propertee.teebox.port");
        System.setProperty("propertee.teebox.config", configFile.getAbsolutePath());
        System.clearProperty("propertee.teebox.port");
        try {
            TeeBoxConfig config = TeeBoxConfig.fromArgs(new String[0]);
            Assert.assertEquals("0.0.0.0", config.bindAddress);
            Assert.assertEquals(19090, config.port);
            Assert.assertEquals(7, config.maxConcurrentRuns);
            Assert.assertEquals("test-token", config.apiToken);
            Assert.assertEquals(new File(root, "scripts").getCanonicalPath(), config.scriptsRoot.getCanonicalPath());
            Assert.assertEquals(new File(root, "data").getCanonicalPath(), config.dataDir.getCanonicalPath());
        } finally {
            restoreProperty("propertee.teebox.config", oldConfig);
            restoreProperty("propertee.teebox.port", oldPort);
        }
    }

    @Test
    public void systemPropertiesShouldOverrideConfigFile() throws Exception {
        File root = Files.createTempDirectory("propertee-teebox-config-override").toFile();
        File configFile = new File(root, "teebox.properties");
        write(configFile,
            "propertee.teebox.scriptsRoot=" + new File(root, "scripts").getAbsolutePath() + "\n" +
            "propertee.teebox.dataDir=" + new File(root, "data").getAbsolutePath() + "\n" +
            "propertee.teebox.port=18080\n");

        String oldConfig = System.getProperty("propertee.teebox.config");
        String oldPort = System.getProperty("propertee.teebox.port");
        System.setProperty("propertee.teebox.config", configFile.getAbsolutePath());
        System.setProperty("propertee.teebox.port", "19999");
        try {
            TeeBoxConfig config = TeeBoxConfig.fromArgs(new String[0]);
            Assert.assertEquals(19999, config.port);
        } finally {
            restoreProperty("propertee.teebox.config", oldConfig);
            restoreProperty("propertee.teebox.port", oldPort);
        }
    }

    @Test
    public void shouldResolveNamespaceTokensWithFallback() throws Exception {
        File root = Files.createTempDirectory("propertee-teebox-config-tokens").toFile();
        File configFile = new File(root, "teebox.properties");
        write(configFile,
            "propertee.teebox.scriptsRoot=" + new File(root, "scripts").getAbsolutePath() + "\n" +
            "propertee.teebox.dataDir=" + new File(root, "data").getAbsolutePath() + "\n" +
            "propertee.teebox.apiToken=default-token\n" +
            "propertee.teebox.clientApiToken=client-token\n" +
            "propertee.teebox.publisherApiToken=publisher-token\n");

        String oldConfig = System.getProperty("propertee.teebox.config");
        System.setProperty("propertee.teebox.config", configFile.getAbsolutePath());
        try {
            TeeBoxConfig config = TeeBoxConfig.fromArgs(new String[0]);
            Assert.assertEquals("default-token", config.apiToken);
            Assert.assertEquals("client-token", config.clientApiToken);
            Assert.assertEquals("publisher-token", config.publisherApiToken);
            Assert.assertNull(config.adminApiToken);
            Assert.assertEquals("client-token", config.tokenForClientApi());
            Assert.assertEquals("publisher-token", config.tokenForPublisherApi());
            Assert.assertEquals("default-token", config.tokenForAdminApi());
        } finally {
            restoreProperty("propertee.teebox.config", oldConfig);
        }
    }

    private static void write(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(Charset.forName("UTF-8")));
        } finally {
            out.close();
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
