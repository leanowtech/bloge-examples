package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

/**
 * Shared validator for the application's persisted strong-validator subset.
 *
 * <p>This application subset is intentionally stricter than the V003 database
 * closure: it accepts one quoted printable ASCII value and rejects list and
 * control forms. Quoted content beginning with {@code "W/} is reserved and
 * excluded by this application rule; that wording does not classify it as an
 * HTTP weak validator. An actual HTTP weak value such as {@code W/"etag"} is
 * rejected by the surrounding quoted-value shape as well.</p>
 */
public final class StrongEtag {
    private StrongEtag() { }

    /** @param value candidate persisted validator @return whether it is valid */
    public static boolean isValid(String value) {
        if (value == null || value.length() < 3 || value.length() > 256
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"'
                || value.startsWith("\"W/")) return false;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c != '!' && (c < '#' || c > '~')) return false;
        }
        return true;
    }
}
