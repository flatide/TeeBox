package com.propertee.teebox;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory session manager for admin UI authentication.
 * Sessions expire after a configurable timeout (default 8 hours).
 */
public class AdminSessionManager {
    private static final long DEFAULT_SESSION_TIMEOUT_MS = 8L * 60L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String adminUser;
    private final String adminPassword;
    private final long sessionTimeoutMs;
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<String, Long>();

    public AdminSessionManager(String adminUser, String adminPassword) {
        this(adminUser, adminPassword, DEFAULT_SESSION_TIMEOUT_MS);
    }

    public AdminSessionManager(String adminUser, String adminPassword, long sessionTimeoutMs) {
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    /** Returns true if login is required (adminUser and adminPassword are both configured). */
    public boolean isLoginRequired() {
        return adminUser != null && adminUser.length() > 0
            && adminPassword != null && adminPassword.length() > 0;
    }

    /** Authenticate and return session token, or null if invalid. */
    public String login(String user, String password) {
        if (!isLoginRequired()) return null;
        if (adminUser.equals(user) && adminPassword.equals(password)) {
            String token = generateToken();
            sessions.put(token, System.currentTimeMillis() + sessionTimeoutMs);
            return token;
        }
        return null;
    }

    /** Invalidate a session. */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Check if a session token is valid. */
    public boolean isValidSession(String token) {
        if (token == null) return false;
        Long expiry = sessions.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    /** Remove expired sessions (called periodically). */
    public void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue()) {
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
