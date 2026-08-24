package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates embedded schemas against authority-visible schema set.
 *
 * <p>Each role-visible schema must be embedded at schemas/<id> in the JAR,
 * and extra schema entries under schemas/ not in the visible set are rejected.</p>
 *
 * <p>Package-private.</p>
 */
final class CapabilityStudioGateASchemaValidator {

    private CapabilityStudioGateASchemaValidator() {}

    /**
     * Schema validation result.
     */
    static final class SchemaValidationResult {
        final Map<String, byte[]> embeddedSchemas;
        final List<String> missingSchemas;
        final List<String> mismatchedSchemas;
        final Map<String, String> schemaFingerprints;
        final List<String> extraSchemas;

        SchemaValidationResult(Map<String, byte[]> embeddedSchemas,
                               List<String> missingSchemas,
                               List<String> mismatchedSchemas,
                               Map<String, String> schemaFingerprints,
                               List<String> extraSchemas) {
            this.embeddedSchemas = embeddedSchemas;
            this.missingSchemas = missingSchemas;
            this.mismatchedSchemas = mismatchedSchemas;
            this.schemaFingerprints = schemaFingerprints;
            this.extraSchemas = extraSchemas;
        }

        boolean isValid() {
            return missingSchemas.isEmpty()
                    && mismatchedSchemas.isEmpty()
                    && extraSchemas.isEmpty();
        }
    }

    /**
     * Validates embedded schemas against authority-visible schema set.
     *
     * <p>Validates:
     * <ul>
     *   <li>Every visible schema is present at schemas/&lt;id&gt;</li>
     *   <li>Each embedded schema is valid UTF-8 JSON object</li>
     *   <li>Schema pins match if provided (no invented pins)</li>
     *   <li>No extra schema entries under schemas/ outside visible set</li>
     * </ul>
     *
     * @param artifactEntries JAR entries (name -> raw bytes)
     * @param visibleSchemaIds schema IDs visible to this role (subset of gateASchemas)
     * @param schemaPins expected SHA-256 pins (schemaId -> bare sha256 hex, no prefix)
     * @param authoritySchemaPrefix prefix for schema paths in JAR (e.g., "schemas/")
     * @return validation result
     */
    static SchemaValidationResult validate(
            Map<String, byte[]> artifactEntries,
            List<String> visibleSchemaIds,
            Map<String, String> schemaPins,
            String authoritySchemaPrefix) {

        Map<String, byte[]> embeddedSchemas = new LinkedHashMap<>();
        List<String> missingSchemas = new ArrayList<>();
        List<String> mismatchedSchemas = new ArrayList<>();
        Map<String, String> schemaFingerprints = new LinkedHashMap<>();
        List<String> extraSchemas = new ArrayList<>();

        Set<String> entryNames = artifactEntries.keySet();
        Set<String> visibleSet = Set.copyOf(visibleSchemaIds);

        // Check for extra schema entries not in visible set
        for (String entryName : entryNames) {
            if (!entryName.startsWith(authoritySchemaPrefix)) {
                continue;
            }
            String schemaId = entryName.substring(authoritySchemaPrefix.length());
            if (!schemaId.isEmpty() && !visibleSet.contains(schemaId)) {
                extraSchemas.add(schemaId);
            }
        }

        // Validate each visible schema
        for (String schemaId : visibleSchemaIds) {
            String schemaPath = authoritySchemaPrefix + schemaId;

            if (!entryNames.contains(schemaPath)) {
                missingSchemas.add(schemaId);
                continue;
            }

            byte[] schemaBytes = artifactEntries.get(schemaPath);

            // Validate UTF-8 encoding
            try {
                String text = new String(schemaBytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                mismatchedSchemas.add(schemaId + ":INVALID_UTF8");
                continue;
            }

            // Validate JSON object (strict: must be {, not array or primitive)
            try {
                Object parsed = StrictJsonParser.parse(schemaBytes);
                if (!(parsed instanceof Map)) {
                    mismatchedSchemas.add(schemaId + ":NOT_JSON_OBJECT");
                    continue;
                }
            } catch (CapabilityStudioGateAException e) {
                mismatchedSchemas.add(schemaId + ":INVALID_JSON");
                continue;
            }

            // Compute bare sha256 (no sha256: prefix)
            String bareSha256 = sha256Hex(schemaBytes);
            schemaFingerprints.put(schemaId, bareSha256);

            // Verify pin if provided (no invented pins)
            String expectedPin = schemaPins.get(schemaId);
            if (expectedPin != null && !expectedPin.equals(bareSha256)) {
                mismatchedSchemas.add(schemaId + ":PIN_MISMATCH");
            }

            embeddedSchemas.put(schemaId, schemaBytes);
        }

        return new SchemaValidationResult(embeddedSchemas, missingSchemas, mismatchedSchemas,
                schemaFingerprints, extraSchemas);
    }

    /**
     * Computes bare SHA-256 hex string (64 lowercase hex chars, no prefix).
     */
    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available");
        }
    }
}
