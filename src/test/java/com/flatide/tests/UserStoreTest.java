package com.flatide.tests;

import com.flatide.teebox.UserStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;

public class UserStoreTest {

    private File newDataDir() throws Exception {
        return Files.createTempDirectory("teebox-userstore-test").toFile();
    }

    private void writeRoster(File dataDir, String json) throws Exception {
        File usersDir = new File(dataDir, "users");
        usersDir.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(usersDir, "users.json")), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
    }

    @Test
    public void passwordHashRoundTrip() throws Exception {
        UserStore store = new UserStore(newDataDir());
        Assert.assertFalse(store.hasPassword("alice"));
        store.setPassword("alice", "s3cret");
        Assert.assertTrue(store.hasPassword("alice"));
        Assert.assertTrue(store.verifyPassword("alice", "s3cret"));
        Assert.assertFalse(store.verifyPassword("alice", "wrong"));
        Assert.assertFalse(store.verifyPassword("alice", null));
        Assert.assertFalse(store.verifyPassword("unknown", "s3cret"));
    }

    @Test
    public void credentialsPersistAcrossReload() throws Exception {
        File dataDir = newDataDir();
        UserStore store = new UserStore(dataDir);
        store.setPassword("bob", "hunter2");

        // New instance reads the persisted credentials.json.
        UserStore reloaded = new UserStore(dataDir);
        Assert.assertTrue(reloaded.hasPassword("bob"));
        Assert.assertTrue(reloaded.verifyPassword("bob", "hunter2"));
        Assert.assertFalse(reloaded.verifyPassword("bob", "hunter3"));
    }

    @Test
    public void rosterLoadNormalizesRole() throws Exception {
        File dataDir = newDataDir();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"},"
                + "{\"username\":\"boss\",\"role\":\"admin\"},"
                + "{\"username\":\"weird\",\"role\":\"superhero\"},"
                + "{\"username\":\"  \",\"role\":\"user\"}]");
        UserStore store = new UserStore(dataDir);

        Assert.assertTrue(store.hasRoster());
        Assert.assertFalse(store.isEmpty());

        List<UserStore.User> users = store.listUsers();
        Assert.assertEquals(3, users.size()); // blank username dropped

        UserStore.User alice = store.findUser("alice");
        Assert.assertNotNull(alice);
        Assert.assertEquals(UserStore.ROLE_USER, alice.role);
        Assert.assertFalse(alice.isAdmin());

        UserStore.User boss = store.findUser("boss");
        Assert.assertTrue(boss.isAdmin());

        // Unknown role normalizes to plain user.
        UserStore.User weird = store.findUser("weird");
        Assert.assertEquals(UserStore.ROLE_USER, weird.role);

        Assert.assertNull(store.findUser("nobody"));
    }

    @Test
    public void noRosterMeansEmpty() throws Exception {
        UserStore store = new UserStore(newDataDir());
        Assert.assertFalse(store.hasRoster());
        Assert.assertTrue(store.isEmpty());
        Assert.assertNull(store.findUser("anyone"));
    }

    @Test
    public void seedAdminCreatesRoster() throws Exception {
        File dataDir = newDataDir();
        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty("root", null);

        Assert.assertTrue(store.hasRoster());
        UserStore.User root = store.findUser("root");
        Assert.assertNotNull(root);
        Assert.assertTrue(root.isAdmin());
        // No password configured => set on first login.
        Assert.assertFalse(store.hasPassword("root"));
    }

    @Test
    public void seedAdminWithPasswordSetsCredential() throws Exception {
        File dataDir = newDataDir();
        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty("root", "initpass");

        Assert.assertTrue(store.hasPassword("root"));
        Assert.assertTrue(store.verifyPassword("root", "initpass"));
    }

    @Test
    public void seedAdminDoesNotClobberExistingRoster() throws Exception {
        File dataDir = newDataDir();
        writeRoster(dataDir, "[{\"username\":\"alice\",\"role\":\"user\"}]");
        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty("root", "initpass");

        // Existing roster preserved; no admin injected.
        Assert.assertNull(store.findUser("root"));
        Assert.assertNotNull(store.findUser("alice"));
    }

    @Test
    public void seedAdminNoOpWithoutAdminUser() throws Exception {
        File dataDir = newDataDir();
        UserStore store = new UserStore(dataDir);
        store.seedAdminIfEmpty(null, null);
        Assert.assertFalse(store.hasRoster());
        Assert.assertTrue(store.isEmpty());
    }

    @Test
    public void malformedRosterFailsClosed() throws Exception {
        File dataDir = newDataDir();
        writeRoster(dataDir, "{ this is not valid json ]");
        UserStore store = new UserStore(dataDir);
        // File exists => login is required, but no valid users can authenticate.
        Assert.assertTrue(store.hasRoster());
        Assert.assertTrue(store.isEmpty());
        Assert.assertNull(store.findUser("alice"));
    }
}
