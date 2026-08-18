package com.leanowtech.bloge.gateway.authoring.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic bounded-wire representation of a governed exact-reference closure.
 *
 * <p>The domain protocol deliberately keeps every ref self-contained. Repeating the same
 * enterprise scope, authority, kind, JSON property names, and {@code sha256:} prefix for a large
 * closure can exceed the 16 KiB registry metadata boundary. This codec dictionaries only those
 * repeated wire values; decoding always reconstructs the complete strongly typed closure before
 * its provenance fingerprint is trusted.</p>
 */
public final class ScenarioGovernedProvenanceMetadataCodec {

    public static final String ENCODING = "dictionary-v1";

    private static final int MAX_REFS = 4_096;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> MANIFEST_KEYS = Set.of(
            "encoding", "scopes", "authorities", "kinds", "refs");

    private ScenarioGovernedProvenanceMetadataCodec() {
    }

    /** Encodes the complete exact-reference closure without dropping any source coordinate. */
    public static Map<String, Object> encodeExactRefs(
            ScenarioGovernedCompilationProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        if (provenance.exactRefs().isEmpty()) {
            throw new IllegalArgumentException("governed exact refs must not be empty");
        }
        if (provenance.exactRefs().size() > MAX_REFS) {
            throw new IllegalArgumentException("governed exact refs exceed the protocol bound");
        }

        LinkedHashMap<ScenarioGovernedCompilationProvenance.Scope, Integer> scopes =
                new LinkedHashMap<>();
        LinkedHashMap<String, Integer> authorities = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> kinds = new LinkedHashMap<>();
        List<List<Object>> rows = new ArrayList<>(provenance.exactRefs().size());
        for (ScenarioGovernedCompilationProvenance.ExactRef ref : provenance.exactRefs()) {
            int scope = index(scopes, ref.scope());
            int authority = index(authorities, ref.authority());
            int kind = index(kinds, ref.kind());
            rows.add(List.of(kind, ref.id(), ref.revision(),
                    ref.fingerprint().substring("sha256:".length()), scope, authority));
        }

        List<List<String>> encodedScopes = scopes.keySet().stream()
                .map(scope -> List.of(scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environmentId(), scope.region()))
                .toList();
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("encoding", ENCODING);
        manifest.put("scopes", encodedScopes);
        manifest.put("authorities", List.copyOf(authorities.keySet()));
        manifest.put("kinds", List.copyOf(kinds.keySet()));
        manifest.put("refs", List.copyOf(rows));
        return Collections.unmodifiableMap(manifest);
    }

    /** Decodes and validates a complete exact-reference closure from stored metadata. */
    public static List<ScenarioGovernedCompilationProvenance.ExactRef> decodeExactRefs(
            Object encoded) {
        if (!(encoded instanceof Map<?, ?> manifest) || !manifest.keySet().equals(MANIFEST_KEYS)
                || !ENCODING.equals(manifest.get("encoding"))) {
            throw new IllegalArgumentException("unsupported governed exact-ref manifest");
        }
        List<ScenarioGovernedCompilationProvenance.Scope> scopes = scopes(manifest.get("scopes"));
        List<String> authorities = strings(manifest.get("authorities"), "authorities");
        List<String> kinds = strings(manifest.get("kinds"), "kinds");
        if (!(manifest.get("refs") instanceof List<?> rows)
                || rows.isEmpty() || rows.size() > MAX_REFS) {
            throw new IllegalArgumentException("governed exact-ref rows are out of bounds");
        }

        List<ScenarioGovernedCompilationProvenance.ExactRef> refs = new ArrayList<>(rows.size());
        for (Object value : rows) {
            if (!(value instanceof List<?> row) || row.size() != 6) {
                throw new IllegalArgumentException("governed exact-ref row has an invalid shape");
            }
            int kind = indexValue(row.get(0), kinds.size(), "kind");
            String id = text(row.get(1), "id");
            long revision = positiveLong(row.get(2), "revision");
            String hash = text(row.get(3), "fingerprint");
            if (!HASH.matcher(hash).matches()) {
                throw new IllegalArgumentException("governed exact-ref fingerprint is invalid");
            }
            int scope = indexValue(row.get(4), scopes.size(), "scope");
            int authority = indexValue(row.get(5), authorities.size(), "authority");
            refs.add(new ScenarioGovernedCompilationProvenance.ExactRef(
                    kinds.get(kind), id, revision, "sha256:" + hash,
                    scopes.get(scope), authorities.get(authority)));
        }
        return List.copyOf(refs);
    }

    /** Returns the number of exact refs after strict decoding, not from untrusted row metadata. */
    public static int exactRefCount(Object encoded) {
        return decodeExactRefs(encoded).size();
    }

    private static List<ScenarioGovernedCompilationProvenance.Scope> scopes(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > MAX_REFS) {
            throw new IllegalArgumentException("governed exact-ref scopes are out of bounds");
        }
        List<ScenarioGovernedCompilationProvenance.Scope> scopes = new ArrayList<>(rows.size());
        for (Object rowValue : rows) {
            if (!(rowValue instanceof List<?> row) || row.size() != 5) {
                throw new IllegalArgumentException("governed exact-ref scope has an invalid shape");
            }
            scopes.add(new ScenarioGovernedCompilationProvenance.Scope(
                    text(row.get(0), "tenantId"), text(row.get(1), "organizationId"),
                    text(row.get(2), "projectId"), text(row.get(3), "environmentId"),
                    text(row.get(4), "region")));
        }
        return List.copyOf(scopes);
    }

    private static List<String> strings(Object value, String field) {
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > MAX_REFS) {
            throw new IllegalArgumentException("governed exact-ref " + field + " are out of bounds");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object entry : values) {
            result.add(text(entry, field));
        }
        return List.copyOf(result);
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("governed exact-ref " + field + " must be text");
        }
        String normalized = text.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("governed exact-ref " + field + " is out of bounds");
        }
        return normalized;
    }

    private static long positiveLong(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("governed exact-ref " + field + " must be numeric");
        }
        long result = wholeLong(number, field);
        if (result < 1) {
            throw new IllegalArgumentException("governed exact-ref " + field + " must be positive");
        }
        return result;
    }

    private static int indexValue(Object value, int size, String field) {
        long index = positiveLongOrZero(value, field);
        if (index >= size) {
            throw new IllegalArgumentException("governed exact-ref " + field + " index is invalid");
        }
        return (int) index;
    }

    private static long positiveLongOrZero(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("governed exact-ref " + field + " index must be numeric");
        }
        long result = wholeLong(number, field);
        if (result < 0) {
            throw new IllegalArgumentException("governed exact-ref " + field + " index is invalid");
        }
        return result;
    }

    private static long wholeLong(Number value, String field) {
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException(
                    "governed exact-ref " + field + " must be a whole long", invalid);
        }
    }

    private static <T> int index(LinkedHashMap<T, Integer> dictionary, T value) {
        Integer existing = dictionary.get(value);
        if (existing != null) {
            return existing;
        }
        int next = dictionary.size();
        dictionary.put(value, next);
        return next;
    }
}
