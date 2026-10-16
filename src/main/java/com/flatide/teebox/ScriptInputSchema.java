package com.flatide.teebox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable, descriptive JSON Schema for a version's props. Never validates or modifies runs. */
@JsonAdapter(ScriptInputSchema.Adapter.class)
public final class ScriptInputSchema {
    public static final int MAX_LENGTH = 65536;
    private static final int MAX_DEPTH = 32;
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array", "null");
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private final JsonObject schema;

    private ScriptInputSchema(JsonObject schema) {
        this.schema = schema.deepCopy();
    }

    /** A blank editor or JSON null means no definition. An empty object is a defined schema. */
    public static ScriptInputSchema parse(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > MAX_LENGTH) throw invalid("must be at most " + MAX_LENGTH + " characters");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("contains trailing content");
            return fromJson(value);
        } catch (IOException | com.google.gson.JsonParseException e) {
            throw invalid("must be valid JSON");
        }
    }

    public static ScriptInputSchema fromJson(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) throw invalid("must be an object");
        checkDepth(value, 0);
        if (value.toString().length() > MAX_LENGTH) throw invalid("is too large");
        JsonObject object = value.getAsJsonObject();
        if (object.has("type") && !isString(object.get("type"), "object")) {
            throw invalid("root type must be object");
        }
        checkSchema(object);
        return new ScriptInputSchema(object);
    }

    public JsonObject toJson() {
        return schema.deepCopy();
    }

    public ScriptInputSchema copy() {
        return new ScriptInputSchema(schema);
    }

    public String prettyJson() {
        String pretty = JSON.toJson(schema);
        // A valid compact API definition must remain saveable after opening it in the editor.
        return pretty.length() <= MAX_LENGTH ? pretty : schema.toString();
    }

    private static void checkDepth(JsonElement value, int depth) {
        if (depth > MAX_DEPTH) throw invalid("nesting exceeds " + MAX_DEPTH);
        if (value.isJsonObject()) {
            for (JsonElement child : value.getAsJsonObject().asMap().values()) checkDepth(child, depth + 1);
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) checkDepth(child, depth + 1);
        }
    }

    // Validate common definition shapes; other JSON Schema keywords are retained for consumers.
    // This is deliberately not a JSON Schema validator, and never resolves external references.
    private static void checkSchema(JsonElement value) {
        if (isBoolean(value)) return; // JSON Schema permits boolean subschemas.
        if (!value.isJsonObject()) throw invalid("subschemas must be objects or booleans");
        JsonObject object = value.getAsJsonObject();
        for (String key : new String[] {"title", "description", "$schema", "$id", "$ref"}) {
            if (object.has(key) && !isString(object.get(key))) throw invalid(key + " must be a string");
        }
        if (object.has("type")) {
            JsonElement type = object.get("type");
            if (type.isJsonArray()) {
                Set<String> seen = new HashSet<>();
                if (type.getAsJsonArray().isEmpty()) throw invalid("type cannot be empty");
                for (JsonElement item : type.getAsJsonArray()) {
                    checkType(item);
                    if (!seen.add(item.getAsString())) throw invalid("type contains duplicates");
                }
            } else {
                checkType(type);
            }
        }
        for (String key : new String[] {"properties", "$defs", "patternProperties"}) {
            if (object.has(key)) {
                if (!object.get(key).isJsonObject()) throw invalid(key + " must be an object");
                for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject(key).entrySet()) {
                    checkSchema(entry.getValue());
                }
            }
        }
        for (String key : new String[] {"items", "additionalProperties", "not", "if", "then", "else"}) {
            if (object.has(key)) checkSchema(object.get(key));
        }
        for (String key : new String[] {"allOf", "anyOf", "oneOf", "prefixItems"}) {
            if (object.has(key)) {
                if (!object.get(key).isJsonArray()) throw invalid(key + " must be an array");
                for (JsonElement item : object.getAsJsonArray(key)) checkSchema(item);
            }
        }
        for (String key : new String[] {"required", "enum", "examples"}) {
            if (object.has(key) && !object.get(key).isJsonArray()) throw invalid(key + " must be an array");
        }
        if (object.has("enum") && object.getAsJsonArray("enum").isEmpty()) throw invalid("enum cannot be empty");
        if (object.has("required")) {
            Set<String> names = new HashSet<>();
            for (JsonElement item : object.getAsJsonArray("required")) {
                if (!isString(item) || !names.add(item.getAsString())) {
                    throw invalid("required must contain unique strings");
                }
            }
        }
    }

    private static void checkType(JsonElement value) {
        if (!isString(value) || !TYPES.contains(value.getAsString())) throw invalid("unknown type");
    }

    private static boolean isBoolean(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean isString(JsonElement value, String expected) {
        return isString(value) && expected.equals(value.getAsString());
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("inputSchema " + message);
    }

    /** Keep literal null defaults/examples and numeric precision through disk/API serialization. */
    public static final class Adapter extends TypeAdapter<ScriptInputSchema> {
        @Override
        public void write(JsonWriter out, ScriptInputSchema value) throws IOException {
            boolean previous = out.getSerializeNulls();
            out.setSerializeNulls(true);
            try {
                JSON.getAdapter(JsonElement.class).write(out, value.schema);
            } finally {
                out.setSerializeNulls(previous);
            }
        }

        @Override
        public ScriptInputSchema read(JsonReader in) {
            return ScriptInputSchema.fromJson(JsonParser.parseReader(in));
        }
    }
}
