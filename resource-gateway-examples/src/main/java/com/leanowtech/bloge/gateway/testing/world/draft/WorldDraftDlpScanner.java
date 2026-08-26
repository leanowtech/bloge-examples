package com.leanowtech.bloge.gateway.testing.world.draft;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Payload-inspection port. It returns only sanitized finding categories. */
@FunctionalInterface
public interface WorldDraftDlpScanner {
    Set<WorldDraftRedactionReport.Finding> scan(Object value, String path, Map<String, Object> schema);

    static WorldDraftDlpScanner failClosedDefault() {
        return (value, path, schema) -> {
            if (value == null) return Set.of();
            String text = value instanceof String string ? string :
                    value instanceof Number number ? number.toString() : "";
            if (text.isBlank()) return Set.of();
            WorldDraftDataClassification classification = classification(schema);
            EnumSet<WorldDraftRedactionReport.Finding> findings = EnumSet.noneOf(
                    WorldDraftRedactionReport.Finding.class);
            if (classification == WorldDraftDataClassification.UNKNOWN
                    || classification == WorldDraftDataClassification.FREE_TEXT) {
                findings.add(WorldDraftRedactionReport.Finding.FREE_TEXT);
            }
            if (classification == WorldDraftDataClassification.IDENTITY || looksLikeIdentity(text)) {
                findings.add(WorldDraftRedactionReport.Finding.IDENTITY);
            }
            if (classification == WorldDraftDataClassification.CONTACT
                    || EMAIL.matcher(text).matches() || PHONE.matcher(text).matches()) {
                findings.add(WorldDraftRedactionReport.Finding.CONTACT);
            }
            if (classification == WorldDraftDataClassification.CREDENTIAL || looksLikeCredential(text)) {
                findings.add(WorldDraftRedactionReport.Finding.CREDENTIAL);
            }
            if (classification == WorldDraftDataClassification.GEOLOCATION) {
                findings.add(WorldDraftRedactionReport.Finding.GEOLOCATION);
            }
            return Set.copyOf(findings);
        };
    }

    private static WorldDraftDataClassification classification(Map<String, Object> schema) {
        if (schema == null) return WorldDraftDataClassification.UNKNOWN;
        Object value = schema.get("x-data-classification");
        if (value == null) value = schema.get("dataClassification");
        return WorldDraftDataClassification.from(value);
    }

    private static boolean looksLikeIdentity(String value) {
        return value.matches("\\d{6,20}") || value.matches("[A-Z][a-z]{1,30}(?: [A-Z][a-z]{1,30})+");
    }

    private static boolean looksLikeCredential(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("bearer ") || lower.contains("basic ")
                || lower.contains("password") || lower.contains("api-key")
                || lower.contains("access-token") || lower.contains("secret")) return true;
        if (!BASE64.matcher(value).matches()) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            return decoded.contains("password") || decoded.contains("secret")
                    || decoded.contains("token") || decoded.contains("credential");
        } catch (IllegalArgumentException ignored) { return false; }
    }

    Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()-]{7,}$");
    Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]{24,}={0,2}$");
}
