package com.flatide.tests;

import com.flatide.teebox.AdminSessionManager;
import com.flatide.teebox.TeeBoxConfig;
import com.flatide.teebox.TeeBoxServer;
import com.flatide.teebox.UserStore;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * The admin UI asks for a login when there is a roster of users, and asks nobody when there is
 * none. "There is none" has to be what was found: a roster that cannot be looked for — its
 * directory closed to the server while it runs — used to count as no roster, and the admin UI of
 * a server that had been asking for a login then served everyone.
 */
public class RosterUnseenTest {

    @Test
    public void aRosterInADirectoryThatWasClosedIsNotTakenForNoRoster() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        UserStore store = new UserStore(dataDir);
        store.addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        AdminSessionManager sessions = new AdminSessionManager(store);
        Assert.assertNotNull(sessions.login("alice", "alice-password"));
        File usersDir = new File(dataDir, "users");

        Assert.assertTrue(usersDir.setExecutable(false));
        try {
            Assert.assertTrue("a roster that cannot be looked for is not \"no roster\"", store.hasRoster());
            Assert.assertTrue(sessions.isLoginRequired());
            Assert.assertNull("nobody can be told from the roster while it cannot be read",
                    sessions.login("alice", "alice-password"));
        } finally {
            usersDir.setExecutable(true);
        }
        Assert.assertTrue(store.hasRoster());
        Assert.assertNotNull(sessions.login("alice", "alice-password"));
    }

    /** What stays as it was: no roster, in a directory that was looked into, is no roster — at any time. */
    @Test
    public void aRosterThatIsNotThereIsNoRoster() throws Exception {
        File dataDir = tempDir();
        UserStore store = new UserStore(dataDir);
        AdminSessionManager sessions = new AdminSessionManager(store);
        Assert.assertFalse(store.hasRoster());
        Assert.assertFalse(sessions.isLoginRequired());

        store.addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        Assert.assertTrue(store.hasRoster());

        // Taken away by the operator while the server runs: the UI is open again, as it was.
        Assert.assertTrue(new File(dataDir, "users/users.json").delete());
        Assert.assertFalse(store.hasRoster());
        Assert.assertFalse(sessions.isLoginRequired());
    }

    /**
     * "No roster" is a roster file that is not in a users directory that is there. When the
     * directory itself is gone — the data directory was moved, or its file system unmounted — it
     * cannot be told whether there is a roster, and the login stays.
     */
    @Test
    public void aUsersDirectoryThatIsGoneIsNotTakenForNoRoster() throws Exception {
        File dataDir = tempDir();
        UserStore store = new UserStore(dataDir);
        store.addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        AdminSessionManager sessions = new AdminSessionManager(store);
        File usersDir = new File(dataDir, "users");
        File moved = new File(dataDir, "users-moved");

        Assert.assertTrue(usersDir.renameTo(moved));
        Assert.assertTrue("the users directory is gone: that is not \"no roster\"", store.hasRoster());
        Assert.assertTrue(sessions.isLoginRequired());
        Assert.assertNull(sessions.login("alice", "alice-password"));

        Assert.assertTrue(moved.renameTo(usersDir));
        Assert.assertNotNull(sessions.login("alice", "alice-password"));
    }

    /** A roster that is there and cannot be read is not an empty one either: the first admin is not put in its place. */
    @Test
    public void aRosterThatCannotBeReadIsNotReplacedByTheFirstAdmin() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        File roster = new File(dataDir, "users/users.json");
        byte[] asItWas = Files.readAllBytes(roster.toPath());

        Assert.assertTrue(roster.setReadable(false));
        try {
            UserStore store = new UserStore(dataDir);
            store.seedAdminIfEmpty("root", "root-password");
            Assert.assertFalse("the first admin got a password over a roster nobody read", store.hasPassword("root"));
        } finally {
            roster.setReadable(true);
        }
        Assert.assertEquals(new String(asItWas, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(roster.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Nor is a roster that is a link leading nowhere — kept on another volume, say, that is not
     * there at this start. The path is taken; what is behind it was not read. Seeding wrote a
     * file of one admin in the link's place, and the roster was not found again when it came back.
     */
    @Test
    public void aRosterThatIsALinkToNowhereIsNotReplacedByTheFirstAdmin() throws Exception {
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        File roster = new File(dataDir, "users/users.json");
        File kept = new File(dataDir, "kept");
        File away = new File(dataDir, "kept-away");
        Assert.assertTrue(kept.mkdirs());
        Assert.assertTrue(roster.renameTo(new File(kept, "users.json")));
        try {
            Files.createSymbolicLink(roster.toPath(), new File(kept, "users.json").toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assume.assumeNoException("symbolic links cannot be made here", e);
        }
        Assert.assertNotNull("a roster behind a link is a roster",
                new AdminSessionManager(new UserStore(dataDir)).login("alice", "alice-password"));

        Assert.assertTrue(kept.renameTo(away));   // the link leads nowhere now
        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty("root", "root-password");
        Assert.assertTrue("the link was replaced by a roster of the first admin", Files.isSymbolicLink(roster.toPath()));
        Assert.assertFalse(store.hasPassword("root"));
        Assert.assertFalse("a roster was made where the link leads", kept.exists());
        Assert.assertTrue("login is still asked for", store.hasRoster());

        Assert.assertTrue(away.renameTo(kept));   // and when it is back, it is the roster again
        Assert.assertNotNull(new AdminSessionManager(new UserStore(dataDir)).login("alice", "alice-password"));
    }

    /**
     * And when what the link leads to comes back while the seeding is under way — right after the
     * roster was asked for and not found behind the link: the seeding goes by that one read. It
     * looked a second time, saw a roster it had not read, took it for an empty one and put the
     * first admin in the link's place.
     */
    @Test
    public void aRosterThatComesBackWhileSeedingIsNotReplacedByTheFirstAdmin() throws Exception {
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        File roster = new File(dataDir, "users/users.json");
        final File kept = new File(dataDir, "kept");
        final File away = new File(dataDir, "kept-away");
        Assert.assertTrue(kept.mkdirs());
        Assert.assertTrue(roster.renameTo(new File(kept, "users.json")));
        try {
            Files.createSymbolicLink(roster.toPath(), new File(kept, "users.json").toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assume.assumeNoException("symbolic links cannot be made here", e);
        }
        Assert.assertTrue(kept.renameTo(away));   // the link leads nowhere

        final boolean[] cameBack = {false};
        UserStore store = new UserStore(dataDir) {
            @Override
            public synchronized java.util.List<User> listUsers() {
                java.util.List<User> users = super.listUsers();
                if (!cameBack[0]) {   // back as soon as the roster has been asked for once
                    cameBack[0] = true;
                    Assert.assertTrue(away.renameTo(kept));
                }
                return users;
            }
        };
        store.seedAdminIfEmpty("root", "root-password");
        Assert.assertTrue("the roster was asked for", cameBack[0]);
        Assert.assertTrue("the link was replaced by a roster of the first admin", Files.isSymbolicLink(roster.toPath()));
        Assert.assertFalse(store.hasPassword("root"));
        Assert.assertNotNull("the roster that came back is the roster",
                new AdminSessionManager(new UserStore(dataDir)).login("alice", "alice-password"));
    }

    /** As a server shows it: the admin UI goes on asking for a login when its roster is closed to it. */
    @Test
    public void theAdminUiStaysBehindItsLoginWhenTheRosterCannotBeLookedFor() throws Exception {
        assumePermissionsApply();
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        File usersDir = new File(dataDir, "users");
        TeeBoxConfig config = new TeeBoxConfig();
        config.bindAddress = "127.0.0.1";
        config.port = 0;
        config.dataDir = dataDir;
        config.maxConcurrentRuns = 1;
        TeeBoxServer server = new TeeBoxServer(config);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getPort();
            Assert.assertEquals("/admin/login", redirectOf(base + "/admin"));
            Assert.assertTrue(usersDir.setExecutable(false));
            try {
                Assert.assertEquals("the admin UI was served to a visitor who had not logged in",
                        "/admin/login", redirectOf(base + "/admin"));
                Assert.assertEquals("/admin/login", redirectOf(base + "/admin/scripts"));
            } finally {
                usersDir.setExecutable(true);
            }
        } finally {
            usersDir.setExecutable(true);
            server.stop();
        }
    }

    /**
     * A roster that was edited by hand and no longer parses is not an empty one: nobody logs in
     * (as before), and the first admin is not seeded over it at the next start. An empty roster
     * is seeded as before.
     */
    @Test
    public void aRosterThatCannotBeParsedIsNotReplacedByTheFirstAdmin() throws Exception {
        String cutShort = "[{\"username\": \"alice\", \"role\": \"admin\"}, {\"username\": \"bob\", \"role\": \"us";
        File dataDir = tempDir();
        new UserStore(dataDir).addUser("alice", UserStore.ROLE_ADMIN, "alice-password");
        File roster = new File(dataDir, "users/users.json");
        Files.write(roster.toPath(), cutShort.getBytes(StandardCharsets.UTF_8));

        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty("root", "root-password");
        Assert.assertEquals(cutShort, new String(Files.readAllBytes(roster.toPath()), StandardCharsets.UTF_8));
        Assert.assertFalse(store.hasPassword("root"));
        Assert.assertNull(new AdminSessionManager(store).login("alice", "alice-password"));

        for (String empty : new String[] {"[]", ""}) {
            File fresh = tempDir();
            Assert.assertTrue(new File(fresh, "users").mkdirs());
            Files.write(new File(fresh, "users/users.json").toPath(), empty.getBytes(StandardCharsets.UTF_8));
            UserStore seeded = new UserStore(fresh);
            seeded.seedAdminIfEmpty("root", "root-password");
            Assert.assertNotNull("[" + empty + "] an empty roster is seeded", seeded.findUser("root"));
        }
    }

    /** Where a GET is sent on to, or its status when it is answered. */
    private static String redirectOf(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Connection", "close");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        try {
            int status = connection.getResponseCode();
            return status == HttpURLConnection.HTTP_MOVED_TEMP ? connection.getHeaderField("Location")
                    : "answered with " + status;
        } finally {
            connection.disconnect();
        }
    }

    private static void assumePermissionsApply() {
        Assume.assumeFalse("needs POSIX permission semantics",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Assume.assumeFalse("root bypasses permissions", "root".equals(System.getProperty("user.name")));
    }

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("teebox-roster-unseen").toFile();
    }
}
