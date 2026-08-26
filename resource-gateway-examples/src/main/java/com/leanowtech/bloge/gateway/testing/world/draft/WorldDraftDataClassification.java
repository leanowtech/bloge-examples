package com.leanowtech.bloge.gateway.testing.world.draft;

/** Schema classification used by the redaction boundary and DLP scanner. */
public enum WorldDraftDataClassification {
    PUBLIC, BUSINESS, IDENTITY, CONTACT, CREDENTIAL, GEOLOCATION, FREE_TEXT, UNKNOWN;

    static WorldDraftDataClassification from(Object value) {
        if (!(value instanceof String text)) return UNKNOWN;
        try { return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException ignored) { return UNKNOWN; }
    }
}
