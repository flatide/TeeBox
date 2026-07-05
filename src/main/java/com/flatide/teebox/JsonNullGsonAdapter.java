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
 * <p>Register on any Gson instance that serializes a run result value tree (currently: the
 * API-response Gson, the webhook Gson, and {@code RunStore}'s persistence Gson). The disk round-trip's
 * load side is handled separately (1.10.1): {@code RunStore.parseRun} re-parses the {@code resultData}
 * subtree with the engine's own {@code value/JsonParser}, restoring {@code JsonNull.NULL} and the
 * engine's number shapes — this adapter's {@link #read} only covers fields declared as {@code JsonNull},
 * which generic {@code Object} trees never are.
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
