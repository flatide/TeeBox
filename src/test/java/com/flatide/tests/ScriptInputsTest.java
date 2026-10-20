package com.flatide.tests;

import com.flatide.teebox.ScriptInfo;
import com.flatide.teebox.ScriptInputSchema;
import com.flatide.teebox.ScriptRegistry;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.client.TeeBoxClient;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptInputsTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"path\":{\"type\":\"string\",\"description\":\"File to read\"},"
            + "\"optional\":{\"type\":[\"string\",\"null\"],\"default\":null},"
            + "\"large\":{\"type\":\"integer\",\"default\":9007199254740993}},\"required\":[\"path\"]}";
    private static final String DEFAULTS_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "text":{"type":"string","description":"Empty text is a value","default":"","enum":["","hello"]},
              "nullable":{"type":["string","null"],"default":null},
              "count":{"type":"integer","default":0,"minimum":0},
              "enabled":{"type":"boolean","default":false},
              "items":{"type":"array","default":[0,false,null,""],"items":{}},
              "options":{"type":"object","default":{"enabled":false,"nested":{"value":null}}}
            },"required":["text","count"],"x-ui":{"order":["text","count"]}}
            """;

    @Test
    public void schemaRoundTripPreservesNullAndLargeNumbersWithoutExposingMutableState() {
        ScriptInputSchema schema = ScriptInputSchema.parse(SCHEMA);
        JsonObject copy = schema.toJson();
        copy.addProperty("type", "string");
        Assert.assertEquals(JsonParser.parseString(SCHEMA), schema.toJson());
        Gson gson = new Gson(); // production Gson omits ordinary Java null fields
        ScriptInfo script = new ScriptInfo();
        com.flatide.teebox.ScriptVersionInfo version = new com.flatide.teebox.ScriptVersionInfo();
        version.version = "1";
        version.inputSchema = schema;
        script.versions.add(version);
        ScriptInfo reloaded = gson.fromJson(gson.toJson(script.copy()), ScriptInfo.class);
        Assert.assertEquals(JsonParser.parseString(SCHEMA), reloaded.versions.get(0).inputSchema.toJson());
        Assert.assertTrue(gson.toJson(Map.of("inputSchema", schema)).contains("9007199254740993"));
        Assert.assertNull(ScriptInputSchema.parse(""));
        Assert.assertNull(ScriptInputSchema.parse("null"));
        Assert.assertNotNull("{} is a defined unrestricted schema", ScriptInputSchema.parse("{}"));
        ScriptInputSchema large = ScriptInputSchema.parse("{\"description\":\"" + "<".repeat(12000) + "\"}");
        Assert.assertTrue(large.prettyJson().length() <= ScriptInputSchema.MAX_LENGTH);
        Assert.assertEquals(large.toJson(), ScriptInputSchema.parse(large.prettyJson()).toJson());
    }

    @Test
    public void rejectsMalformedDefinitionsButPreservesAdditionalSchemaKeywords() {
        for (String invalid : List.of("[]", "true", "{", "{} false", "{\"type\":\"array\"}",
                "{\"properties\":[]}", "{\"required\":[1]}", "{\"required\":[\"x\",\"x\"]}",
                "{\"properties\":{\"x\":{\"type\":\"typo\"}}}", "{\"properties\":{\"x\":{\"type\":[]}}}",
                "{\"enum\":[]}", "{\"description\":false}", " ".repeat(ScriptInputSchema.MAX_LENGTH) + "{}")) {
            Assert.assertThrows(invalid, IllegalArgumentException.class, () -> ScriptInputSchema.parse(invalid));
        }
        String deep = "{\"default\":" + "[".repeat(35) + "0" + "]".repeat(35) + "}";
        Assert.assertThrows(IllegalArgumentException.class, () -> ScriptInputSchema.parse(deep));
        String advanced = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                + "\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1}},\"x-ui\":{\"order\":[\"rows\"]}}";
        Assert.assertEquals(JsonParser.parseString(advanced), ScriptInputSchema.parse(advanced).toJson());
    }

    @Test
    public void versionDefinitionsPersistCopyAndFollowActivationWithoutChangingLegacySaves() throws Exception {
        File dir = Files.createTempDirectory("teebox-input-registry").toFile();
        ScriptRegistry registry = new ScriptRegistry(dir);
        registry.registerVersionDetailedWithAlias("inputs", "1", "return 1\n", "one", "Alias", true,
                null, null, ScriptInputSchema.parse(SCHEMA));
        registry.registerVersionDetailedWithAlias("inputs", "2", "return 2\n", "two", null, false,
                null, null, ScriptInputSchema.parse("{\"type\":\"object\",\"required\":[\"other\"]}"));
        Assert.assertEquals("1", registry.getVersionInputs("inputs", null).version);
        registry.updateVersionContent("inputs", "1", "return 3\n", null, "legacy save");
        registry = new ScriptRegistry(dir);
        Assert.assertEquals(JsonParser.parseString(SCHEMA), registry.getVersionInputs("inputs", "1").inputSchema.toJson());
        registry.activateVersion("inputs", "2");
        Assert.assertEquals("2", registry.getVersionInputs("inputs", null).version);
        registry.duplicateScript("inputs", "copy", null);
        registry.updateVersionInputs("inputs", "1", null);
        Assert.assertFalse(registry.getVersionInputs("inputs", "1").hasInputSchema);
        Assert.assertTrue(registry.getVersionInputs("copy", "1").hasInputSchema);
        registry.updateVersionContent("inputs", "2", "return 4\n", null, null, true, null);
        Assert.assertFalse(registry.getVersionInputs("inputs", "2").hasInputSchema);
    }

    @Test
    public void clientTokenCanDiscoverInputsAndPublisherTokenAloneCanChangeThem() throws Exception {
        try (Fixture f = new Fixture()) {
            String publish = "{\"scriptId\":\"inputs\",\"version\":\"1\",\"alias\":\"Files\","
                    + "\"content\":\"return _PROPS\\n\",\"inputSchema\":" + SCHEMA + "}";
            f.request("POST", "/api/publisher/scripts", publish, "publisher", 201);
            // A script literally named "inputs" must not be mistaken for the nested resource.
            Assert.assertTrue(f.html("/admin/scripts/inputs").contains("Inputs (1)"));
            Assert.assertEquals("inputs", f.request("GET", "/api/publisher/scripts/inputs", null,
                    "publisher", 200).getAsJsonObject().get("scriptId").getAsString());
            JsonObject inputs = f.request("GET", "/api/client/scripts/inputs/inputs", null, "client", 200).getAsJsonObject();
            Assert.assertEquals("1", inputs.get("version").getAsString());
            Assert.assertEquals(JsonParser.parseString(SCHEMA), inputs.get("inputSchema"));
            Assert.assertFalse(inputs.has("source"));
            Assert.assertFalse(inputs.has("owner"));
            Assert.assertEquals(JsonParser.parseString(SCHEMA), f.request("GET",
                    "/api/publisher/scripts/inputs/versions/1/inputs", null, "publisher", 200).getAsJsonObject().get("inputSchema"));
            JsonObject item = f.request("GET", "/api/client/scripts", null, "client", 200).getAsJsonArray().get(0).getAsJsonObject();
            Assert.assertEquals("Files", item.get("alias").getAsString());
            Assert.assertTrue(item.get("hasInputSchema").getAsBoolean());
            Assert.assertFalse(item.has("versions"));
            f.request("GET", "/api/client/scripts", null, "publisher", 401);
            f.request("GET", "/api/client/scripts/inputs/inputs", null, null, 401);
            f.request("PUT", "/api/publisher/scripts/inputs/versions/1/inputs", "{}", "client", 401);

            TeeBoxClient client = new TeeBoxClient(f.base).setClientApiToken("client").setPublisherApiToken("publisher");
            // Metadata does not reject a missing required field or inject default values.
            Map<String, Object> run = client.runAndWait("inputs", null, new LinkedHashMap<String, Object>(), 10000L);
            Assert.assertEquals("COMPLETED", run.get("status"));
            Assert.assertTrue(((Map<?, ?>) run.get("resultData")).isEmpty());
            Assert.assertEquals("1", client.getScriptInputs("inputs").get("version"));
            Assert.assertEquals(1, client.listScriptCatalog().size());

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("default", null);
            client.addScriptVersionWithInputs("inputs", "return 2\n", schema, false);
            Assert.assertEquals("1", client.getScriptInputs("inputs").get("version"));
            Assert.assertTrue(((Map<?, ?>) client.getScriptInputs("inputs", "2").get("inputSchema")).containsKey("default"));
            client.updateScriptInputs("inputs", "1", schema);
            Assert.assertEquals(schema, client.getScriptInputs("inputs", "1").get("inputSchema"));
            Assert.assertEquals(Boolean.FALSE, client.deleteScriptInputs("inputs", "1").get("hasInputSchema"));
            client.registerScriptWithInputs("new_inputs", "return 1\n", schema, true);
            Assert.assertEquals(Boolean.TRUE, client.getScriptInputs("new_inputs").get("hasInputSchema"));
        }
    }

    @Test
    public void handlesUndefinedMissingInactiveAndDeletedDefinitionsExplicitly() throws Exception {
        try (Fixture f = new Fixture()) {
            TeeBoxClient client = new TeeBoxClient(f.base).setClientApiToken("client").setPublisherApiToken("publisher");
            client.registerScript("legacy", "return 1\n", true);
            Assert.assertEquals(Boolean.FALSE, client.getScriptInputs("legacy").get("hasInputSchema"));
            f.server.getRunManager().createScript("shell", null);
            f.request("GET", "/api/client/scripts/shell/inputs", null, "client", 409);
            f.request("GET", "/api/client/scripts/missing/inputs", null, "client", 404);
            f.request("GET", "/api/client/scripts/legacy/inputs?version=99", null, "client", 404);
            f.request("GET", "/api/client/scripts/legacy/inputs?version=bad", null, "client", 400);
            String url = "/api/publisher/scripts/legacy/versions/1/inputs";
            f.request("PUT", url, "[]", "publisher", 400);
            f.request("PUT", url, "null", "publisher", 400);
            f.request("PUT", url, "{\"required\":false}", "publisher", 400);
            Assert.assertEquals(Boolean.FALSE, client.getScriptInputs("legacy").get("hasInputSchema"));
            f.request("PUT", url, "{}", "publisher", 200);
            Assert.assertEquals(Boolean.TRUE, client.getScriptInputs("legacy").get("hasInputSchema"));
            f.request("PUT", url.replace("/1/", "/99/"), "{}", "publisher", 404);
            f.request("POST", "/api/publisher/scripts", "{\"scriptId\":\"invalid\",\"content\":\"return 1\\n\",\"inputSchema\":[]}", "publisher", 400);
            f.request("POST", "/api/publisher/scripts", "[]", "publisher", 400);
            Assert.assertNull(f.server.getRunManager().getScript("invalid"));
            f.server.getRunManager().deleteScript("legacy");
            f.request("GET", "/api/client/scripts/legacy/inputs", null, "client", 404);
            f.request("PUT", url, "{}", "publisher", 404);
            Assert.assertEquals(1, client.listScriptCatalog().size()); // only shell remains
        }
    }

    @Test
    public void uiSavesInputsWithSelectedVersionAndCanSaveOnlyMetadata() throws Exception {
        try (Fixture f = new Fixture()) {
            String versionForm = "scriptId=ui_inputs&content=" + enc("return 1\n") + "&inputSchemaJson=" + enc(SCHEMA);
            f.form("/admin/scripts/register", versionForm, 302);
            String page = f.html("/admin/scripts/ui_inputs?version=1");
            Assert.assertTrue(page.contains("name='inputSchemaJson' form='version-source-form'"));
            Assert.assertTrue(page.contains("data-inputs-save"));
            Assert.assertTrue(page.contains(">Add parameter</button>"));
            Assert.assertFalse(page.contains("input-clear"));
            Assert.assertTrue(page.contains("File to read"));
            Assert.assertTrue(page.contains("id='run-input-version'"));
            f.form("/admin/scripts/register", versionForm, 302); // Save as new version carries textarea
            Assert.assertEquals(JsonParser.parseString(SCHEMA), f.server.getRunManager().getScriptInputs("ui_inputs", "2").inputSchema.toJson());
            f.form("/admin/scripts/update-source", "scriptId=ui_inputs&version=2&content=" + enc("return 9\n") + "&inputSchemaJson=[]", 400);
            Assert.assertEquals("return 1\n", f.server.getRunManager().getScriptVersionContent("ui_inputs", "2"));
            f.form("/admin/scripts/inputs/ui_inputs", "version=2&inputSchemaJson=", 302);
            Assert.assertFalse(f.server.getRunManager().getScriptInputs("ui_inputs", "2").hasInputSchema);
            Assert.assertTrue(f.server.getRunManager().getScriptInputs("ui_inputs", "1").hasInputSchema);
            f.form("/admin/scripts/inputs/ui_inputs", "version=1&inputSchemaJson=" + enc("{\"description\":\"</textarea><script>oops()</script>\"}"), 302);
            page = f.html("/admin/scripts/ui_inputs?version=1");
            Assert.assertFalse(page.contains("<script>oops()</script>"));
        }
    }

    @Test
    public void uiFormsPreserveJsonDefaultsAcrossSavePathsAndRegistryReload() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject schema = JsonParser.parseString(DEFAULTS_SCHEMA).getAsJsonObject();
            f.form("/admin/scripts/register", "scriptId=defaults&content=" + enc("return 1\n")
                    + "&inputSchemaJson=" + enc(schema.toString()), 302);
            assertStoredInputs(f, "defaults", "1", schema);

            // Source Save must carry all JSON value kinds, not just string-like defaults.
            schema.addProperty("description", "Saved together with the source");
            f.form("/admin/scripts/update-source", "scriptId=defaults&version=1&content=" + enc("return _PROPS\n")
                    + "&inputSchemaJson=" + enc(schema.toString()), 302);
            assertStoredInputs(f, "defaults", "1", schema);
            Assert.assertEquals("return _PROPS\n", f.server.getRunManager().getScriptVersionContent("defaults", "1"));

            // Save inputs changes only the metadata, keeping the same source and defaults.
            schema.addProperty("description", "Saved as metadata only");
            f.form("/admin/scripts/inputs/defaults", "version=1&inputSchemaJson=" + enc(schema.toString()), 302);
            assertStoredInputs(f, "defaults", "1", schema);
            Assert.assertEquals("return _PROPS\n", f.server.getRunManager().getScriptVersionContent("defaults", "1"));

            JsonObject next = schema.deepCopy();
            next.addProperty("description", "Saved as a new version");
            f.form("/admin/scripts/register", "scriptId=defaults&content=" + enc("return _PROPS\n")
                    + "&inputSchemaJson=" + enc(next.toString()), 302);
            assertStoredInputs(f, "defaults", "2", next);
            assertStoredInputs(f, "defaults", "1", schema);

            TeeBoxClient client = new TeeBoxClient(f.base).setClientApiToken("client");
            Map<String, Object> run = client.runAndWait("defaults", "2", new LinkedHashMap<String, Object>(), 10000L);
            Assert.assertEquals("COMPLETED", run.get("status"));
            Assert.assertTrue("editing defaults must not inject runtime props", ((Map<?, ?>) run.get("resultData")).isEmpty());
        }
    }

    @Test
    public void removingAnInputDefaultPreservesOtherDefaultsAndSchemaKeywords() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonObject expected = JsonParser.parseString(DEFAULTS_SCHEMA).getAsJsonObject();
            f.form("/admin/scripts/register", "scriptId=remove_defaults&content=" + enc("return 1\n")
                    + "&inputSchemaJson=" + enc(expected.toString()), 302);
            for (String name : List.of("text", "nullable", "count", "enabled", "items", "options")) {
                // A blank Default field removes just this keyword, not the property definition.
                JsonObject definition = expected.getAsJsonObject("properties").getAsJsonObject(name);
                Assert.assertTrue(definition.has("default"));
                definition.remove("default");
                f.form("/admin/scripts/inputs/remove_defaults", "version=1&inputSchemaJson=" + enc(expected.toString()), 302);
                assertStoredInputs(f, "remove_defaults", "1", expected);
            }
            Assert.assertEquals("return 1\n", f.server.getRunManager().getScriptVersionContent("remove_defaults", "1"));
        }
    }

    private static void assertStoredInputs(Fixture fixture, String scriptId, String version, JsonObject expected)
            throws Exception {
        Assert.assertEquals(expected, fixture.server.getRunManager().getScriptInputs(scriptId, version).inputSchema.toJson());
        Assert.assertEquals(expected, fixture.request("GET", "/api/client/scripts/" + scriptId + "/inputs?version=" + version,
                null, "client", 200).getAsJsonObject().get("inputSchema"));
        Assert.assertEquals(expected, fixture.request("GET", "/api/publisher/scripts/" + scriptId + "/versions/" + version + "/inputs",
                null, "publisher", 200).getAsJsonObject().get("inputSchema"));
        Assert.assertEquals("defaults survive a fresh registry load", expected,
                new ScriptRegistry(fixture.dataDir).getVersionInputs(scriptId, version).inputSchema.toJson());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {
        final File dataDir;
        final TeeBoxServer server;
        final String base;

        Fixture() throws Exception {
            TeeBoxConfig config = new TeeBoxConfig();
            dataDir = Files.createTempDirectory("teebox-inputs-api").toFile();
            config.dataDir = dataDir;
            config.bindAddress = "127.0.0.1";
            config.port = 0;
            config.maxConcurrentRuns = 2;
            config.clientApiToken = "client";
            config.publisherApiToken = "publisher";
            config.adminApiToken = "admin";
            server = new TeeBoxServer(config);
            server.start();
            base = "http://127.0.0.1:" + server.getPort();
        }

        JsonElement request(String method, String path, String body, String token, int expected) throws Exception {
            return JsonParser.parseString(exchange(method, path, body, token, "application/json", expected));
        }

        void form(String path, String body, int expected) throws Exception {
            exchange("POST", path, body, null, "application/x-www-form-urlencoded", expected);
        }

        String html(String path) throws Exception {
            return exchange("GET", path, null, null, null, 200);
        }

        String exchange(String method, String path, String body, String token, String contentType, int expected) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            try {
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", contentType);
                    try (var out = conn.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                int status = conn.getResponseCode();
                String text;
                try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                    text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Assert.assertEquals(text, expected, status);
                return text;
            } finally {
                conn.disconnect();
            }
        }

        @Override public void close() { server.stop(); }
    }
}
