package com.flatide.tests;

import com.flatide.teebox.AdminSessionManager;
import com.flatide.teebox.UserStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;

public class AdminSessionManagerTest {

    private File newDataDir() throws Exception {
        return Files.createTempDirectory("teebox-session-test").toFile();
    }

    private UserStore rosterStore(String json) throws Exception {
        File dataDir = newDataDir();
        File usersDir = new File(dataDir, "users");
        usersDir.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(usersDir, "users.json")), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
        return new UserStore(dataDir);
    }

    @Test
    public void openModeWhenNoRoster() throws Exception {
        UserStore store = new UserStore(newDataDir());
        AdminSessionManager mgr = new AdminSessionManager(store);
        Assert.assertFalse(mgr.isLoginRequired());
        Assert.assertNull(mgr.login("anyone", "whatever"));
    }

    @Test
    public void firstLoginSetsPasswordThenEnforces() throws Exception {
        UserStore store = rosterStore("[{\"username\":\"alice\",\"role\":\"user\"}]");
        AdminSessionManager mgr = new AdminSessionManager(store);
        Assert.assertTrue(mgr.isLoginRequired());

        // First login: the entered password is registered.
        String token = mgr.login("alice", "chosen-pass");
        Assert.assertNotNull(token);
        Assert.assertTrue(store.hasPassword("alice"));

        AdminSessionManager.Session session = mgr.getSession(token);
        Assert.assertNotNull(session);
        Assert.assertEquals("alice", session.username);
        Assert.assertEquals(UserStore.ROLE_USER, session.role);
        Assert.assertFalse(session.isAdmin());

        // Subsequent login must match the registered password.
        Assert.assertNull(mgr.login("alice", "wrong"));
        Assert.assertNotNull(mgr.login("alice", "chosen-pass"));
    }

    @Test
    public void adminRoleReflectedInSession() throws Exception {
        UserStore store = rosterStore("[{\"username\":\"boss\",\"role\":\"admin\"}]");
        AdminSessionManager mgr = new AdminSessionManager(store);
        String token = mgr.login("boss", "pw");
        AdminSessionManager.Session session = mgr.getSession(token);
        Assert.assertTrue(session.isAdmin());
        Assert.assertEquals(UserStore.ROLE_ADMIN, session.role);
    }

    @Test
    public void unknownUserRejected() throws Exception {
        UserStore store = rosterStore("[{\"username\":\"alice\",\"role\":\"user\"}]");
        AdminSessionManager mgr = new AdminSessionManager(store);
        Assert.assertNull(mgr.login("mallory", "pw"));
    }

    @Test
    public void logoutInvalidatesSession() throws Exception {
        UserStore store = rosterStore("[{\"username\":\"alice\",\"role\":\"user\"}]");
        AdminSessionManager mgr = new AdminSessionManager(store);
        String token = mgr.login("alice", "pw");
        Assert.assertTrue(mgr.isValidSession(token));
        mgr.logout(token);
        Assert.assertFalse(mgr.isValidSession(token));
        Assert.assertNull(mgr.getSession(token));
    }

    @Test
    public void expiredSessionRejected() throws Exception {
        UserStore store = rosterStore("[{\"username\":\"alice\",\"role\":\"user\"}]");
        AdminSessionManager mgr = new AdminSessionManager(store, 1L); // 1ms timeout
        String token = mgr.login("alice", "pw");
        Thread.sleep(10);
        Assert.assertNull(mgr.getSession(token));
        Assert.assertFalse(mgr.isValidSession(token));
    }
}
