package com.flatide.teebox.webhook;

import com.flatide.teebox.TeeBoxLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed outbox for {@link WebhookDelivery} records ({@code ${dataDir}/webhooks/}).
 * Mirrors {@code RunStore}: all methods {@code synchronized}, writes are temp-file + atomic rename.
 * MVP keeps no index file (a directory scan is sufficient at expected volumes).
 */
public class WebhookStore {
    private final File dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WebhookStore(File dataDir) {
        this.dir = new File(dataDir, "webhooks");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Failed to create webhooks directory: " + dir.getAbsolutePath());
        }
    }

    public synchronized void save(WebhookDelivery delivery) {
        File target = fileFor(delivery.deliveryId);
        File tmp = new File(dir, delivery.deliveryId + ".json.tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            gson.toJson(delivery, writer);
            writer.close();
            writer = null;
            moveAtomically(tmp.toPath(), target.toPath());
        } catch (IOException e) {
            if (tmp.exists()) {
                tmp.delete();
            }
            throw new RuntimeException("Failed to save webhook delivery " + delivery.deliveryId + ": " + e.getMessage(), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public synchronized WebhookDelivery load(String deliveryId) {
        File file = fileFor(deliveryId);
        if (!file.exists()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            return gson.fromJson(readAll(fis), WebhookDelivery.class);
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public synchronized List<WebhookDelivery> loadAll() {
        List<WebhookDelivery> result = new ArrayList<WebhookDelivery>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(".json") || name.endsWith(".json.tmp")) {
                continue;
            }
            WebhookDelivery d = load(name.substring(0, name.length() - ".json".length()));
            if (d != null) {
                result.add(d);
            }
        }
        return result;
    }

    public synchronized boolean exists(String deliveryId) {
        return fileFor(deliveryId).exists();
    }

    public synchronized void delete(String deliveryId) {
        if (deliveryId == null) return;
        File file = fileFor(deliveryId);
        if (file.exists() && !file.delete() && file.exists()) {
            TeeBoxLog.warn("WebhookStore", "Failed to delete webhook delivery " + deliveryId);
        }
    }

    private File fileFor(String deliveryId) {
        return new File(dir, deliveryId + ".json");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readAll(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len, "UTF-8"));
        }
        return sb.toString();
    }
}
