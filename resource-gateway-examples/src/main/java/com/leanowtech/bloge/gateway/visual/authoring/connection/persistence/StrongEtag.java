package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

/**
 * Shared validator for the persisted strong-validator safe subset.
 *
 * <p>The V003/V010 database closure accepts one quoted ASCII validator and
 * rejects weak, list, control, and oversized values. Keeping this check at
 * the metadata seam prevents the in-memory model, JDBC adapter, and returned
 * value objects from drifting apart.</p>
 */
public final class StrongEtag {
    private StrongEtag() { }

    /**
     * Returns whether the value is one quoted, ASCII, non-list strong
     * validator accepted by the V003/V010 database closure. The database
     * safe subset intentionally rejects every weak/list/control form.
     *
     * @param value candidate persisted validator
     * @return whether the candidate is valid
     */
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
