package com.propertee.teebox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class RuntimePolicy {
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

    private RuntimePolicy() {
    }

    public static void requireNonRoot() {
        if (IS_WINDOWS) {
            return;
        }
        verifyNonRootUid(detectEffectiveUid());
    }

    public static void verifyNonRootUid(int uid) {
        if (uid == 0) {
            throw new IllegalStateException(
                    "TeeBox must not run as root. Run it as a non-privileged user and do not use sudo.");
        }
    }

    static int detectEffectiveUid() {
        Process process = null;
        try {
            process = new ProcessBuilder("id", "-u").start();
            String output = readStream(process.getInputStream()).trim();
            String error = readStream(process.getErrorStream()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Failed to determine effective uid: " + error);
            }
            Integer uid = parseUid(output);
            if (uid == null) {
                throw new IllegalStateException("Failed to parse effective uid from: " + output);
            }
            return uid.intValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while determining effective uid", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to determine effective uid", e);
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (IOException ignore) {}
                try { process.getErrorStream().close(); } catch (IOException ignore) {}
                try { process.getOutputStream().close(); } catch (IOException ignore) {}
            }
        }
    }

    public static Integer parseUid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
