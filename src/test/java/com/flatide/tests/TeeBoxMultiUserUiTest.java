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

    @Test
    public void scriptEditorIsInjectedIntoAdminScriptPages() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser-editor").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);
            assertRedirect("alice registers", postForm(base, "/admin/scripts/register",
                    "scriptId=ed_script&content=" + enc("return {\"ok\": true}\n") + "&activate=on", alice));

            // The script detail page (edit-source) must carry the ported code editor: an upgradable
            // textarea, the syntax-highlight CSS, the verbatim highlighter JS, and the builtin panel data.
            String html = getBody(base, "/admin/scripts/ed_script", alice);
            Assert.assertTrue("textarea marked for upgrade", html.contains("data-pt-editor"));
            Assert.assertTrue("editor CSS inlined (syntax token class)", html.contains(".syn-fn"));
            Assert.assertTrue("editor JS inlined (verbatim highlighter)", html.contains("function highlightSyntax"));
            Assert.assertTrue("builtin panel data inlined", html.contains("BUILTIN_DOCS"));

            // The highlighter + panel must track the language's builtin catalog: the spec v0.10.0
            // Results builtins (FAIL/UNWRAP/OK/ERR/IS_RESULT) and the TeeBox host builtins
            // (STREAM_FILE/THUMBNAIL) are both highlighted and documented in the panel.
            Assert.assertTrue("Results builtins in the highlighter regex",
                    html.contains("FAIL|UNWRAP|OK|ERR|IS_RESULT"));
            Assert.assertTrue("TeeBox host builtins in the highlighter regex",
                    html.contains("STREAM_FILE|THUMBNAIL"));
            Assert.assertTrue("Results category in the panel docs", html.contains("cat: 'Results'"));
            Assert.assertTrue("UNWRAP documented in the panel", html.contains("name: 'UNWRAP'"));
            Assert.assertTrue("TeeBox Host category in the panel docs", html.contains("cat: 'TeeBox Host'"));
            Assert.assertTrue("STREAM_FILE documented in the panel", html.contains("name: 'STREAM_FILE'"));

            // The builtin panel is hidden by default and toggled by the ƒ button pinned to the
            // editor's top-right (TeeBox addition); the choice persists via localStorage.
            Assert.assertTrue("panel toggle wiring shipped (JS + CSS)", html.contains("pt-fn-toggle"));
            Assert.assertTrue("panel visibility persisted per browser", html.contains("teebox-fn-panel"));
            Assert.assertTrue("panel toggle function present", html.contains("function wirePanelToggle"));

            // The editor pre-check runs client-side via the inlined propertee-js bundle
            // (checkScript), seeded with the Java-runtime-enumerated known-name set so the client
            // and server lints can never disagree; the server validate endpoint stays the fallback.
            Assert.assertTrue("propertee-js bundle inlined (checkScript chain)",
                    html.contains("lintUnknownFunctions"));
            Assert.assertTrue("client-first check wired", html.contains("ptClientCheck"));
            int knownStart = html.indexOf("var PT_KNOWN=[");
            Assert.assertTrue("runtime known-name set rendered", knownStart >= 0);
            String knownList = html.substring(knownStart, html.indexOf("];", knownStart));
            Assert.assertTrue("TeeBox host builtins in the injected known set",
                    knownList.contains("'STREAM_FILE'") && knownList.contains("'THUMBNAIL'"));
            Assert.assertTrue("engine builtins in the injected known set", knownList.contains("'SHELL'"));


            // The Active Version Source card is now the single edit + add-version surface: the separate
            // "Add New Version" card is gone, and it carries both a "Save" (overwrite active) and a
            // "Save as new version" button that overrides the form action to the register endpoint.
            Assert.assertTrue("save-as-new-version button present", html.contains("Save as new version"));
            Assert.assertTrue("new-version button targets the register endpoint",
                    html.contains("formaction='/admin/scripts/register'"));
            Assert.assertFalse("the standalone Add New Version card is removed", html.contains("Add New Version"));
            Assert.assertTrue("editor is given more rows", html.contains("rows='24'"));

            // The heavy editor JS is only on editor pages — the dashboard stays lean.
            String dashboard = getBody(base, "/admin", alice);
            Assert.assertFalse("editor JS should not bloat non-editor pages",
                    dashboard.contains("function highlightSyntax"));
            Assert.assertFalse("the propertee-js bundle should not bloat non-editor pages",
                    dashboard.contains("lintUnknownFunctions"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void activeSourceSaveAsNewVersionAddsVersionWithoutChangingActive() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser-addver").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);
            // First version registers and auto-activates.
            assertRedirect("alice registers v1", postForm(base, "/admin/scripts/register",
                    "scriptId=av_script&content=" + enc("PRINT(\"one\")\n") + "&activate=on", alice));
            String before = getBody(base, "/admin/scripts/av_script", alice);
            Assert.assertTrue("one version to start", before.contains("Versions (1)"));

            // "Save as new version" = POST the editor content to the register endpoint with a blank
            // version (auto next #) and no activate: a new version is added, the active one is unchanged.
            assertRedirect("alice saves as a new version", postForm(base, "/admin/scripts/register",
                    "scriptId=av_script&content=" + enc("PRINT(\"two\")\n") + "&description=" + enc("second"), alice));
            String after = getBody(base, "/admin/scripts/av_script", alice);
            Assert.assertTrue("a second version was added", after.contains("Versions (2)"));
            Assert.assertTrue("the active version is unchanged (still 1)", after.contains("active (1)"));

            // "Save" = POST to update-source overwrites the active version in place (no new version).
            assertRedirect("alice overwrites the active version", postForm(base, "/admin/scripts/update-source",
                    "scriptId=av_script&version=1&content=" + enc("PRINT(\"one-edited\")\n"), alice));
            String edited = getBody(base, "/admin/scripts/av_script", alice);
            Assert.assertTrue("still two versions after an in-place save", edited.contains("Versions (2)"));
            Assert.assertTrue("active source reflects the in-place edit", edited.contains("one-edited"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void inactiveVersionIsSelectableAndEditableFromVersionsList() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser-editver").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);
            // v1 (active), then a v2 that is NOT activated.
            assertRedirect("register v1", postForm(base, "/admin/scripts/register",
                    "scriptId=ev_script&content=" + enc("PRINT(\"one\")\n") + "&activate=on", alice));
            assertRedirect("add v2 (not active)", postForm(base, "/admin/scripts/register",
                    "scriptId=ev_script&content=" + enc("PRINT(\"two\")\n"), alice));

            // Default page: edits the active version (1); the versions list offers an Edit link to v2.
            String def = getBody(base, "/admin/scripts/ev_script", alice);
            Assert.assertTrue("default targets the active version", def.contains("Version Source (1)"));
            Assert.assertTrue("default Save overwrites v1", def.contains("Overwrite version 1 in place"));
            Assert.assertTrue("versions list links to edit v2", def.contains("?version=2"));

            // Selecting v2 (the inactive version) opens it in the editor and Save overwrites v2.
            String v2 = getBody(base, "/admin/scripts/ev_script?version=2", alice);
            Assert.assertTrue("selected version shown", v2.contains("Version Source (2)"));
            Assert.assertTrue("selected version marked inactive", v2.contains("inactive"));
            Assert.assertTrue("v2 content is loaded", v2.contains("PRINT(&quot;two&quot;)"));
            Assert.assertTrue("Save overwrites v2, not the active one", v2.contains("Overwrite version 2 in place"));

            // Overwrite the inactive v2 in place; v1 stays active, v2 reflects the edit.
            assertRedirect("edit the inactive v2", postForm(base, "/admin/scripts/update-source",
                    "scriptId=ev_script&version=2&content=" + enc("PRINT(\"two-edited\")\n"), alice));
            String v2b = getBody(base, "/admin/scripts/ev_script?version=2", alice);
            Assert.assertTrue("v2 shows the edit", v2b.contains("PRINT(&quot;two-edited&quot;)"));
            Assert.assertTrue("the active version is still 1", v2b.contains("active (1)"));
            String v1 = getBody(base, "/admin/scripts/ev_script?version=1", alice);
            Assert.assertTrue("v1 is untouched by the v2 edit", v1.contains("PRINT(&quot;one&quot;)"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void firstRegistrationCreatesInactiveShellThenCodeIsAddedSeparately() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-multiuser-shell").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);

            // First registration is metadata-only: no content => an empty script shell (no version).
            assertRedirect("register empty shell", postForm(base, "/admin/scripts/register",
                    "scriptId=shell_script", alice));
            String shell = getBody(base, "/admin/scripts/shell_script", alice);
            Assert.assertTrue("shell has no versions", shell.contains("Versions (0)"));
            Assert.assertTrue("shell shows the empty-source editor", shell.contains("New Version Source"));
            Assert.assertTrue("shell prompts to add code", shell.contains("no versions yet"));
            Assert.assertTrue("shell can save-as-new-version", shell.contains("Save as new version"));
            Assert.assertFalse("shell has nothing to overwrite (no Save button)", shell.contains("Overwrite version"));

            // Content-less register against an EXISTING script is an error (not another shell),
            // and the error names the real mistake — the duplicate id, not missing content.
            String[] reRegister = postFormWithBody(base, "/admin/scripts/register", "scriptId=shell_script", alice);
            Assert.assertEquals("re-register with no content is rejected", "400", reRegister[0]);
            Assert.assertTrue("error says the script already exists",
                    reRegister[1].contains("Script already exists: shell_script"));
            Assert.assertFalse("error must not blame missing content",
                    reRegister[1].contains("Script content is required"));

            // Add the code from the detail page. The first version must NOT auto-activate.
            assertRedirect("add first version", postForm(base, "/admin/scripts/register",
                    "scriptId=shell_script&content=" + enc("PRINT(\"hi\")\n"), alice));
            String withV1 = getBody(base, "/admin/scripts/shell_script?version=1", alice);
            Assert.assertTrue("now has one version", withV1.contains("Versions (1)"));
            Assert.assertTrue("editing version 1", withV1.contains("Version Source (1)"));
            Assert.assertTrue("version 1 is inactive", withV1.contains("inactive"));
            Assert.assertTrue("warns there is no active version", withV1.contains("No active version yet"));

            // Explicit activation makes it runnable.
            assertRedirect("activate version 1", postForm(base, "/admin/scripts/activate/shell_script",
                    "version=1", alice));
            String active = getBody(base, "/admin/scripts/shell_script?version=1", alice);
            Assert.assertTrue("version 1 is now active", active.contains("ACTIVE"));
            Assert.assertFalse("no-active warning is gone", active.contains("No active version yet"));
        } finally {
            server.stop();
        }
    }

    /**
     * The reported "version contents got swapped" bug: after "Save as new version" the redirect
     * dropped {@code ?version=}, the detail page fell back to the ACTIVE (old) version — silently
     * putting the OLD content back into the editor — and the next in-place Save (whose button
     * targets the displayed version) overwrote the old version with content meant for the new one
     * (destroying the old version's source). The redirect must land on the version just saved,
     * and the editor/Save button must target it.
     */
    @Test
    public void saveAsNewVersionLandsOnTheNewVersionNotTheOldActive() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-save-as-new").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);

            // v1 = A, active (one-shot register).
            assertRedirect("register v1", postForm(base, "/admin/scripts/register",
                    "scriptId=swap_bug&content=" + enc("return {\"which\": \"A\"}\n") + "&activate=on", alice));

            // The user types B and clicks "Save as new version" (blank version => auto "2", NOT active).
            String location = postFormLocation(base, "/admin/scripts/register",
                    "scriptId=swap_bug&content=" + enc("return {\"which\": \"B\"}\n"), alice);
            Assert.assertEquals("redirect lands on the version just saved",
                    "/admin/scripts/swap_bug?version=2", location);

            // The redirected page keeps editing the NEW version: its content, and a Save button
            // that targets it — not the old active version.
            String page = getBody(base, location, alice);
            Assert.assertTrue("editor shows the new version", page.contains("Version Source (2)"));
            Assert.assertTrue("editor holds the new content", page.contains("&quot;B&quot;"));
            Assert.assertTrue("Save overwrites the new version",
                    page.contains("title='Overwrite version 2 in place'"));
            Assert.assertTrue("the Save button names its overwrite target",
                    page.contains(">Save (2)</button>"));

            // The follow-up edit therefore goes to v2; v1 keeps its original content.
            assertRedirect("edit continues on v2", postForm(base, "/admin/scripts/update-source",
                    "scriptId=swap_bug&version=2&content=" + enc("return {\"which\": \"B2\"}\n"), alice));
            String v1 = getBody(base, "/api/publisher/scripts/swap_bug/content?version=1", null);
            String v2 = getBody(base, "/api/publisher/scripts/swap_bug/content?version=2", null);
            Assert.assertTrue("old version untouched: " + v1, v1.contains("\\\"A\\\""));
            Assert.assertTrue("new version carries the edit: " + v2, v2.contains("\\\"B2\\\""));
        } finally {
            server.stop();
        }
    }

    // ---- helpers ----

    /** GET returning the response body (authed). */
    @Test
    public void userManagementIsAdminOnlyAndDrivesTheFullLifecycle() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-usermgmt").toFile();
        writeRoster(dataDir, "[{\"username\":\"admin\",\"role\":\"admin\"},"
                + "{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            // --- regular user: no menu, no page, no actions ---
            String alice = login(base, "alice", "alice-pw");
            Assert.assertNotNull(alice);
            Assert.assertFalse("regular user must not see the Users menu",
                    getBody(base, "/admin", alice).contains("href='/admin/users'"));
            assertForbidden("regular user GET /admin/users", get(base, "/admin/users", alice));
            assertForbidden("regular user POST add", postForm(base, "/admin/users/add",
                    "username=mallory&role=admin", alice));

            // --- admin: menu + page ---
            String admin = login(base, "admin", "admin-pw");
            Assert.assertNotNull(admin);
            Assert.assertTrue("admin sees the Users menu",
                    getBody(base, "/admin", admin).contains("href='/admin/users'"));
            String page = getBody(base, "/admin/users", admin);
            Assert.assertTrue("users page lists alice", page.contains("alice"));

            // --- add: new user can log in (password set on first login) ---
            assertRedirect("admin adds bob", postForm(base, "/admin/users/add",
                    "username=bob&role=user", admin));
            Assert.assertTrue("users page lists bob",
                    getBody(base, "/admin/users", admin).contains("bob"));
            String bob = login(base, "bob", "bob-pw");
            Assert.assertNotNull("added user logs in (first login)", bob);

            // Validation errors land back on the page as ?error=
            String dup = postFormLocation(base, "/admin/users/add", "username=bob&role=user", admin);
            Assert.assertTrue("duplicate add -> error callout, got " + dup,
                    dup != null && dup.startsWith("/admin/users?error="));
            String badName = postFormLocation(base, "/admin/users/add", "username=" + enc("no spaces!") + "&role=user", admin);
            Assert.assertTrue("invalid username -> error callout, got " + badName,
                    badName != null && badName.startsWith("/admin/users?error="));

            // --- last-admin guard: sole admin can be neither deleted nor demoted ---
            String delGuard = postFormLocation(base, "/admin/users/delete", "username=admin", admin);
            Assert.assertTrue("deleting the last admin is rejected, got " + delGuard,
                    delGuard != null && delGuard.startsWith("/admin/users?error="));
            String demoteGuard = postFormLocation(base, "/admin/users/role", "username=admin&role=user", admin);
            Assert.assertTrue("demoting the last admin is rejected, got " + demoteGuard,
                    demoteGuard != null && demoteGuard.startsWith("/admin/users?error="));
            Assert.assertEquals("admin session still valid after rejected self-demote",
                    200, get(base, "/admin/users", admin));

            // --- role change: promotion invalidates live sessions, takes effect on re-login ---
            assertRedirect("admin promotes bob", postForm(base, "/admin/users/role",
                    "username=bob&role=admin", admin));
            assertRedirect("bob's old session is dead after role change", get(base, "/admin", bob));
            bob = login(base, "bob", "bob-pw");
            Assert.assertNotNull("bob re-login after promotion", bob);
            Assert.assertEquals("bob is now an admin (can open user management)",
                    200, get(base, "/admin/users", bob));

            // --- password reset: credential dropped, next login sets a new one ---
            assertRedirect("admin resets alice's password", postForm(base, "/admin/users/reset-password",
                    "username=alice", admin));
            assertRedirect("alice's old session is dead after reset", get(base, "/admin", alice));
            Assert.assertNotNull("alice logs in with a NEW password (first-login again)",
                    login(base, "alice", "brand-new-pw"));

            // --- delete: roster entry + credential gone, sessions dead ---
            assertRedirect("admin deletes alice", postForm(base, "/admin/users/delete",
                    "username=alice", admin));
            Assert.assertNull("deleted user cannot log in", login(base, "alice", "brand-new-pw"));
            Assert.assertFalse("users page no longer lists alice",
                    getBody(base, "/admin/users", admin).contains("alice"));

            // --- self-delete is allowed when another admin remains ---
            assertRedirect("admin deletes their own account (bob is admin)",
                    postForm(base, "/admin/users/delete", "username=admin", admin));
            assertRedirect("admin's session is dead after self-delete", get(base, "/admin", admin));
            Assert.assertNull("deleted admin cannot log in", login(base, "admin", "admin-pw"));
            String finalPage = getBody(base, "/admin/users", bob);
            Assert.assertTrue("bob (sole remaining admin) still manages users", finalPage.contains("bob"));
            Assert.assertFalse(finalPage.contains("admin ("));
        } finally {
            server.stop();
        }
    }

    @Test
    public void initialAndTempPasswordsCloseTheFirstLoginWindow() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-temppw").toFile();
        writeRoster(dataDir, "[{\"username\":\"admin\",\"role\":\"admin\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String admin = login(base, "admin", "admin-pw");
            Assert.assertNotNull(admin);

            // Add with an initial password: the account is NOT claimable by first login.
            assertRedirect("add bob with initial password", postForm(base, "/admin/users/add",
                    "username=bob&role=user&password=" + enc("bob-initial"), admin));
            Assert.assertNull("a different password must not claim the account",
                    login(base, "bob", "attacker-pw"));
            String bob = login(base, "bob", "bob-initial");
            Assert.assertNotNull("the admin-chosen initial password logs in", bob);

            // Reset WITH a temp password: old fails, temp works, no claimable window.
            assertRedirect("reset bob with temp password", postForm(base, "/admin/users/reset-password",
                    "username=bob&password=" + enc("bob-temp"), admin));
            Assert.assertNull("old password rejected after reset", login(base, "bob", "bob-initial"));
            Assert.assertNull("arbitrary password must not claim after temp reset",
                    login(base, "bob", "attacker-pw"));
            Assert.assertNotNull("temp password logs in", login(base, "bob", "bob-temp"));

            // Reset BLANK: legacy first-login flow returns (documented, operator's choice).
            assertRedirect("blank reset", postForm(base, "/admin/users/reset-password",
                    "username=bob", admin));
            Assert.assertNotNull("first login provisions again after blank reset",
                    login(base, "bob", "fresh-pw"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void selfServicePasswordChangeRoute() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-selfpw").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String alice = login(base, "alice", "old-pw");
            String aliceOther = login(base, "alice", "old-pw");   // a second live session
            Assert.assertNotNull(alice);
            Assert.assertNotNull(aliceOther);

            Assert.assertEquals("password page reachable for a regular user",
                    200, get(base, "/admin/password", alice));
            Assert.assertTrue("nav offers the password link",
                    getBody(base, "/admin", alice).contains("href='/admin/password'"));

            String[] wrongCurrent = postFormWithBody(base, "/admin/password",
                    "currentPassword=nope&newPassword=x1&confirmPassword=x1", alice);
            Assert.assertEquals("200", wrongCurrent[0]);
            Assert.assertTrue(wrongCurrent[1].contains("Current password is incorrect"));

            String[] mismatch = postFormWithBody(base, "/admin/password",
                    "currentPassword=" + enc("old-pw") + "&newPassword=x1&confirmPassword=x2", alice);
            Assert.assertEquals("200", mismatch[0]);
            Assert.assertTrue(mismatch[1].contains("do not match"));

            assertRedirect("valid change", postForm(base, "/admin/password",
                    "currentPassword=" + enc("old-pw") + "&newPassword=" + enc("new-pw")
                    + "&confirmPassword=" + enc("new-pw"), alice));
            Assert.assertNull("old password rejected", login(base, "alice", "old-pw"));
            Assert.assertNotNull("new password accepted", login(base, "alice", "new-pw"));
            Assert.assertEquals("the changing session stays alive", 200, get(base, "/admin", alice));
            assertRedirect("the user's OTHER session is logged out", get(base, "/admin", aliceOther));
        } finally {
            server.stop();
        }
    }

    @Test
    public void corruptCredentialsRefuseAllLogins() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-corruptcreds").toFile();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        File usersDir = new File(dataDir, "users");
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(usersDir, "credentials.json")), "UTF-8");
        try {
            w.write("{ not json ]");
        } finally {
            w.close();
        }
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            // Fail-closed: before this, a corrupt credentials file silently emptied the credential
            // store and every account fell back into the claimable first-login state.
            Assert.assertNull("login must be refused while credentials.json is corrupt",
                    login(base, "alice", "any-password"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void handEditedRosterChangesApplyToLiveSessions() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-handedit").toFile();
        writeRoster(dataDir, "[{\"username\":\"admin\",\"role\":\"admin\"},"
                + "{\"username\":\"alice\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            String admin = login(base, "admin", "admin-pw");
            Assert.assertEquals("admin session opens user management", 200, get(base, "/admin/users", admin));

            // Hand-demote the admin in users.json (documented operator path — no UI involved).
            // Deliberately the SAME byte length as the original roster (admin demoted -1 char,
            // alice promoted +1 char) and written within the same timestamp tick: an mtime/length
            // cache would miss this revocation — the cache must compare content.
            writeRoster(dataDir, "[{\"username\":\"admin\",\"role\":\"user\"},"
                    + "{\"username\":\"alice\",\"role\":\"admin\"}]");
            assertForbidden("demoted-by-hand admin loses admin powers on the next request",
                    get(base, "/admin/users", admin));
            Assert.assertEquals("but stays logged in as a regular user", 200, get(base, "/admin", admin));

            // Hand-REMOVE the user entirely: the session dies on the next request.
            writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"admin\"}]");
            assertRedirect("removed-by-hand user's session is gone", get(base, "/admin", admin));
        } finally {
            server.stop();
        }
    }

    @Test
    public void concurrentViewersNeverSeeEachOthersIdentity() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-viewer-race").toFile();
        writeRoster(dataDir, "[{\"username\":\"aaa_alice\",\"role\":\"user\"},"
                + "{\"username\":\"zzz_bob\",\"role\":\"user\"}]");
        TeeBoxServer server = startServer(dataDir);
        final String base = "http://127.0.0.1:" + server.getPort();
        try {
            final String alice = login(base, "aaa_alice", "a-pw");
            final String bob = login(base, "zzz_bob", "b-pw");
            Assert.assertNotNull(alice);
            Assert.assertNotNull(bob);

            // The renderer used to keep the viewer identity in singleton fields; under the server's
            // concurrent pool, one user's page could render with the other's name/role. Hammer the
            // dashboard from both sessions in parallel and assert no cross-contamination (with no
            // scripts/runs, the only username on the page is the viewer's own, in the nav).
            final java.util.concurrent.atomic.AtomicReference<String> failure =
                    new java.util.concurrent.atomic.AtomicReference<String>();
            Thread ta = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < 30 && failure.get() == null; i++) {
                            String body = getBody(base, "/admin", alice);
                            if (!body.contains("aaa_alice") || body.contains("zzz_bob")) {
                                failure.compareAndSet(null, "alice's page rendered with bob's identity");
                            }
                        }
                    } catch (IOException e) {
                        failure.compareAndSet(null, "alice request failed: " + e.getMessage());
                    }
                }
            });
            Thread tb = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < 30 && failure.get() == null; i++) {
                            String body = getBody(base, "/admin", bob);
                            if (!body.contains("zzz_bob") || body.contains("aaa_alice")) {
                                failure.compareAndSet(null, "bob's page rendered with alice's identity");
                            }
                        }
                    } catch (IOException e) {
                        failure.compareAndSet(null, "bob request failed: " + e.getMessage());
                    }
                }
            });
            ta.start();
            tb.start();
            ta.join(30000);
            tb.join(30000);
            Assert.assertNull(String.valueOf(failure.get()), failure.get());
        } finally {
            server.stop();
        }
    }

    @Test
    public void openModeHasNoUserManagement() throws Exception {
        File dataDir = Files.createTempDirectory("teebox-usermgmt-open").toFile();
        TeeBoxServer server = startServer(dataDir);   // no roster: UI fully open
        String base = "http://127.0.0.1:" + server.getPort();
        try {
            Assert.assertFalse("open mode shows no Users menu",
                    getBody(base, "/admin", null).contains("href='/admin/users'"));
            Assert.assertEquals("open mode GET /admin/users is unavailable",
                    409, get(base, "/admin/users", null));
            Assert.assertEquals("open mode POST add is unavailable",
                    409, postForm(base, "/admin/users/add", "username=x&role=admin", null));
        } finally {
            server.stop();
        }
    }

    private String getBody(String base, String path, String cookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        if (cookie != null) {
            conn.setRequestProperty("Cookie", "teebox-session=" + cookie);
        }
        int code = conn.getResponseCode();
        java.io.InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            in.close();
        }
        conn.disconnect();
        return out.toString("UTF-8");
    }

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

    /** POST returning {status, responseBody} (reads the error stream on 4xx/5xx). */
    private String[] postFormWithBody(String base, String path, String body, String cookie) throws IOException {
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
        java.io.InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            in.close();
        }
        conn.disconnect();
        return new String[] {String.valueOf(code), buf.toString("UTF-8")};
    }

    /** POST asserting a 3xx response and returning its Location header. */
    private String postFormLocation(String base, String path, String body, String cookie) throws IOException {
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
        Assert.assertTrue("expected 3xx redirect, got " + code, code >= 300 && code < 400);
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        return location;
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
