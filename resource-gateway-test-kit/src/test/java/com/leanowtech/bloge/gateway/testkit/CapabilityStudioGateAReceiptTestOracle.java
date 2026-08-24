package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private test oracle that independently derives the expected role-self-test receipt
 * using Jackson (not the production canonicalizer), for byte-for-byte comparison.
 */
final class CapabilityStudioGateAReceiptTestOracle {

    private static final String ROLE_VIEW_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1";
    private static final String ROLE_INPUT_TREE_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1";
    private static final String SCHEMA_SET_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1";
    private static final String RECEIPT_DOMAIN = "RG-CS-GATE-A-ROLE-SELF-TEST-RECEIPT-v1";

    private CapabilityStudioGateAReceiptTestOracle() {}

    /**
     * Computes the expected receipt bytes using Jackson canonical JSON.
     */
    @SuppressWarnings("unchecked")
    public static byte[] computeExpectedReceipt(
            byte[] authorityRaw,
            byte[] artifactRaw,
            Map<String, byte[]> embeddedSchemas,
            List<String> blackBoxCapabilities,
            int authorityRevision) throws java.io.IOException {

        // Build input tree
        List<Map<String, Object>> inputEntries = new ArrayList<>();

        Map<String, Object> authEntry = new LinkedHashMap<>();
        authEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json");
        authEntry.put("byteLength", (long) authorityRaw.length);
        authEntry.put("rawFingerprint", sha256Hex(authorityRaw));
        inputEntries.add(authEntry);

        Map<String, Object> artifactEntry = new LinkedHashMap<>();
        artifactEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        artifactEntry.put("byteLength", (long) artifactRaw.length);
        artifactEntry.put("rawFingerprint", sha256Hex(artifactRaw));
        inputEntries.add(artifactEntry);

        String inputTreeFingerprint = treeCommitment(inputEntries, ROLE_INPUT_TREE_DOMAIN);

        // Build schema set entries
        List<String> sortedSchemaIds = new ArrayList<>(embeddedSchemas.keySet());
        Collections.sort(sortedSchemaIds);

        List<Map<String, Object>> schemaSetEntries = new ArrayList<>();
        for (String schemaId : sortedSchemaIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("relativePath", "schemas/" + schemaId);
            entry.put("kind", "SCHEMA");
            entry.put("byteLength", (long) embeddedSchemas.get(schemaId).length);
            entry.put("rawFingerprint", sha256Hex(embeddedSchemas.get(schemaId)));
            schemaSetEntries.add(entry);
        }

        String schemaSetFingerprint = treeCommitment(schemaSetEntries, SCHEMA_SET_DOMAIN);

        // Build role view material
        Map<String, Object> roleViewMaterial = new LinkedHashMap<>();
        roleViewMaterial.put("messageVersion", "capability-studio.gate-a.release-authority-bundle.role-view.v1");
        roleViewMaterial.put("role", "IMPLEMENTATION_CANDIDATE");
        roleViewMaterial.put("visibleFileRefs", List.of(
                "role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json",
                "role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar"));
        roleViewMaterial.put("inputTreeFingerprint", inputTreeFingerprint);
        roleViewMaterial.put("forbiddenCapabilities", List.of(
                "ORACLE", "AUTHORITY_WORKSPACE", "REPOSITORY_ROOT", "OTHER_ROLE_INPUTS"));
        roleViewMaterial.put("requiredRuntimeArtifactRoles", List.of());
        roleViewMaterial.put("packagedSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("visibleSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("schemaSetFingerprint", schemaSetFingerprint);

        String roleViewFingerprint = committed(ROLE_VIEW_DOMAIN, roleViewMaterial);

        // Build receipt
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("messageVersion", "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1");
        receipt.put("role", "IMPLEMENTATION_CANDIDATE");
        receipt.put("authority", Map.of(
                "rawFingerprint", typedRaw(authorityRaw),
                "revision", authorityRevision
        ));
        receipt.put("artifactRawFingerprint", typedRaw(artifactRaw));
        receipt.put("profileRawFingerprint", null);
        receipt.put("fixtureSetId", "GATE_A_ROLE_BLACK_BOX_V1");
        receipt.put("capabilities", blackBoxCapabilities);
        receipt.put("status", "READY");
        receipt.put("roleViewFingerprint", typedTree("sha256:" + roleViewFingerprint));
        receipt.put("inputTreeFingerprint", typedTree("sha256:" + inputTreeFingerprint));
        receipt.put("receiptFingerprint", null);

        String receiptFingerprint = committed(RECEIPT_DOMAIN, receipt);
        receipt.put("receiptFingerprint", typedSelfNull(receiptFingerprint));

        return canonical(canonicalize(receipt));
    }

    /**
     * Jackson canonical JSON: sort_keys=true, separators=(",",":").
     */
    @SuppressWarnings("unchecked")
    private static byte[] canonicalize(Object value) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JsonGenerator gen = new JsonFactory().createGenerator(baos)) {
            canonicalize(gen, value);
        }
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void canonicalize(JsonGenerator gen, Object value) throws java.io.IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof Boolean) {
            gen.writeBoolean((Boolean) value);
        } else if (value instanceof Long) {
            gen.writeNumber((Long) value);
        } else if (value instanceof Integer) {
            gen.writeNumber((Integer) value);
        } else if (value instanceof Double) {
            gen.writeNumber((Double) value);
        } else if (value instanceof Float) {
            gen.writeNumber((Float) value);
        } else if (value instanceof Number) {
            gen.writeNumber(((Number) value).doubleValue());
        } else if (value instanceof String) {
            gen.writeString((String) value);
        } else if (value instanceof List) {
            gen.writeStartArray();
            for (Object item : (List<?>) value) {
                canonicalize(gen, item);
            }
            gen.writeEndArray();
        } else if (value instanceof Map) {
            gen.writeStartObject();
            List<String> keys = new ArrayList<>();
            for (Object k : ((Map<?, ?>) value).keySet()) keys.add((String) k);
            Collections.sort(keys);
            for (String key : keys) {
                gen.writeFieldName(key);
                canonicalize(gen, ((Map<?, ?>) value).get(key));
            }
            gen.writeEndObject();
        }
    }

    private static byte[] canonical(byte[] json) {
        return json;
    }

    static String treeCommitment(List<Map<String, Object>> entries, String domain) {
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            String pA = (String) a.get("relativePath");
            String pB = (String) b.get("relativePath");
            return pA.compareTo(pB);
        });
        try {
            return committed(domain, sorted);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String committed(String domain, Object value) throws java.io.IOException {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] canonicalBytes = canonicalize(value);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalBytes.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalBytes, 0, combined, domainBytes.length + 1, canonicalBytes.length);
        return sha256Hex(combined);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> typedRaw(byte[] raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "RAW_BYTES");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + sha256Hex(raw));
        return result;
    }

    static Map<String, Object> typedTree(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "TREE_COMMITMENT");
        result.put("algorithm", "SHA-256");
        result.put("value", value);
        return result;
    }

    static Map<String, Object> typedSelfNull(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "SELF_NULL_RECEIPT");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + value);
        result.put("selfNullField", "receiptFingerprint");
        return result;
    }
}
