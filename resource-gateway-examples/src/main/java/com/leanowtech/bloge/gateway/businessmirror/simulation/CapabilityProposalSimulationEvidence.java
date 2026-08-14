package com.leanowtech.bloge.gateway.businessmirror.simulation;

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

/** Payload-free aggregate proving how one Proposal revision behaved across its acceptance suite. */
public record CapabilityProposalSimulationEvidence(
        String schemaVersion,
        String simulationId,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef proposalDraftRef,
        MirrorArtifactRef packageRef,
        MirrorArtifactRef graphRef,
        MirrorArtifactRef baseCapabilityClosureRef,
        MirrorArtifactRef simulatedCapabilityClosureRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef temporaryCapabilityRef,
        List<MirrorArtifactRef> acceptanceSuiteRefs,
        Status status,
        List<CaseEvidence> cases,
        Instant startedAt,
        Instant completedAt,
        List<String> limitations,
        List<String> uncertainties
) {
    /** Current Proposal simulation evidence protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityProposalSimulationEvidence.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Aggregate business-acceptance result; both states still represent a completed simulation. */
    public enum Status {
        PASSED,
        FAILED
    }

    /** One acceptance case projected from a signed, payload-free Mirror evidence bundle. */
    public record CaseEvidence(
            String caseId,
            TestSuite.CaseType caseType,
            MirrorArtifactRef suiteRef,
            MirrorArtifactRef fixtureRef,
            MirrorArtifactRef mirrorPlanRef,
            MirrorArtifactRef mirrorEvidenceBundleRef,
            String runStatus,
            List<String> resolverSources,
            List<String> matchedRuleRefs,
            int proposalCallCount,
            List<String> limitations
    ) {
        /** Enforces exact references without admitting test input or output payloads. */
        public CaseEvidence {
            caseId = required(caseId, "caseId");
            caseType = Objects.requireNonNull(caseType, "caseType");
            suiteRef = requireKind(suiteRef, "TEST_SUITE", "suiteRef");
            fixtureRef = requireKind(fixtureRef, "FIXTURE_BUNDLE", "fixtureRef");
            mirrorPlanRef = requireKind(mirrorPlanRef, "MIRROR_PLAN", "mirrorPlanRef");
            mirrorEvidenceBundleRef = requireKind(
                    mirrorEvidenceBundleRef, "MIRROR_EVIDENCE_BUNDLE",
                    "mirrorEvidenceBundleRef");
            runStatus = required(runStatus, "runStatus");
            resolverSources = strings(resolverSources);
            matchedRuleRefs = strings(matchedRuleRefs);
            if (matchedRuleRefs.size() > 100_000
                    || proposalCallCount < 0 || proposalCallCount > 100_000) {
                throw new IllegalArgumentException("case evidence exceeds execution bounds");
            }
            limitations = strings(limitations);
        }
    }

    /** Validates aggregate identity, deterministic order, and terminal time bounds. */
    public CapabilityProposalSimulationEvidence {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        simulationId = required(simulationId, "simulationId");
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        scope = Objects.requireNonNull(scope, "scope");
        proposalDraftRef = requireKind(
                proposalDraftRef, "CAPABILITY_PROPOSAL_DRAFT", "proposalDraftRef");
        packageRef = requireKind(packageRef, "DOMAIN_CAPABILITY_PACKAGE", "packageRef");
        graphRef = requireKind(graphRef, "GRAPH_DRAFT", "graphRef");
        baseCapabilityClosureRef = requireKind(
                baseCapabilityClosureRef, "CAPABILITY_CLOSURE", "baseCapabilityClosureRef");
        simulatedCapabilityClosureRef = requireKind(
                simulatedCapabilityClosureRef, "CAPABILITY_CLOSURE",
                "simulatedCapabilityClosureRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        temporaryCapabilityRef = requireKind(
                temporaryCapabilityRef, "CAPABILITY", "temporaryCapabilityRef");
        acceptanceSuiteRefs = refs(acceptanceSuiteRefs, "TEST_SUITE");
        status = Objects.requireNonNull(status, "status");
        cases = cases == null ? List.of() : cases.stream()
                .map(value -> Objects.requireNonNull(value, "case"))
                .sorted(Comparator.comparing(CaseEvidence::suiteRef,
                                Comparator.comparing(MirrorArtifactRef::id)
                                        .thenComparingLong(MirrorArtifactRef::revision))
                        .thenComparing(CaseEvidence::caseId))
                .toList();
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        limitations = strings(limitations);
        uncertainties = strings(uncertainties);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !fingerprint.isBlank() && !FINGERPRINT.matcher(fingerprint).matches()
                || acceptanceSuiteRefs.isEmpty() || cases.isEmpty()
                || acceptanceSuiteRefs.size() > 256 || cases.size() > 4096
                || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Proposal simulation evidence is incomplete");
        }
        Set<MirrorArtifactRef> suitesWithCases = cases.stream()
                .map(CaseEvidence::suiteRef).collect(java.util.stream.Collectors.toSet());
        if (!suitesWithCases.equals(Set.copyOf(acceptanceSuiteRefs))) {
            throw new IllegalArgumentException(
                    "Proposal simulation evidence does not cover every acceptance suite exactly");
        }
        Set<String> caseCoordinates = new LinkedHashSet<>();
        if (cases.stream().anyMatch(value -> !caseCoordinates.add(
                value.suiteRef().fingerprint() + "\u0000" + value.caseId()))) {
            throw new IllegalArgumentException("Proposal simulation case coordinates must be unique");
        }
        boolean allPassed = cases.stream().allMatch(value -> "PASSED".equals(value.runStatus()));
        int proposalCalls = cases.stream().mapToInt(CaseEvidence::proposalCallCount).sum();
        if (status == Status.PASSED && (!allPassed || proposalCalls == 0)) {
            throw new IllegalArgumentException("Passed Proposal evidence contains a failed case");
        }
    }

    /** @return exact content-addressed aggregate evidence reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("Proposal simulation evidence is not sealed");
        }
        return new MirrorArtifactRef(
                "PROPOSAL_SIMULATION_EVIDENCE", simulationId, 1, fingerprint);
    }

    /** @return content-addressed evidence with no business payload fields */
    public CapabilityProposalSimulationEvidence seal(ObjectMapper mapper) {
        CapabilityProposalSimulationEvidence material = withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), material, 16 * 1024 * 1024));
    }

    /** Verifies the attached content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Proposal simulation evidence fingerprint mismatch");
        }
    }

    private CapabilityProposalSimulationEvidence withFingerprint(String value) {
        return new CapabilityProposalSimulationEvidence(schemaVersion, simulationId, value, scope,
                proposalDraftRef, packageRef, graphRef, baseCapabilityClosureRef,
                simulatedCapabilityClosureRef, targetCapabilityRef, temporaryCapabilityRef,
                acceptanceSuiteRefs, status, cases, startedAt, completedAt, limitations,
                uncertainties);
    }

    private static List<MirrorArtifactRef> refs(List<MirrorArtifactRef> values, String kind) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> requireKind(value, kind, "reference"))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (new LinkedHashSet<>(exact).size() != exact.size()) {
            throw new IllegalArgumentException("evidence references must be unique");
        }
        return exact;
    }

    private static List<String> strings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new java.util.TreeSet<>(values.stream()
                .map(value -> required(value, "string item")).toList()));
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
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }
}
