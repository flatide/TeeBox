package com.flatide.teebox;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Identifiers for what TeeBox creates at run time: runs, debug sessions, the per-process prefixes
 * of task ids and the host instance id.
 *
 * <p>The time part makes an id readable and roughly ordered. It does not make it unique: two
 * requests in one millisecond are ordinary, and a clock can be set back. Uniqueness comes from
 * the random part — and, where an id names a record, from the registry refusing a second record
 * under an id it already holds (see {@link RunRegistry#registerIfAbsent}).
 */
final class UniqueIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private UniqueIds() {
    }

    /** {@code <prefix><now, in timePattern><separator><2 x randomBytes hex digits>}. */
    static String timestamped(String prefix, String timePattern, String separator, int randomBytes) {
        return prefix + new SimpleDateFormat(timePattern, Locale.ENGLISH).format(new Date())
            + separator + randomHex(randomBytes);
    }

    static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        char[] hex = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            hex[i * 2] = HEX[(random[i] >> 4) & 0x0f];
            hex[i * 2 + 1] = HEX[random[i] & 0x0f];
        }
        return new String(hex);
    }
}
