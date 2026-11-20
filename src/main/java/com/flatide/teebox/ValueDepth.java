package com.flatide.teebox;

import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.LazyLines;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * How deep the values of a run may nest: {@value #MAX} levels of objects and arrays, the depth
 * of a document the language's own {@code JSON_PARSE} reads. A result, an input or a run file
 * that goes deeper is refused where it enters — a result when its run ends, an input when it is
 * submitted, a file when it is loaded.
 *
 * <p>The language itself does not bound it: {@code x = {"a": x}} in a loop nests one level more
 * at every turn (no cycle: an assignment copies). Everything that walks such a value goes down
 * one call per level — the writer of the run's file, the copy made for every answer — and a few
 * thousand levels are more than a thread's stack holds: such a run could be saved when it ended
 * and then neither be shown nor archived, and could keep the server from starting.
 *
 * <p>A level is one object or array: {@code 1}, {@code "a"} and {@code null} are 0 deep,
 * {@code []} and {@code {}} are 1, {@code [[]]} is 2 — as the engine's JSON parser counts. (The
 * Result {@code JSON_PARSE} answers with is an object around what it read, one level more.) The
 * walks here do not themselves go down by calls: they are what must not run out of stack.
 */
public final class ValueDepth {

    /** The most levels of objects and arrays a run's value may nest. */
    public static final int MAX = JsonParser.MAX_DEPTH;

    private ValueDepth() {
    }

    /** As a refusal says it: {@code "nested more than 1000 levels deep"}. */
    public static String tooDeep() {
        return "nested more than " + MAX + " levels deep";
    }

    /**
     * Whether a value as a script holds it — maps, lists and what is in them — nests more than
     * {@link #MAX} levels deep.
     */
    public static boolean exceeds(Object value) {
        if (!isContainer(value)) {
            return false;
        }
        ArrayDeque<Iterator<?>> open = new ArrayDeque<Iterator<?>>();   // one per level that is being walked
        open.push(membersOf(value));
        while (!open.isEmpty()) {
            Iterator<?> members = open.peek();
            if (!members.hasNext()) {
                open.pop();
                continue;
            }
            Object member = members.next();
            if (isContainer(member)) {
                if (open.size() >= MAX) {
                    return true;   // it would be level MAX + 1
                }
                open.push(membersOf(member));
            }
        }
        return false;
    }

    private static boolean isContainer(Object value) {
        return value instanceof Map || value instanceof List;
    }

    private static Iterator<?> membersOf(Object container) {
        // A FILE_LINES array still unread holds only its file's lines, which are strings; walking
        // it would read the file. It is one level, with nothing below it.
        if (container instanceof LazyLines && !((LazyLines) container).isLoaded()) {
            return Collections.emptyIterator();
        }
        if (container instanceof Map) {
            return ((Map<?, ?>) container).values().iterator();
        }
        return ((List<?>) container).iterator();
    }

    /** The same of the parsed text of a file; null (a field that is not there) is not deep. */
    public static boolean exceeds(JsonElement value) {
        if (!isContainer(value)) {
            return false;
        }
        ArrayDeque<Iterator<JsonElement>> open = new ArrayDeque<Iterator<JsonElement>>();
        open.push(membersOf(value));
        while (!open.isEmpty()) {
            Iterator<JsonElement> members = open.peek();
            if (!members.hasNext()) {
                open.pop();
                continue;
            }
            JsonElement member = members.next();
            if (isContainer(member)) {
                if (open.size() >= MAX) {
                    return true;
                }
                open.push(membersOf(member));
            }
        }
        return false;
    }

    private static boolean isContainer(JsonElement value) {
        return value != null && (value.isJsonObject() || value.isJsonArray());
    }

    private static Iterator<JsonElement> membersOf(JsonElement container) {
        if (container.isJsonObject()) {
            final Iterator<Map.Entry<String, JsonElement>> entries = ((JsonObject) container).entrySet().iterator();
            return new Iterator<JsonElement>() {
                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public JsonElement next() {
                    return entries.next().getValue();
                }
            };
        }
        return ((JsonArray) container).iterator();
    }
}
