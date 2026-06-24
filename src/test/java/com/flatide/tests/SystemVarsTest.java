package com.flatide.tests;

import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.client.TeeBoxClient;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TeeBox injects a reserved {@code _SYS} object (runId / scriptId / version) as a global variable so a
 * script can read its own run id. It must NOT leak into {@code _PROPS} (which stays user-input only).
 */
public class SystemVarsTest {

    @Test
    public void scriptSeesSysRunIdAndPropsStaysClean() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-sys-it").toFile();
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 2;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            TeeBoxClient client = new TeeBoxClient("http://127.0.0.1:" + server.getPort());
            client.registerScript("sysprobe",
                "return {\"runId\": _SYS.runId, \"scriptId\": _SYS.scriptId, "
              + "\"version\": _SYS.version, \"propsHasSys\": HAS_KEY(_PROPS, \"_SYS\")}\n",
                true);
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            props.put("a", Integer.valueOf(1));
            String runId = (String) client.submitRun("sysprobe", props).get("runId");
            client.waitForRunTerminal(runId, 30000L);

            Map<String, Object> result = client.getRunResult(runId);
            Map<?, ?> data = (Map<?, ?>) result.get("resultData");
            Assert.assertEquals("script must see its own run id", runId, data.get("runId"));
            Assert.assertEquals("sysprobe", data.get("scriptId"));
            Assert.assertEquals("1", data.get("version"));
            Assert.assertEquals("_SYS must not be in _PROPS", Boolean.FALSE, data.get("propsHasSys"));
        } finally {
            server.stop();
        }
    }
}
