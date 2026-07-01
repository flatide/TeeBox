package com.flatide.tests;

import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.TeeBoxConfig;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.List;

/**
 * Integration coverage for multi-user admin-UI authentication + per-script ownership.
 * Roster is provisioned by writing users.json before the server starts.
 */
public class TeeBoxMultiUserUiTest {

    private static final String SCRIPT_BODY = "return {\"v\": 1}\n";

    @Test
    public void ownershipGovernsAdminUiActions() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser").toFile();
        writeRoster(dataDir, "[{\"username\":\"admin\",\"role\":\"admin\"},"
                + "{\"username\":\"alice\",\"role\":\"user\"},"
                + "{\"username\":\"bob\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            // --- alice: first login registers her password, then owns what she registers ---
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull("alice first login should succeed", alice);

            // Wrong password now rejected (password was set on first login).
            Assert.assertNull("second login with wrong password rejected", login(base, "alice", "nope"));

            assertRedirect("alice registers her script", postForm(base, "/admin/scripts/register",
                    "scriptId=alice_script&content=" + enc(SCRIPT_BODY) + "&activate=on", alice));
            assertRedirect("alice runs her script", postForm(base, "/admin/submit",
                    "scriptId=alice_script&propsJson=" + enc("{}") + "&maxIterations=1000", alice));
            assertRedirect("alice edits her script source", postForm(base, "/admin/scripts/update-source",
                    "scriptId=alice_script&version=1&content=" + enc(SCRIPT_BODY), alice));

            // --- bob: cannot touch alice's script ---
            String bob = login(base, "bob", "bob-pw");
            Assert.assertNotNull("bob first login should succeed", bob);

            assertForbidden("bob edits alice's script", postForm(base, "/admin/scripts/update-source",
                    "scriptId=alice_script&version=1&content=" + enc(SCRIPT_BODY), bob));
            assertForbidden("bob activates alice's script", postForm(base, "/admin/scripts/activate/alice_script",
                    "version=1", bob));
            assertForbidden("bob deletes alice's script", postForm(base, "/admin/scripts/delete/alice_script",
                    "", bob));
            assertForbidden("bob runs alice's script", postForm(base, "/admin/submit",
                    "scriptId=alice_script&propsJson=" + enc("{}") + "&maxIterations=1000", bob));

            // bob owns what he registers.
            assertRedirect("bob registers his script", postForm(base, "/admin/scripts/register",
                    "scriptId=bob_script&content=" + enc(SCRIPT_BODY) + "&activate=on", bob));
            assertRedirect("bob edits his own script", postForm(base, "/admin/scripts/update-source",
                    "scriptId=bob_script&version=1&content=" + enc(SCRIPT_BODY), bob));

            // bob is not an admin: server control is denied.
            assertForbidden("bob attempts shutdown", postForm(base, "/admin/shutdown", "", bob));

            // --- admin: may act on any script ---
            String admin = login(base, "admin", "admin-pw");
            Assert.assertNotNull("admin first login should succeed", admin);
            assertRedirect("admin edits alice's script", postForm(base, "/admin/scripts/update-source",
                    "scriptId=alice_script&version=1&content=" + enc(SCRIPT_BODY), admin));
            assertRedirect("admin deletes alice's script", postForm(base, "/admin/scripts/delete/alice_script",
                    "", admin));

            // --- unauthenticated POST is redirected to login, not executed ---
            int anon = postForm(base, "/admin/scripts/delete/bob_script", "", null);
            Assert.assertTrue("anonymous mutating POST should redirect to login, got " + anon,
                    anon >= 300 && anon < 400);
        } finally {
            server.stop();
        }
    }

    @Test
    public void unauthenticatedAdminGetIsGatedBehindLogin() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser-getgate").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            // alice logs in and registers a script whose source must not leak to anonymous GET.
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);
            assertRedirect("alice registers", postForm(base, "/admin/scripts/register",
                    "scriptId=secret_script&content=" + enc(SCRIPT_BODY) + "&activate=on", alice));

            // The login page itself stays reachable without auth.
            Assert.assertEquals("login page GET open", 200, get(base, "/admin/login", null));

            // Every other /admin GET redirects to login for an unauthenticated client (no content leak).
            assertRedirect("anon GET dashboard", get(base, "/admin", null));
            assertRedirect("anon GET scripts list", get(base, "/admin/scripts", null));
            assertRedirect("anon GET script detail (source)", get(base, "/admin/scripts/secret_script", null));
            assertRedirect("anon GET runs", get(base, "/admin/runs", null));

            // A logged-in user can still read.
            Assert.assertEquals("authed GET script detail", 200, get(base, "/admin/scripts/secret_script", alice));
        } finally {
            server.stop();
        }
    }

    // ---- helpers ----

    /** GET with an optional session cookie; no redirect following. Returns the status code. */
    private int get(String base, String path, String cookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        if (cookie != null) {
            conn.setRequestProperty("Cookie", "teebox-session=" + cookie);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private TeeBoxServer startServer(File dataDir) throws Exception {
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 4;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        return server;
    }

    private void writeRoster(File dataDir, String json) throws IOException {
        File usersDir = new File(dataDir, "users");
        usersDir.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(usersDir, "users.json")), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
    }

    /** POST the login form and return the teebox-session cookie value, or null on failure. */
    private String login(String base, String user, String pass) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/admin/login").openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("user=" + enc(user) + "&password=" + enc(pass)).getBytes("UTF-8");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
        conn.getResponseCode();
        String cookie = extractSessionCookie(conn);
        conn.disconnect();
        return cookie;
    }

    private String extractSessionCookie(HttpURLConnection conn) {
        // com.sun.net.httpserver normalizes header names (e.g. "Set-cookie"), so match case-insensitively.
        for (java.util.Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String key = e.getKey();
            if (key == null || !"set-cookie".equalsIgnoreCase(key)) {
                continue;
            }
            for (String c : e.getValue()) {
                if (c != null && c.startsWith("teebox-session=")) {
                    String v = c.substring("teebox-session=".length());
                    int semi = v.indexOf(';');
                    if (semi >= 0) {
                        v = v.substring(0, semi);
                    }
                    return v.length() > 0 ? v : null;
                }
            }
        }
        return null;
    }

    private int postForm(String base, String path, String body, String cookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null) {
            conn.setRequestProperty("Cookie", "teebox-session=" + cookie);
        }
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private void assertRedirect(String what, int code) {
        Assert.assertTrue(what + ": expected 3xx redirect, got " + code, code >= 300 && code < 400);
    }

    private void assertForbidden(String what, int code) {
        Assert.assertEquals(what + ": expected 403", HttpURLConnection.HTTP_FORBIDDEN, code);
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }
}
