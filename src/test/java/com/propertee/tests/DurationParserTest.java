package com.propertee.tests;

import com.propertee.teebox.DurationParser;
import org.junit.Assert;
import org.junit.Test;

public class DurationParserTest {

    @Test
    public void shouldParseRawMillisecondsForBackwardCompatibility() {
        Assert.assertEquals(500L, DurationParser.parseMillis("500"));
        Assert.assertEquals(86400000L, DurationParser.parseMillis("86400000"));
    }

    @Test
    public void shouldParseDurationSuffixes() {
        Assert.assertEquals(500L, DurationParser.parseMillis("500ms"));
        Assert.assertEquals(3000L, DurationParser.parseMillis("3s"));
        Assert.assertEquals(120000L, DurationParser.parseMillis("2m"));
        Assert.assertEquals(10800000L, DurationParser.parseMillis("3h"));
        Assert.assertEquals(172800000L, DurationParser.parseMillis("2d"));
        Assert.assertEquals(3600000L, DurationParser.parseMillis("1H"));
    }

    @Test
    public void shouldRejectInvalidDurationStrings() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("ms");
        assertInvalid("1w");
        assertInvalid("1.5h");
        assertInvalid("abc");
    }

    private void assertInvalid(String raw) {
        try {
            DurationParser.parseMillis(raw);
            Assert.fail("Expected parse failure for: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
