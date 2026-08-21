package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Multi-user store for the admin UI, backed by two files under {@code dataDir/users/}:
 *
 * <ul>
 *   <li><b>users.json</b> — the roster, an array of {@code {username, role}} objects. This file is
 *       <i>operator-managed</i>: administrators add/remove users by hand-editing it. It is read fresh
 *       on every query, so edits take effect without a server restart.</li>
 *   <li><b>credentials.json</b> — password hashes, managed by TeeBox. A user has no entry here until
 *       they set a password on <i>first login</i>; the plaintext is never stored.</li>
 * </ul>
 *
 * Passwords are hashed with PBKDF2-HMAC-SHA256 (JDK built-in, no external dependency), a per-user
 * random salt, and verified in constant time. This is the {@code /admin} UI identity source only —
 * it is unrelated to the {@code /api/*} Bearer tokens (which stay unrestricted).
 */
public class UserStore {
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private static final String PBKDF2_ALGO = "pbkdf2-sha256";
    private static final int PBKDF2_ITERATIONS = 210000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A roster entry. Deserialized directly from users.json. */
    public static class User {
        public String username;
        public String role; // "admin" or "user"

        public User() {
        }

        public User(String username, String role) {
            this.username = username;
            this.role = role;
        }

        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }
    }

    /** A persisted password hash record (credentials.json value). */
    private static class Credential {
        String algo;
        int iterations;
        String salt; // base64
        String hash; // base64
    }

    private final File usersDir;
    private final File rosterFile;
    private final File credentialsFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** In-memory credential cache, persisted to credentials.json on change. Guarded by {@code this}. */
    private final Map<String, Credential> credentials;

    public UserStore(File dataDir) {
        this.usersDir = new File(dataDir, "users");
        if (!usersDir.exists() && !usersDir.mkdirs()) {
            throw new IllegalStateException("Failed to create users directory: " + usersDir.getAbsolutePath());
        }
        this.rosterFile = new File(usersDir, "users.json");
        this.credentialsFile = new File(usersDir, "credentials.json");
        this.credentials = loadCredentials();
    }

    // ---- Roster (users.json, operator-managed, read fresh) ----

    /** True when a roster file exists — i.e. the operator has opted into UI authentication. */
    public synchronized boolean hasRoster() {
        return rosterFile.exists();
    }

    /** All valid roster entries (empty if the file is missing or unparseable). Read fresh from disk. */
    public synchronized List<User> listUsers() {
        if (!rosterFile.exists()) {
            return new ArrayList<User>();
        }
        try {
            String json = readFile(rosterFile);
            List<User> raw = gson.fromJson(json, new TypeToken<List<User>>() {
            }.getType());
            List<User> out = new ArrayList<User>();
            if (raw == null) {
                return out;
            }
            for (User u : raw) {
                if (u == null || u.username == null || u.username.trim().length() == 0) {
                    continue;
                }
                out.add(new User(u.username.trim(), ROLE_ADMIN.equals(u.role) ? ROLE_ADMIN : ROLE_USER));
            }
            return out;
        } catch (Exception e) {
            // Malformed roster: fail closed (no valid users) rather than silently opening access.
            TeeBoxLog.warn("UserStore", "Failed to parse users.json (treating as no valid users): " + e.getMessage());
            return new ArrayList<User>();
        }
    }

    /** Look up a roster entry by exact username, or null. */
    public synchronized User findUser(String username) {
        if (username == null) {
            return null;
        }
        for (User u : listUsers()) {
            if (u.username.equals(username)) {
                return u;
            }
        }
        return null;
    }

    /** True when no valid users are configured (used for bootstrap seeding). */
    public synchronized boolean isEmpty() {
        return listUsers().isEmpty();
    }

    /**
     * Bootstrap: when the roster is missing/empty and {@code adminUser} is configured, seed a single
     * admin entry. If {@code adminPassword} is also set, hash it as that admin's initial credential
     * (preserving the legacy single-admin login); otherwise the admin sets it on first login.
     * No-op when a non-empty roster already exists (never clobbers operator edits).
     */
    public synchronized void seedAdminIfEmpty(String adminUser, String adminPassword) {
        if (adminUser == null || adminUser.trim().length() == 0) {
            return;
        }
        if (rosterFile.exists() && !listUsers().isEmpty()) {
            return;
        }
        String user = adminUser.trim();
        List<User> seeded = new ArrayList<User>();
        seeded.add(new User(user, ROLE_ADMIN));
        saveRoster(seeded);
        if (adminPassword != null && adminPassword.trim().length() > 0 && !hasPassword(user)) {
            setPassword(user, adminPassword.trim());
        }
        TeeBoxLog.info("UserStore", "Seeded admin user '" + user + "' into roster");
    }

    // ---- Roster management (admin-UI user administration) ----

    /** Username shape for UI-created users. Hand-edited users.json entries are not re-validated. */
    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Add a roster entry. Fails on a duplicate or an invalid username; role normalizes to user. */
    public synchronized void addUser(String username, String role) {
        String user = validateUsername(username);
        List<User> users = listUsers();
        for (User u : users) {
            if (u.username.equals(user)) {
                throw new IllegalArgumentException("User already exists: " + user);
            }
        }
        users.add(new User(user, normalizeRole(role)));
        saveRoster(users);
        TeeBoxLog.info("UserStore", "User added: " + user + " (" + normalizeRole(role) + ")");
    }

    /**
     * Remove a roster entry and its stored credential. The last remaining admin cannot be removed —
     * a roster without an admin would leave no one able to manage users from the UI.
     */
    public synchronized void removeUser(String username) {
        String user = validateUsername(username);
        List<User> users = listUsers();
        User target = null;
        for (User u : users) {
            if (u.username.equals(user)) {
                target = u;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        if (target.isAdmin() && countAdmins(users) <= 1) {
            throw new IllegalArgumentException("Cannot delete the last admin: " + user);
        }
        users.remove(target);
        saveRoster(users);
        if (credentials.remove(user) != null) {
            saveCredentials();
        }
        TeeBoxLog.info("UserStore", "User removed: " + user);
    }

    /** Change a user's role. Demoting the last remaining admin is rejected (see removeUser). */
    public synchronized void setRole(String username, String role) {
        String user = validateUsername(username);
        String newRole = normalizeRole(role);
        List<User> users = listUsers();
        User target = null;
        for (User u : users) {
            if (u.username.equals(user)) {
                target = u;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Unknown user: " + user);
        }
        if (newRole.equals(target.role)) {
            return;
        }
        if (target.isAdmin() && countAdmins(users) <= 1) {
            throw new IllegalArgumentException("Cannot demote the last admin: " + user);
        }
        target.role = newRole;
        saveRoster(users);
        TeeBoxLog.info("UserStore", "Role changed: " + user + " -> " + newRole);
    }

    /** Drop a user's stored credential so their next login sets a new password (first-login flow). */
    public synchronized void clearPassword(String username) {
        String user = validateUsername(username);
        if (credentials.remove(user) != null) {
            saveCredentials();
            TeeBoxLog.info("UserStore", "Password cleared for user: " + user + " (set on next login)");
        }
    }

    private static String normalizeRole(String role) {
        return ROLE_ADMIN.equals(role) ? ROLE_ADMIN : ROLE_USER;
    }

    private static int countAdmins(List<User> users) {
        int n = 0;
        for (User u : users) {
            if (u.isAdmin()) {
                n++;
            }
        }
        return n;
    }

    private static String validateUsername(String username) {
        String user = username != null ? username.trim() : "";
        if (user.length() == 0) {
            throw new IllegalArgumentException("Username is required");
        }
        if (!USERNAME_PATTERN.matcher(user).matches()) {
            throw new IllegalArgumentException(
                    "Invalid username (allowed: letters, digits, '.', '_', '-'; max 64 chars): " + user);
        }
        return user;
    }

    // ---- Credentials (credentials.json, TeeBox-managed) ----

    /** True when a password hash has been recorded for this user. */
    public synchronized boolean hasPassword(String username) {
        return username != null && credentials.containsKey(username);
    }

    /** Hash and persist a new password for the user (first-login provisioning or reset). */
    public synchronized void setPassword(String username, String plain) {
        if (username == null || username.length() == 0) {
            throw new IllegalArgumentException("username is required");
        }
        if (plain == null || plain.length() == 0) {
            throw new IllegalArgumentException("password is required");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plain.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        Credential c = new Credential();
        c.algo = PBKDF2_ALGO;
        c.iterations = PBKDF2_ITERATIONS;
        c.salt = Base64.getEncoder().encodeToString(salt);
        c.hash = Base64.getEncoder().encodeToString(hash);
        credentials.put(username, c);
        saveCredentials();
    }

    /** Constant-time verify of a plaintext password against the stored hash. False if no credential. */
    public synchronized boolean verifyPassword(String username, String plain) {
        if (plain == null) {
            return false;
        }
        Credential c = credentials.get(username);
        if (c == null || c.salt == null || c.hash == null) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(c.salt);
            expected = Base64.getDecoder().decode(c.hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int iterations = c.iterations > 0 ? c.iterations : PBKDF2_ITERATIONS;
        byte[] actual = pbkdf2(plain.toCharArray(), salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }

    private Map<String, Credential> loadCredentials() {
        Map<String, Credential> map = new LinkedHashMap<String, Credential>();
        if (!credentialsFile.exists()) {
            return map;
        }
        try {
            String json = readFile(credentialsFile);
            Map<String, Credential> loaded = gson.fromJson(json,
                    new TypeToken<LinkedHashMap<String, Credential>>() {
                    }.getType());
            if (loaded != null) {
                map.putAll(loaded);
            }
        } catch (Exception e) {
            TeeBoxLog.warn("UserStore", "Failed to parse credentials.json (starting empty): " + e.getMessage());
        }
        return map;
    }

    private void saveCredentials() {
        writeFile(credentialsFile, gson.toJson(credentials));
        restrictPermissions(credentialsFile);
    }

    private void saveRoster(List<User> users) {
        writeFile(rosterFile, gson.toJson(users));
    }

    // ---- file helpers ----

    private String readFile(File file) throws IOException {
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = input.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, len, "UTF-8"));
            }
            return sb.toString();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Atomic write (temp file + rename): a crash mid-write must never leave a truncated users.json —
     * the roster parser fails closed (no valid users), which would lock every operator out of the UI.
     */
    private void writeFile(File file, String content) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temp), "UTF-8");
            writer.write(content);
            writer.close();
            writer = null;
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + file.getAbsolutePath(), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    /** Best-effort: restrict the credentials file to owner read/write only. */
    private void restrictPermissions(File file) {
        try {
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
        } catch (SecurityException ignore) {
            // best effort; not fatal
        }
    }
}
