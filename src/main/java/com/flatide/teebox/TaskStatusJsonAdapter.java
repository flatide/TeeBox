package com.flatide.teebox;

import com.flatide.task.TaskStatus;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;

/**
 * Gson (de)serializer for {@link TaskStatus} keyed on its lowercase wire value ({@code TaskStatus.value()}).
 *
 * <p>ProperTee v2's {@code TaskStatus} dropped the gson {@code @SerializedName} annotations that v1 carried,
 * so by default gson maps only by the (uppercase) enum constant name — and reads legacy
 * {@code "status":"running"} metadata as {@code null}. This adapter restores v1 compatibility: it reads the
 * lowercase value form AND the uppercase name (case-insensitively), and writes the lowercase value so new
 * metadata matches the v1 wire format. An unknown/blank status deserializes to {@code null} (recovery then
 * treats a null status as not-yet-terminal).
 */
final class TaskStatusJsonAdapter implements JsonSerializer<TaskStatus>, JsonDeserializer<TaskStatus> {

    @Override
    public JsonElement serialize(TaskStatus src, Type type, JsonSerializationContext ctx) {
        return src == null ? JsonNull.INSTANCE : new JsonPrimitive(src.value());
    }

    @Override
    public TaskStatus deserialize(JsonElement el, Type type, JsonDeserializationContext ctx) {
        if (el == null || el.isJsonNull()) return null;
        String raw = el.getAsString();
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (TaskStatus status : TaskStatus.values()) {
            if (status.value().equalsIgnoreCase(s) || status.name().equalsIgnoreCase(s)) {
                return status;
            }
        }
        return null;   // unknown legacy/garbled value -> null; recovery reconciles it
    }
}
