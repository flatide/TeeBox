package com.propertee.teebox;

import java.util.Locale;

public final class DurationParser {
    private DurationParser() {
    }

    public static long parseMillis(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Duration value is required");
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) {
            throw new IllegalArgumentException("Duration value is required");
        }

        long multiplier = 1L;
        String numberPart = value;
        if (value.endsWith("ms")) {
            numberPart = value.substring(0, value.length() - 2);
        } else if (value.endsWith("s")) {
            numberPart = value.substring(0, value.length() - 1);
            multiplier = 1000L;
        } else if (value.endsWith("m")) {
            numberPart = value.substring(0, value.length() - 1);
            multiplier = 60L * 1000L;
        } else if (value.endsWith("h")) {
            numberPart = value.substring(0, value.length() - 1);
            multiplier = 60L * 60L * 1000L;
        } else if (value.endsWith("d")) {
            numberPart = value.substring(0, value.length() - 1);
            multiplier = 24L * 60L * 60L * 1000L;
        }

        if (numberPart.length() == 0) {
            throw new IllegalArgumentException("Invalid duration value: " + raw);
        }

        long amount = Long.parseLong(numberPart);
        return Math.multiplyExact(amount, multiplier);
    }
}
