package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Payload-free, content-addressed comparison of simulation and implementation acceptance runs. */
public record CapabilityImplementationConformanceReport(
        String schemaVersion,
        String conformanceId,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef proposalDraftRef,
        MirrorArtifactRef simulationEvidenceRef,
        MirrorArtifactRef implementationBindingRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef graphRef,
        List<MirrorArtifactRef> acceptanceSuiteRefs,
        Status status,
        List<CaseComparison> cases,
        Instant startedAt,
        Instant completedAt,
        List<String> limitations,
        List<String> uncertainties
) {
    /** Current implementation-conformance report protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityImplementationConformanceReport.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Aggregate same-suite result. */
    public enum Status {
        PASSED,
        FAILED
    }

    /** Whether one exact acceptance Case preserved its observable semantic result. */
    public enum Comparison {
        MATCH,
        MISMATCH
    }

    /** Payload-free implementation evidence embedded so its exact reference never dangles. */
    public record ImplementationEvidence(
            String schemaVersion,
            String runId,
            String fingerprint,
            String status,
            String semanticResultFingerprint,
            String planFingerprint,
            String fixtureFingerprint,
            String assertionSummaryFingerprint,
            String nodeTraceFingerprint,
            String edgeTraceFingerprint,
            Instant startedAt,
            Instant completedAt
    ) {
        /** Current embedded implementation-evidence protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.capabilityImplementationTestEvidence.v1";

        /** Enforces a payload-free exact evidence identity. */
        public ImplementationEvidence {
            schemaVersion = version(schemaVersion, SCHEMA_VERSION);
            runId = required(runId, "runId");
            fingerprint = optionalFingerprint(fingerprint, "fingerprint");
            status = required(status, "status");
            semanticResultFingerprint = requiredFingerprint(
                    semanticResultFingerprint, "semanticResultFingerprint");
            planFingerprint = requiredFingerprint(planFingerprint, "planFingerprint");
            fixtureFingerprint = requiredFingerprint(fixtureFingerprint, "fixtureFingerprint");
            assertionSummaryFingerprint = requiredFingerprint(
                    assertionSummaryFingerprint, "assertionSummaryFingerprint");
            nodeTraceFingerprint = requiredFingerprint(
                    nodeTraceFingerprint, "nodeTraceFingerprint");
            edgeTraceFingerprint = requiredFingerprint(
                    edgeTraceFingerprint, "edgeTraceFingerprint");
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            if (completedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("implementation evidence time is invalid");
            }
        }

        /** @return content-addressed embedded implementation evidence */
        public ImplementationEvidence seal(ObjectMapper mapper) {
            ImplementationEvidence material = withFingerprint("");
            return material.withFingerprint(ProtocolFingerprint.ofBounded(
                    Objects.requireNonNull(mapper, "mapper"), material, 16 * 1024 * 1024));
        }

        /** Recomputes and verifies the embedded evidence content address. */
        public void verify(ObjectMapper mapper) {
            if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
                throw new IllegalArgumentException("implementation evidence fingerprint mismatch");
            }
        }

        /** @return exact reference to the embedded implementation evidence */
        public MirrorArtifactRef artifactRef() {
            if (fingerprint.isBlank()) {
                throw new IllegalStateException("implementation evidence is not sealed");
            }
            return new MirrorArtifactRef("IMPLEMENTATION_TEST_EVIDENCE", runId, 1, fingerprint);
        }

        private ImplementationEvidence withFingerprint(String value) {
            return new ImplementationEvidence(schemaVersion, runId, value, status,
                    semanticResultFingerprint, planFingerprint, fixtureFingerprint,
                    assertionSummaryFingerprint, nodeTraceFingerprint, edgeTraceFingerprint,
                    startedAt, completedAt);
        }
    }

    /** One exact Suite/Case comparison. */
    public record CaseComparison(
            String caseId,
            TestSuite.CaseType caseType,
            MirrorArtifactRef suiteRef,
            MirrorArtifactRef fixtureRef,
            MirrorArtifactRef baselineMirrorEvidenceBundleRef,
            ImplementationEvidence implementationEvidence,
            String baselineRunStatus,
            String baselineSemanticResultFingerprint,
            String baselineBehaviorFingerprint,
            String implementationBehaviorFingerprint,
            Comparison comparison,
            int baselineTargetCallCount,
            int implementationTargetCallCount,
            List<String> targetInvocationSiteIds,
            List<String> mismatchReasons,
            List<String> limitations
    ) {
        /** Rejects incomplete, duplicated, or payload-bearing comparison coordinates. */
        public CaseComparison {
            caseId = required(caseId, "caseId");
            caseType = Objects.requireNonNull(caseType, "caseType");
            suiteRef = requireKind(suiteRef, "TEST_SUITE", "suiteRef");
            fixtureRef = requireKind(fixtureRef, "FIXTURE_BUNDLE", "fixtureRef");
            baselineMirrorEvidenceBundleRef = requireKind(
                    baselineMirrorEvidenceBundleRef, "MIRROR_EVIDENCE_BUNDLE",
                    "baselineMirrorEvidenceBundleRef");
            implementationEvidence = Objects.requireNonNull(
                    implementationEvidence, "implementationEvidence");
            baselineRunStatus = required(baselineRunStatus, "baselineRunStatus");
            baselineSemanticResultFingerprint = requiredFingerprint(
                    baselineSemanticResultFingerprint, "baselineSemanticResultFingerprint");
            baselineBehaviorFingerprint = requiredFingerprint(
                    baselineBehaviorFingerprint, "baselineBehaviorFingerprint");
            implementationBehaviorFingerprint = requiredFingerprint(
                    implementationBehaviorFingerprint, "implementationBehaviorFingerprint");
            comparison = Objects.requireNonNull(comparison, "comparison");
            if (baselineTargetCallCount < 0 || implementationTargetCallCount < 0
                    || baselineTargetCallCount > 100_000
                    || implementationTargetCallCount > 100_000) {
                throw new IllegalArgumentException("target call count is out of bounds");
            }
            targetInvocationSiteIds = strings(targetInvocationSiteIds, 100_000);
            mismatchReasons = strings(mismatchReasons, 256);
            limitations = strings(limitations, 256);
            if (targetInvocationSiteIds.isEmpty()
                    || comparison == Comparison.MATCH && (!mismatchReasons.isEmpty()
                    || baselineTargetCallCount == 0
                    || baselineTargetCallCount != implementationTargetCallCount
                    || !baselineBehaviorFingerprint.equals(implementationBehaviorFingerprint))
                    || comparison == Comparison.MISMATCH && mismatchReasons.isEmpty()) {
                throw new IllegalArgumentException("case comparison is inconsistent");
            }
        }
    }

    /** Enforces complete Suite coverage and a status derived from Case comparisons. */
    public CapabilityImplementationConformanceReport {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        conformanceId = required(conformanceId, "conformanceId");
        fingerprint = optionalFingerprint(fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        proposalDraftRef = requireKind(
                proposalDraftRef, "CAPABILITY_PROPOSAL_DRAFT", "proposalDraftRef");
        simulationEvidenceRef = requireKind(
                simulationEvidenceRef, "PROPOSAL_SIMULATION_EVIDENCE", "simulationEvidenceRef");
        implementationBindingRef = requireKind(
                implementationBindingRef, "PROPOSAL_IMPLEMENTATION_BINDING",
                "implementationBindingRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        graphRef = requireKind(graphRef, "GRAPH_DRAFT", "graphRef");
        acceptanceSuiteRefs = refs(acceptanceSuiteRefs, "TEST_SUITE");
        status = Objects.requireNonNull(status, "status");
        cases = cases == null ? List.of() : cases.stream()
                .map(value -> Objects.requireNonNull(value, "case"))
                .sorted(Comparator.comparing(CaseComparison::suiteRef,
                                Comparator.comparing(MirrorArtifactRef::id)
                                        .thenComparingLong(MirrorArtifactRef::revision))
                        .thenComparing(CaseComparison::caseId))
                .toList();
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        limitations = strings(limitations, 256);
        uncertainties = strings(uncertainties, 256);
        if (acceptanceSuiteRefs.isEmpty() || acceptanceSuiteRefs.size() > 256
                || cases.isEmpty() || cases.size() > 4096
                || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("implementation conformance report is incomplete");
        }
        Set<MirrorArtifactRef> coveredSuites = cases.stream()
                .map(CaseComparison::suiteRef).collect(java.util.stream.Collectors.toSet());
        if (!coveredSuites.equals(Set.copyOf(acceptanceSuiteRefs))) {
            throw new IllegalArgumentException("conformance report does not cover every suite");
        }
        Set<String> coordinates = new LinkedHashSet<>();
        if (cases.stream().anyMatch(value -> !coordinates.add(
                value.suiteRef().fingerprint() + "\u0000" + value.caseId()))) {
            throw new IllegalArgumentException("conformance Case coordinates must be unique");
        }
        boolean allMatch = cases.stream()
                .allMatch(value -> value.comparison() == Comparison.MATCH);
        if ((status == Status.PASSED) != allMatch) {
            throw new IllegalArgumentException("conformance status must be derived from Cases");
        }
    }

    /** @return exact content-addressed report reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("implementation conformance report is not sealed");
        }
        return new MirrorArtifactRef(
                "IMPLEMENTATION_CONFORMANCE_REPORT", conformanceId, 1, fingerprint);
    }

    /** @return content-addressed payload-free report */
    public CapabilityImplementationConformanceReport seal(ObjectMapper mapper) {
        CapabilityImplementationConformanceReport material = withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), material, 32 * 1024 * 1024));
    }

    /** Verifies the report and every embedded evidence fingerprint. */
    public void verify(ObjectMapper mapper) {
        cases.forEach(value -> value.implementationEvidence().verify(mapper));
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("implementation conformance fingerprint mismatch");
        }
    }

    private CapabilityImplementationConformanceReport withFingerprint(String value) {
        return new CapabilityImplementationConformanceReport(schemaVersion, conformanceId, value,
                scope, proposalDraftRef, simulationEvidenceRef, implementationBindingRef,
                targetCapabilityRef, graphRef, acceptanceSuiteRefs, status, cases, startedAt,
                completedAt, limitations, uncertainties);
    }

    private static List<MirrorArtifactRef> refs(List<MirrorArtifactRef> values, String kind) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> requireKind(value, kind, "reference"))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (new LinkedHashSet<>(exact).size() != exact.size()) {
            throw new IllegalArgumentException("references must be unique");
        }
        return exact;
    }

    private static List<String> strings(List<String> values, int maximum) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> exact = List.copyOf(new java.util.TreeSet<>(values.stream()
                .map(value -> required(value, "string item")).toList()));
        if (exact.size() > maximum) {
            throw new IllegalArgumentException("string list exceeds its limit");
        }
        return exact;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String version(String value, String fallback) {
        String exact = value == null || value.isBlank() ? fallback : value.trim();
        if (!fallback.equals(exact)) {
            throw new IllegalArgumentException("unsupported schema version");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank() && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String requiredFingerprint(String value, String field) {
        String exact = optionalFingerprint(value, field);
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return exact;
    }
}
