package com.flatide.tests;

import com.flatide.teebox.ValueDepth;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The look that says whether a value nests deeper than a run's values may: see ValueDepth. */
public class ValueDepthTest {

    @Test
    public void aLevelIsOneObjectOrArray() {
        Assert.assertFalse(ValueDepth.exceeds((Object) null));
        Assert.assertFalse(ValueDepth.exceeds("text"));
        Assert.assertFalse(ValueDepth.exceeds(Integer.valueOf(1)));
        Assert.assertFalse(ValueDepth.exceeds(new LinkedHashMap<String, Object>()));
        Assert.assertFalse(ValueDepth.exceeds(new ArrayList<Object>()));
        Assert.assertFalse(ValueDepth.exceeds((JsonElement) null));
        Assert.assertFalse(ValueDepth.exceeds(JsonParser.parseString("null")));
        Assert.assertFalse(ValueDepth.exceeds(JsonParser.parseString("[1, \"a\", {}]")));
    }

    @Test
    public void aThousandLevelsAreWithinAndOneMoreIsNot() {
        for (boolean lists : new boolean[] {false, true}) {
            Assert.assertFalse(ValueDepth.exceeds(nested(1000, lists)));
            Assert.assertTrue(ValueDepth.exceeds(nested(1001, lists)));
            Assert.assertFalse(ValueDepth.exceeds(JsonParser.parseString(text(1000, lists))));
            Assert.assertTrue(ValueDepth.exceeds(JsonParser.parseString(text(1001, lists))));
        }
    }

    /** The deepest member decides, wherever it is: first, last, or beside shallow ones. */
    @Test
    public void theDeepestMemberDecides() {
        for (int at = 0; at < 3; at++) {
            List<Object> members = new ArrayList<Object>();
            Map<String, Object> fields = new LinkedHashMap<String, Object>();
            for (int i = 0; i < 3; i++) {
                // The list (or the object) is one level itself: 999 below it are within, 1000 are not.
                members.add(i == at ? nested(1000, false) : nested(3, true));
                fields.put("f" + i, i == at ? nested(999, true) : "shallow");
            }
            Assert.assertTrue("a list, deepest member at " + at, ValueDepth.exceeds(members));
            Assert.assertFalse("an object, deepest member at " + at, ValueDepth.exceeds(fields));
        }
        // Wide is not deep.
        List<Object> wide = new ArrayList<Object>();
        for (int i = 0; i < 50_000; i++) {
            wide.add(nested(2, i % 2 == 0));
        }
        Assert.assertFalse(ValueDepth.exceeds(wide));
    }

    /** What the look is for: it says "too deep" of values that nothing that goes down by calls could walk. */
    @Test
    public void aValueFarTooDeepIsSaidToBeThatWithoutRunningOutOfStack() {
        Assert.assertTrue(ValueDepth.exceeds(nested(500_000, false)));
        Assert.assertTrue(ValueDepth.exceeds(nested(500_000, true)));
        Assert.assertTrue(ValueDepth.exceeds(JsonParser.parseString(text(200_000, false))));
        Assert.assertTrue(ValueDepth.exceeds(JsonParser.parseString(text(200_000, true))));
    }

    /** A value nested {@code levels} levels deep, of objects or of lists. */
    private static Object nested(int levels, boolean lists) {
        Object value = lists ? new ArrayList<Object>() : new LinkedHashMap<String, Object>();
        for (int level = 1; level < levels; level++) {
            if (lists) {
                List<Object> outer = new ArrayList<Object>();
                outer.add(value);
                value = outer;
            } else {
                Map<String, Object> outer = new LinkedHashMap<String, Object>();
                outer.put("a", value);
                value = outer;
            }
        }
        return value;
    }

    private static String text(int levels, boolean lists) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < levels; i++) {
            text.append(lists ? "[" : "{\"a\":");
        }
        text.append(lists ? "[]" : "{}");
        for (int i = 1; i < levels; i++) {
            text.append(lists ? "]" : "}");
        }
        return text.toString();
    }
}
