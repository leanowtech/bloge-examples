package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-only JSON canonicalizer and hash provider for role-self-test receipts.
 *
 * <p>Implements exact Python-equivalent canonicalization:</p>
 * <ul>
 *   <li>{@code json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)}</li>
 *   <li>{@code SHA256(domain_bytes + NUL + canonical_bytes)}</li>
 * </ul>
 *
 * <p>Package-private.</p>
 */
final class CapabilityStudioGateAReceiptCanonicalizer {

    private CapabilityStudioGateAReceiptCanonicalizer() {
    }

    /**
     * Canonical UTF-8 bytes for a JSON-compatible value.
     * Matches Python: {@code json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)}
     */
    static byte[] canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        canonicalize(value, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Raw SHA-256 fingerprint: {@code "sha256:" + hex(sha256(raw))}
     */
    static String rawFingerprint(byte[] raw) {
        byte[] digest = sha256(raw);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return "sha256:" + hex;
    }

    /**
     * Committed fingerprint: SHA256(domainAscii + NUL + canonical(value))
     */
    static String committed(String domain, Object value) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] canonicalBytes = canonical(value);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalBytes.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalBytes, 0, combined, domainBytes.length + 1, canonicalBytes.length);
        byte[] digest = sha256(combined);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /**
     * Typed raw fingerprint dict: {kind:"RAW_BYTES", algorithm:"SHA-256", value:"sha256:..."}
     */
    static Map<String, Object> typedRaw(byte[] raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "RAW_BYTES");
        result.put("algorithm", "SHA-256");
        result.put("value", rawFingerprint(raw));
        return result;
    }

    /**
     * Typed tree commitment fingerprint dict: {kind:"TREE_COMMITMENT", algorithm:"SHA-256", value:"sha256:..."}
     */
    static Map<String, Object> typedTree(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "TREE_COMMITMENT");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + value);
        return result;
    }

    /**
     * Self-null receipt fingerprint dict: {kind:"SELF_NULL_RECEIPT", algorithm:"SHA-256", value:"sha256:...", selfNullField:"receiptFingerprint"}
     */
    static Map<String, Object> typedSelfNull(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "SELF_NULL_RECEIPT");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + value);
        result.put("selfNullField", "receiptFingerprint");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    // Canonicalization helpers
    // ─────────────────────────────────────────────────────────────────

    private static void canonicalize(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            appendNumber(sb, (Number) value);
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof List) {
            appendArray(sb, (List<?>) value);
        } else if (value instanceof Map) {
            appendObject(sb, (Map<?, ?>) value);
        } else {
            throw new CapabilityStudioGateAException("UNSUPPORTED_VALUE_TYPE:" + value.getClass().getName());
        }
    }

    private static void appendNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new CapabilityStudioGateAException("NON_FINITE_NUMBER");
        }
        sb.append(n.toString());
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static void appendArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            canonicalize(list.get(i), sb);
        }
        sb.append(']');
    }

    @SuppressWarnings("unchecked")
    private static void appendObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new CapabilityStudioGateAException("NON_STRING_KEY");
            }
            keys.add((String) key);
        }
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            String key = keys.get(i);
            appendString(sb, key);
            sb.append(':');
            canonicalize(map.get(key), sb);
        }
        sb.append('}');
    }

    /**
     * Tree commitment: sort entries by relativePath, then SHA256(domain + NUL + canonical(entries)).
     *
     * @param entries list of {relativePath, byteLength, rawFingerprint}
     * @param domain ASCII domain string
     * @return bare sha256 hex string
     */
    static String treeCommitment(List<Map<String, Object>> entries, String domain) {
        List<Map<String, Object>> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            String pA = (String) a.get("relativePath");
            String pB = (String) b.get("relativePath");
            return pA.compareTo(pB);
        });
        return committed(domain, sorted);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new CapabilityStudioGateAException("FINGERPRINT_HASH_ERROR", e);
        }
    }
}
