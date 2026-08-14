package com.leanowtech.bloge.gateway.businessmirror.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, payload-free migration preview for one existing built-in Graph.
 *
 * <p>The projection exposes only facts that can be proven from existing authorities. Missing
 * business semantics remain explicit gaps; discovered Contract test suites remain evidence and
 * are never relabelled as owner-governed Scenario packs.</p>
 */
public record LegacyGraphPackageProjection(
        String schemaVersion,
        String projectorVersion,
        MigrationMode migrationMode,
        String graphName,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef sourceGraphRef,
        MirrorArtifactRef sourceContractRef,
        MirrorArtifactRef projectedCapabilityRef,
        MirrorArtifactRef capabilityClosureRef,
        List<MirrorArtifactRef> discoveredTestSuiteRefs,
        DomainCapabilityPackageDraft packageDraft,
        List<Gap> gaps,
        Status status,
        String projectionFingerprint
) {
    public static final String SCHEMA_VERSION =
            "resourceGateway.legacyGraphPackageProjection.v1";
    public static final String PROJECTOR_VERSION = "legacy-graph-package-projector-v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAXIMUM_PROJECTION_BYTES = 8 * 1_048_576;

    public enum MigrationMode {
        LEGACY_IMPORTED
    }

    public enum Status {
        BLOCKED,
        READY_FOR_OWNER_REVIEW
    }

    public enum GapOrigin {
        PACKAGE_READINESS,
        MIGRATION_POLICY
    }

    public enum GapCategory {
        BUSINESS_CONTEXT,
        CONTRACT,
        EXECUTION_MODEL,
        SCENARIO,
        SERVICE_ASSET,
        FIDELITY,
        OUTCOME,
        MIGRATION_TRUST
    }

    public enum GapSeverity {
        BLOCKING,
        WARNING
    }

    /** One stable migration task without customer payload values. */
    public record Gap(
            String code,
            GapOrigin origin,
            GapCategory category,
            GapSeverity severity,
            String draftPath,
            String explanation,
            String requiredAction,
            List<MirrorArtifactRef> evidenceRefs
    ) {
        public Gap {
            code = required(code, "code");
            origin = Objects.requireNonNull(origin, "origin");
            category = Objects.requireNonNull(category, "category");
            severity = Objects.requireNonNull(severity, "severity");
            draftPath = required(draftPath, "draftPath");
            if (!draftPath.startsWith("/")) {
                throw new IllegalArgumentException("draftPath must be a JSON Pointer");
            }
            explanation = required(explanation, "explanation");
            requiredAction = required(requiredAction, "requiredAction");
            evidenceRefs = exactRefs(evidenceRefs, null, "evidenceRefs");
        }
    }

    public LegacyGraphPackageProjection {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        projectorVersion = version(projectorVersion, PROJECTOR_VERSION);
        migrationMode = migrationMode == null ? MigrationMode.LEGACY_IMPORTED : migrationMode;
        graphName = required(graphName, "graphName");
        scope = Objects.requireNonNull(scope, "scope");
        sourceGraphRef = exactRef(sourceGraphRef, "GRAPH_DRAFT", "sourceGraphRef");
        sourceContractRef = exactRef(sourceContractRef, "CONTRACT", "sourceContractRef");
        projectedCapabilityRef = exactRef(
                projectedCapabilityRef, "CAPABILITY", "projectedCapabilityRef");
        capabilityClosureRef = exactRef(
                capabilityClosureRef, "CAPABILITY_CLOSURE", "capabilityClosureRef");
        discoveredTestSuiteRefs = exactRefs(
                discoveredTestSuiteRefs, "TEST_SUITE", "discoveredTestSuiteRefs");
        packageDraft = Objects.requireNonNull(packageDraft, "packageDraft");
        gaps = gaps == null ? List.of() : gaps.stream()
                .map(value -> Objects.requireNonNull(value, "gap"))
                .sorted(Comparator.comparing(Gap::code))
                .toList();
        if (gaps.size() > 256 || gaps.stream().map(Gap::code).distinct().count() != gaps.size()) {
            throw new IllegalArgumentException("gaps must contain unique bounded codes");
        }
        status = status == null ? expectedStatus(gaps) : status;
        projectionFingerprint = projectionFingerprint == null ? "" : projectionFingerprint.trim();
        validateBindings(graphName, scope, sourceGraphRef, sourceContractRef,
                projectedCapabilityRef, capabilityClosureRef, discoveredTestSuiteRefs,
                packageDraft, gaps, status, projectionFingerprint);
    }

    /** Seals an unsigned projection with a deterministic canonical fingerprint. */
    public LegacyGraphPackageProjection seal(ObjectMapper mapper) {
        if (!projectionFingerprint.isBlank()) {
            throw new IllegalStateException("Legacy Graph projection is already sealed");
        }
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                Objects.requireNonNull(mapper, "mapper"), this, MAXIMUM_PROJECTION_BYTES);
        return new LegacyGraphPackageProjection(schemaVersion, projectorVersion, migrationMode,
                graphName, scope, sourceGraphRef, sourceContractRef, projectedCapabilityRef,
                capabilityClosureRef, discoveredTestSuiteRefs, packageDraft, gaps, status,
                fingerprint);
    }

    /** Re-derives the canonical projection fingerprint before transport or import. */
    public void verify(ObjectMapper mapper) {
        if (!FINGERPRINT.matcher(projectionFingerprint).matches()) {
            throw new IllegalArgumentException("Legacy Graph projection fingerprint is invalid");
        }
        LegacyGraphPackageProjection unsigned = new LegacyGraphPackageProjection(
                schemaVersion, projectorVersion, migrationMode, graphName, scope, sourceGraphRef,
                sourceContractRef, projectedCapabilityRef, capabilityClosureRef,
                discoveredTestSuiteRefs, packageDraft, gaps, status, "");
        String expected = VisualBundleFingerprint.fromCanonicalValue(
                Objects.requireNonNull(mapper, "mapper"), unsigned, MAXIMUM_PROJECTION_BYTES);
        if (!expected.equals(projectionFingerprint)) {
            throw new IllegalArgumentException("Legacy Graph projection fingerprint drifted");
        }
    }

    private static void validateBindings(
            String graphName,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef graphRef,
            MirrorArtifactRef contractRef,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef closureRef,
            List<MirrorArtifactRef> testSuiteRefs,
            DomainCapabilityPackageDraft draft,
            List<Gap> gaps,
            Status status,
            String fingerprint) {
        if (!scope.equals(draft.scope())
                || !draft.graphRefs().equals(List.of(graphRef))
                || !Objects.equals(draft.packageContractRef(), contractRef)
                || !draft.capabilityRefs().isEmpty()
                || draft.revision() != 0
                || draft.lifecycle() != DomainCapabilityPackageDraft.Lifecycle.DRAFT) {
            throw new IllegalArgumentException("Legacy Graph Package bindings are inconsistent");
        }
        String expectedGraphId = "built-in:" + graphName;
        if (!expectedGraphId.equals(graphRef.id())
                || !(expectedGraphId + ":contract").equals(contractRef.id())
                || !capabilityRef.id().equals(closureRef.id())) {
            throw new IllegalArgumentException("Legacy Graph authority coordinates are inconsistent");
        }
        Set<MirrorArtifactRef> provenanceRefs = Set.copyOf(draft.provenance().sourceRefs());
        if (!provenanceRefs.containsAll(List.of(
                graphRef, contractRef, capabilityRef, closureRef))
                || !provenanceRefs.containsAll(testSuiteRefs)) {
            throw new IllegalArgumentException("Legacy Graph provenance is incomplete");
        }
        Set<String> readinessCodes = Set.copyOf(draft.readinessBlockers());
        Set<String> projectedReadinessCodes = gaps.stream()
                .filter(value -> value.origin() == GapOrigin.PACKAGE_READINESS)
                .map(Gap::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!readinessCodes.equals(projectedReadinessCodes)
                || gaps.stream().filter(value -> readinessCodes.contains(value.code()))
                .anyMatch(value -> value.severity() != GapSeverity.BLOCKING)) {
            throw new IllegalArgumentException("Legacy Graph readiness gap inventory drifted");
        }
        if (status != expectedStatus(gaps)) {
            throw new IllegalArgumentException("Legacy Graph projection status contradicts gaps");
        }
        if (!fingerprint.isBlank() && !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("projectionFingerprint must be SHA-256");
        }
    }

    private static Status expectedStatus(List<Gap> gaps) {
        return gaps.stream().anyMatch(value -> value.severity() == GapSeverity.BLOCKING)
                ? Status.BLOCKED : Status.READY_FOR_OWNER_REVIEW;
    }

    private static MirrorArtifactRef exactRef(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static List<MirrorArtifactRef> exactRefs(
            List<MirrorArtifactRef> values, String kind, String field) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.size() > 4_096 || exact.stream().distinct().count() != exact.size()
                || kind != null && exact.stream().anyMatch(value -> !kind.equals(value.kind()))) {
            throw new IllegalArgumentException(field + " must contain unique exact refs");
        }
        return exact;
    }

    private static String version(String value, String expected) {
        String exact = value == null || value.isBlank() ? expected : value.trim();
        if (!expected.equals(exact)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + exact);
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return exact;
    }
}
