package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, capability-studio-neutral provenance for one governed Scenario compilation.
 *
 * <p>The compiler owns this value as server-derived metadata. It is intentionally a typed
 * protocol value rather than an arbitrary caller map so the same exact source closure can be
 * carried into FixtureBundle, TestCase, and TestSuite content-addressed material.</p>
 */
public record ScenarioGovernedCompilationProvenance(
        String schemaVersion,
        String sourceMapFingerprint,
        List<ExactRef> exactRefs) {

    /** Current provenance protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.scenarioGovernedCompilationProvenance.v1";

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<ExactRef> EXACT_REF_ORDER = Comparator
            .comparing(ExactRef::kind)
            .thenComparing(ExactRef::id)
            .thenComparingLong(ExactRef::revision)
            .thenComparing(ExactRef::fingerprint)
            .thenComparing(ref -> ref.scope().tenantId())
            .thenComparing(ref -> ref.scope().organizationId())
            .thenComparing(ref -> ref.scope().projectId())
            .thenComparing(ref -> ref.scope().environmentId())
            .thenComparing(ref -> ref.scope().region())
            .thenComparing(ExactRef::authority);

    /** Normalizes and deeply freezes the provenance closure in canonical order. */
    public ScenarioGovernedCompilationProvenance {
        schemaVersion = required("schemaVersion", schemaVersion);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported governed compilation provenance schema");
        }
        sourceMapFingerprint = requiredFingerprint("sourceMapFingerprint", sourceMapFingerprint);
        if (exactRefs == null) {
            throw new IllegalArgumentException("exactRefs is required");
        }
        LinkedHashSet<ExactRef> unique = new LinkedHashSet<>();
        for (ExactRef exactRef : exactRefs) {
            unique.add(Objects.requireNonNull(exactRef, "exactRefs must not contain null"));
        }
        List<ExactRef> sorted = new ArrayList<>(unique);
        sorted.sort(EXACT_REF_ORDER);
        exactRefs = List.copyOf(sorted);
    }

    /**
     * Returns the explicit empty provenance used only by the legacy compiler signature.
     * Empty does not mean unvalidated: the schema and empty source-map fingerprint remain exact.
     */
    public static ScenarioGovernedCompilationProvenance empty(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new ScenarioGovernedCompilationProvenance(
                SCHEMA_VERSION,
                ProtocolFingerprint.ofBounded(mapper, Map.of(), MAX_PROTOCOL_BYTES),
                List.of());
    }

    /** Computes the canonical fingerprint of this immutable provenance value. */
    public String fingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(mapper, this, MAX_PROTOCOL_BYTES);
    }

    /** @return whether this is the explicit compatibility sentinel used by legacy signatures */
    public boolean empty() {
        return exactRefs.isEmpty();
    }

    /** Exact source coordinate carried into governed content-addressed assets. */
    public record ExactRef(
            String kind,
            String id,
            long revision,
            String fingerprint,
            Scope scope,
            String authority) {

        public ExactRef {
            kind = required("exactRef.kind", kind);
            id = required("exactRef.id", id);
            if (revision < 1) {
                throw new IllegalArgumentException("exactRef.revision must be positive");
            }
            fingerprint = requiredFingerprint("exactRef.fingerprint", fingerprint);
            scope = Objects.requireNonNull(scope, "exactRef.scope is required");
            authority = required("exactRef.authority", authority);
        }
    }

    /** Complete enterprise scope for an exact source coordinate. */
    public record Scope(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region) {

        public Scope {
            tenantId = required("scope.tenantId", tenantId);
            organizationId = required("scope.organizationId", organizationId);
            projectId = required("scope.projectId", projectId);
            environmentId = required("scope.environmentId", environmentId);
            region = required("scope.region", region);
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String requiredFingerprint(String name, String value) {
        String normalized = required(name, value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a sha256 fingerprint");
        }
        return normalized;
    }
}
