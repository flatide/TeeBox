package com.flatide.teebox;

import com.flatide.propertee2.value.JsonNull;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Serializes the ProperTee engine's first-class {@code null} singleton ({@link JsonNull#NULL}) as JSON
 * {@code null}, preserving the language's {@code null != {}} distinction (spec v0.8.0) across the TeeBox
 * host boundary.
 *
 * <p>Without this, Gson has no adapter for the engine's fieldless {@code JsonNull} class and reflects it
 * into {@code {}} — silently turning a script's explicit {@code null} (a returned value, or a value
 * nested in {@code resultData} like {@code {"coupon": null}}) into {@code {}}, which in ProperTee means
 * "absence". That breaks lossless JSON round-trips for API/webhook consumers.
 *
 * <p>It flips {@code serializeNulls} on <em>only</em> while writing this one value, so a genuine Java
 * {@code null} field elsewhere in the same response is still omitted exactly as before — no global
 * {@code serializeNulls}, which would expose unrelated null fields across every response.
 *
 * <p>Register on any Gson instance that serializes a run result value tree (e.g. the API-response and
 * webhook Gson). Note: this is a serialization-boundary fix; a result persisted to disk and reloaded
 * after a restart still collapses {@code null} (Gson deserializes JSON {@code null} to a Java null, not
 * back to {@code JsonNull.NULL}) — full round-trip would additionally need a reload-side reconstruction.
 */
public final class JsonNullGsonAdapter extends TypeAdapter<JsonNull> {

    @Override
    public void write(JsonWriter out, JsonNull value) throws IOException {
        boolean previous = out.getSerializeNulls();
        out.setSerializeNulls(true);
        try {
            out.nullValue();
        } finally {
            out.setSerializeNulls(previous);
        }
    }

    @Override
    public JsonNull read(JsonReader in) throws IOException {
        in.nextNull();
        return JsonNull.NULL;
    }
}
