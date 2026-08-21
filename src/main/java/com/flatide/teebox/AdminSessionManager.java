package com.flatide.teebox;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session manager for the admin UI, backed by a {@link UserStore} (multi-user roster +
 * password hashes). Sessions carry the authenticated username and role and expire after a
 * configurable timeout (default 8 hours).
 *
 * <p>Login is required only when a roster exists ({@link UserStore#hasRoster()}); with no roster the
 * UI stays open (the historical open-by-default posture). Passwords are set on <i>first login</i>:
 * a roster user with no stored credential has the password they type recorded and hashed.
 */
public class AdminSessionManager {
    private static final long DEFAULT_SESSION_TIMEOUT_MS = 8L * 60L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An authenticated admin-UI session. */
    public static class Session {
        public final String username;
        public final String role;
        final long expiry;

        Session(String username, String role, long expiry) {
            this.username = username;
            this.role = role;
            this.expiry = expiry;
        }

        public boolean isAdmin() {
            return UserStore.ROLE_ADMIN.equals(role);
        }
    }

    private final UserStore userStore;
    private final long sessionTimeoutMs;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    public AdminSessionManager(UserStore userStore) {
        this(userStore, DEFAULT_SESSION_TIMEOUT_MS);
    }

    public AdminSessionManager(UserStore userStore, long sessionTimeoutMs) {
        this.userStore = userStore;
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    /** Returns true if login is required (a user roster exists). */
    public boolean isLoginRequired() {
        return userStore.hasRoster();
    }

    /**
     * Authenticate and return a session token, or null if invalid.
     *
     * <p>The user must exist in the roster. If they have no stored credential yet, this is their
     * first login — the supplied password is hashed and stored, and a session is issued. Otherwise
     * the password is verified against the stored hash.
     */
    public String login(String user, String password) {
        if (!isLoginRequired()) {
            return null;
        }
        if (user == null || password == null || password.length() == 0) {
            return null;
        }
        UserStore.User found = userStore.findUser(user);
        if (found == null) {
            return null;
        }
        if (userStore.hasPassword(found.username)) {
            if (!userStore.verifyPassword(found.username, password)) {
                return null;
            }
        } else {
            // First login: capture and store the password.
            userStore.setPassword(found.username, password);
            TeeBoxLog.info("AdminUI", "First login: password set for user '" + found.username + "'");
        }
        String token = generateToken();
        sessions.put(token, new Session(found.username, found.role, System.currentTimeMillis() + sessionTimeoutMs));
        return token;
    }

    /** Invalidate a session. */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Resolve a session token to its (non-expired) session, or null. */
    public Session getSession(String token) {
        if (token == null) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() > session.expiry) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    /** Check if a session token is valid. */
    public boolean isValidSession(String token) {
        return getSession(token) != null;
    }

    /**
     * Invalidate every live session belonging to a username. Called when an admin deletes a user,
     * changes their role, or resets their password — without this, a deleted user's cookie would
     * stay valid for up to the session timeout, and a role change would not take effect until then.
     */
    public void invalidateUser(String username) {
        if (username == null) {
            return;
        }
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (username.equals(it.next().getValue().username)) {
                it.remove();
            }
        }
    }

    /** Remove expired sessions (called periodically). */
    public void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue().expiry) {
                it.remove();
            }
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
