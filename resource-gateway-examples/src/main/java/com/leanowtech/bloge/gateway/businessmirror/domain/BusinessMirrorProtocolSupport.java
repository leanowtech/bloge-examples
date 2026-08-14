package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Strict scalar, reference, and ordering rules shared by business-mirror protocols. */
final class BusinessMirrorProtocolSupport {
    static final int MAXIMUM_CANONICAL_BYTES = 8 * 1024 * 1024;
    static final int MAXIMUM_REFERENCES = 4_096;
    static final int MAXIMUM_TEXT_ITEMS = 256;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> MUTABLE_KINDS = Set.of("GRAPH_DRAFT", "CAPABILITY_PROPOSAL");

    private BusinessMirrorProtocolSupport() {
    }

    static String version(String value, String expected) {
        String exact = value == null || value.isBlank() ? expected : value.trim();
        if (!expected.equals(exact)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + exact);
        }
        return exact;
    }

    static String identifier(String value, String field) {
        String exact = required(value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    static String required(String value, String field) {
        String exact = normalized(value);
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (exact.length() > 2_048) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return exact;
    }

    static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    static String optionalFingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.isEmpty() && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be blank or a canonical SHA-256 value");
        }
        return exact;
    }

    static String fingerprint(String value, String field) {
        String exact = required(value, field);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    static List<String> normalizedList(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> exact = values.stream()
                .map(BusinessMirrorProtocolSupport::normalized)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (exact.size() > MAXIMUM_TEXT_ITEMS) {
            throw new IllegalArgumentException(field + " exceeds its item limit");
        }
        return exact;
    }

    static MirrorArtifactRef exactRef(MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return value;
    }

    static MirrorArtifactRef optionalRef(MirrorArtifactRef value, String kind, String field) {
        return value == null ? null : exactRef(value, kind, field);
    }

    static List<MirrorArtifactRef> exactRefs(List<MirrorArtifactRef> values,
                                             Set<String> kinds,
                                             String field) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .peek(value -> {
                    if (!kinds.contains(value.kind())) {
                        throw new IllegalArgumentException(field + " contains unsupported kind " + value.kind());
                    }
                })
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.size() > MAXIMUM_REFERENCES || exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return exact;
    }

    static List<MirrorArtifactRef> immutableRefs(List<MirrorArtifactRef> values, String field) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.size() > MAXIMUM_REFERENCES
                || exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        if (exact.stream().anyMatch(value -> MUTABLE_KINDS.contains(value.kind()))) {
            throw new IllegalArgumentException(field + " must not contain mutable authoring artifacts");
        }
        return exact;
    }

    static <T> List<T> sortedUnique(List<T> values,
                                    Comparator<T> comparator,
                                    Function<T, ?> identity,
                                    String field) {
        List<T> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .sorted(comparator)
                .toList();
        if (exact.size() > MAXIMUM_REFERENCES
                || exact.stream().map(identity).distinct().count() != exact.size()) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return exact;
    }

    static boolean sameScope(CapabilitySnapshot.Scope left, CapabilitySnapshot.Scope right) {
        return Objects.equals(left, right);
    }

    static String upper(String value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }
}
