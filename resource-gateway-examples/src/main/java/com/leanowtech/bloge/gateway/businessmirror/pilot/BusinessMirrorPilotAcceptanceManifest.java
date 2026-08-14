package com.leanowtech.bloge.gateway.businessmirror.pilot;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed index for one customer-owned Business Mirror pilot acceptance decision.
 *
 * <p>The manifest records exact evidence coordinates and derives readiness. It is not the
 * authority for business-owner approval, ANEKE governance, runtime certification, or customer
 * environment facts. Those decisions remain external artifacts referenced by this index.</p>
 */
public record BusinessMirrorPilotAcceptanceManifest(
        String schemaVersion,
        String manifestFingerprint,
        String manifestId,
        long revision,
        CapabilitySnapshot.Scope scope,
        String pilotDomainId,
        MirrorArtifactRef packageSnapshotRef,
        ScenarioDenominator scenarioDenominator,
        List<AcceptanceGate> acceptanceGates,
        ObservationWindow observationWindow,
        CustomerAcceptance customerAcceptance,
        OverallStatus status,
        Instant assembledAt,
        String assembler,
        List<String> limitations
) {
    /** Current pilot acceptance manifest wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.businessMirrorPilotAcceptanceManifest.v1";
    /** Artifact kind used when another immutable object references this manifest. */
    public static final String ARTIFACT_KIND =
            "BUSINESS_MIRROR_PILOT_ACCEPTANCE_MANIFEST";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
    private static final int MAXIMUM_REFS = 4_096;

    private static final Map<GateId, Set<String>> REQUIRED_PASS_EVIDENCE = requiredPassEvidence();
    private static final Map<GateId, GateAuthority> REQUIRED_GATE_AUTHORITY =
            requiredGateAuthority();

    /** Validates the fixed ten-gate denominator and derives the only legal overall status. */
    public BusinessMirrorPilotAcceptanceManifest {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        manifestFingerprint = optionalFingerprint(manifestFingerprint, "manifestFingerprint");
        manifestId = identifier(manifestId, "manifestId");
        if (revision < 1) {
            throw new IllegalArgumentException("manifest revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        pilotDomainId = identifier(pilotDomainId, "pilotDomainId");
        packageSnapshotRef = exactRef(
                packageSnapshotRef, "DOMAIN_CAPABILITY_PACKAGE", "packageSnapshotRef");
        scenarioDenominator = Objects.requireNonNull(
                scenarioDenominator, "scenarioDenominator");
        acceptanceGates = orderedGates(acceptanceGates);
        observationWindow = Objects.requireNonNull(observationWindow, "observationWindow");
        customerAcceptance = Objects.requireNonNull(customerAcceptance, "customerAcceptance");
        status = Objects.requireNonNull(status, "status");
        assembledAt = Objects.requireNonNull(assembledAt, "assembledAt");
        Instant assemblyTime = assembledAt;
        Instant denominatorFrozenAt = scenarioDenominator.frozenAt();
        assembler = identifier(assembler, "assembler");
        limitations = textList(limitations, "limitations");

        if (assembledAt.isBefore(scenarioDenominator.frozenAt())) {
            throw new IllegalArgumentException("manifest predates the frozen scenario denominator");
        }
        MirrorArtifactRef denominatorRef = scenarioDenominator.artifactRef();
        if (!acceptanceGates.get(0).evidenceRefs().contains(packageSnapshotRef)
                || !acceptanceGates.get(0).evidenceRefs().contains(denominatorRef)
                || !acceptanceGates.get(1).evidenceRefs().contains(denominatorRef)) {
            throw new IllegalArgumentException("pilot core evidence references do not close");
        }
        if (acceptanceGates.get(1).state() == GateState.PASSED
                && scenarioDenominator.coveredHighRiskObligationCount()
                != scenarioDenominator.highRiskObligationCount()) {
            throw new IllegalArgumentException(
                    "high-risk branch gate cannot pass with uncovered obligations");
        }
        if (observationWindow.status() == ObservationWindowStatus.COMPLETED
                && !acceptanceGates.get(8).evidenceRefs()
                .contains(observationWindow.authoritativeOutcomePopulationRef())) {
            throw new IllegalArgumentException(
                    "outcome fidelity gate does not reference the observed population");
        }
        if (acceptanceGates.stream().map(AcceptanceGate::assessedAt)
                .filter(Objects::nonNull).anyMatch(assessed -> assessed.isAfter(assemblyTime))
                || observationWindow.actualFrom() != null
                && observationWindow.actualFrom().isAfter(assembledAt)
                || observationWindow.actualTo() != null
                && observationWindow.actualTo().isAfter(assembledAt)
                || customerAcceptance.decidedAt() != null
                && customerAcceptance.decidedAt().isAfter(assembledAt)) {
            throw new IllegalArgumentException("manifest contains future assessment evidence");
        }
        if (acceptanceGates.subList(0, 2).stream().map(AcceptanceGate::assessedAt)
                .filter(Objects::nonNull)
                .anyMatch(assessed -> assessed.isBefore(denominatorFrozenAt))
                || customerAcceptance.decidedAt() != null
                && customerAcceptance.decidedAt().isBefore(denominatorFrozenAt)) {
            throw new IllegalArgumentException(
                    "acceptance decision predates the frozen scenario denominator");
        }
        boolean acceptanceReady = acceptanceGates.stream()
                .allMatch(gate -> gate.state() == GateState.PASSED)
                && observationWindow.status() == ObservationWindowStatus.COMPLETED;
        if (customerAcceptance.status() == CustomerAcceptanceStatus.IN_REVIEW
                && !acceptanceReady) {
            throw new IllegalArgumentException(
                    "customer review cannot start before all gates and observation complete");
        }
        if (customerAcceptance.status() == CustomerAcceptanceStatus.ACCEPTED
                && acceptanceReady
                && customerAcceptance.decidedAt().isBefore(observationWindow.actualTo())) {
            throw new IllegalArgumentException(
                    "customer acceptance decision predates completed observation");
        }
        OverallStatus expected = derivedStatus(customerAcceptance.status(), acceptanceReady);
        if (status != expected) {
            throw new IllegalArgumentException("pilot overall status is not derived from gates");
        }
    }

    /** @return exact immutable manifest reference */
    public MirrorArtifactRef artifactRef() {
        if (manifestFingerprint.isBlank()) {
            throw new IllegalStateException("pilot acceptance manifest is not content-addressed");
        }
        return new MirrorArtifactRef(ARTIFACT_KIND, manifestId, revision, manifestFingerprint);
    }

    /** @return an identical manifest carrying a replacement canonical fingerprint */
    public BusinessMirrorPilotAcceptanceManifest withFingerprint(String fingerprint) {
        return new BusinessMirrorPilotAcceptanceManifest(schemaVersion, fingerprint, manifestId,
                revision, scope, pilotDomainId, packageSnapshotRef, scenarioDenominator,
                acceptanceGates, observationWindow, customerAcceptance, status, assembledAt,
                assembler, limitations);
    }

    /** @return an identical manifest carrying a separately addressed denominator */
    public BusinessMirrorPilotAcceptanceManifest withScenarioDenominator(
            ScenarioDenominator denominator) {
        return new BusinessMirrorPilotAcceptanceManifest(schemaVersion, manifestFingerprint,
                manifestId, revision, scope, pilotDomainId, packageSnapshotRef, denominator,
                acceptanceGates, observationWindow, customerAcceptance, status, assembledAt,
                assembler, limitations);
    }

    /** The ten non-waivable exit gates defined by the Business Mirror blueprint. */
    public enum GateId {
        PACKAGE_DEFINITION_COMPLETE,
        HIGH_RISK_BRANCH_OBLIGATIONS,
        ISOLATED_PROPOSAL_REHEARSAL,
        SAME_SUITE_IMPLEMENTATION_CONFORMANCE,
        ZERO_EXTERNAL_BUSINESS_WRITES,
        EVIDENCE_TRACEABILITY,
        ANEKE_GOVERNANCE_ROUND_TRIP,
        CHANGE_IMPACT_ANALYSIS,
        OUTCOME_FIDELITY_FAIL_CLOSED,
        TARGET_ENVIRONMENT_CERTIFICATION
    }

    /** Evidence state of one gate; no waiver state exists in this protocol. */
    public enum GateState {
        NOT_EVALUATED,
        EVIDENCE_AVAILABLE,
        PASSED,
        FAILED,
        BLOCKED
    }

    /** Authority that must assess a gate and own the cited evidence. */
    public enum GateAuthority {
        RESOURCE_GATEWAY,
        ANEKE,
        CUSTOMER_BUSINESS_OWNER,
        CUSTOMER_PLATFORM
    }

    /** Derived lifecycle of the whole pilot acceptance package. */
    public enum OverallStatus {
        PREPARING,
        READY_FOR_CUSTOMER_VALIDATION,
        CUSTOMER_ACCEPTED,
        CUSTOMER_REJECTED
    }

    /** Lifecycle of the customer-owned outcome observation window. */
    public enum ObservationWindowStatus {
        PLANNED,
        ACTIVE,
        COMPLETED,
        INVALIDATED
    }

    /** State of the external customer acceptance decision. */
    public enum CustomerAcceptanceStatus {
        NOT_REQUESTED,
        IN_REVIEW,
        ACCEPTED,
        REJECTED
    }

    /**
     * One gate result with exact evidence references and explicit blockers.
     *
     * <p>A {@link GateState#PASSED} gate must contain every evidence kind required for that gate.
     * {@code EVIDENCE_AVAILABLE} means material is ready for assessment, not that it passed.</p>
     */
    public record AcceptanceGate(
            GateId gateId,
            GateAuthority authority,
            GateState state,
            List<MirrorArtifactRef> evidenceRefs,
            List<String> reasonCodes,
            Instant assessedAt
    ) {
        /** Validates evidence-state semantics without assuming the external Authority decision. */
        public AcceptanceGate {
            gateId = Objects.requireNonNull(gateId, "gateId");
            authority = Objects.requireNonNull(authority, "authority");
            state = Objects.requireNonNull(state, "state");
            evidenceRefs = exactRefs(evidenceRefs, "evidenceRefs");
            reasonCodes = BusinessMirrorPilotAcceptanceManifest.reasonCodes(reasonCodes);

            if (authority != REQUIRED_GATE_AUTHORITY.get(gateId)) {
                throw new IllegalArgumentException(
                        "gate authority does not match protocol authority for " + gateId);
            }
            if ((state == GateState.PASSED || state == GateState.FAILED)
                    && (assessedAt == null || evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException(
                        "assessed gate requires evidenceRefs and assessedAt");
            }
            if (state == GateState.EVIDENCE_AVAILABLE && evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "EVIDENCE_AVAILABLE gate requires evidenceRefs");
            }
            if (state == GateState.BLOCKED && reasonCodes.isEmpty()) {
                throw new IllegalArgumentException("BLOCKED gate requires reasonCodes");
            }
            if (state == GateState.FAILED && reasonCodes.isEmpty()) {
                throw new IllegalArgumentException("FAILED gate requires reasonCodes");
            }
            if (state == GateState.PASSED && !evidenceKinds(evidenceRefs)
                    .containsAll(REQUIRED_PASS_EVIDENCE.get(gateId))) {
                throw new IllegalArgumentException(
                        "PASSED gate is missing required evidence kinds for " + gateId);
            }
            if ((state == GateState.NOT_EVALUATED || state == GateState.EVIDENCE_AVAILABLE
                    || state == GateState.BLOCKED) && assessedAt != null) {
                throw new IllegalArgumentException(
                        "unassessed gate must not carry assessedAt");
            }
        }
    }

    /**
     * Owner-frozen scenario denominator, including visible unknown range and exact family refs.
     */
    public record ScenarioDenominator(
            String schemaVersion,
            String denominatorFingerprint,
            String denominatorId,
            long revision,
            String frozenBy,
            Instant frozenAt,
            MirrorArtifactRef ownerFreezeAttestationRef,
            int declaredFamilyCount,
            int highRiskObligationCount,
            int coveredHighRiskObligationCount,
            int unknownRangeCount,
            List<MirrorArtifactRef> scenarioFamilyRefs,
            List<MirrorArtifactRef> unknownRangeRefs,
            List<String> limitations
    ) {
        /** Scenario denominator subprotocol version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.businessMirrorPilotScenarioDenominator.v1";
        /** Exact kind used by gate evidence. */
        public static final String ARTIFACT_KIND = "SCENARIO_DENOMINATOR";

        /** Validates owner freeze, declared counts, coverage bounds, and visible unknown refs. */
        public ScenarioDenominator {
            schemaVersion = version(schemaVersion, SCHEMA_VERSION);
            denominatorFingerprint = optionalFingerprint(
                    denominatorFingerprint, "denominatorFingerprint");
            denominatorId = identifier(denominatorId, "denominatorId");
            if (revision < 1) {
                throw new IllegalArgumentException("denominator revision must be positive");
            }
            frozenBy = identifier(frozenBy, "frozenBy");
            frozenAt = Objects.requireNonNull(frozenAt, "frozenAt");
            ownerFreezeAttestationRef = exactRef(ownerFreezeAttestationRef,
                    "BUSINESS_OWNER_FREEZE_ATTESTATION", "ownerFreezeAttestationRef");
            scenarioFamilyRefs = exactRefsOfKind(
                    scenarioFamilyRefs, "SCENARIO_FAMILY", "scenarioFamilyRefs");
            unknownRangeRefs = exactRefsOfKind(
                    unknownRangeRefs, "SCENARIO_UNKNOWN_RANGE", "unknownRangeRefs");
            limitations = textList(limitations, "limitations");
            if (declaredFamilyCount < 1
                    || declaredFamilyCount != scenarioFamilyRefs.size()
                    || highRiskObligationCount < 1
                    || coveredHighRiskObligationCount < 0
                    || coveredHighRiskObligationCount > highRiskObligationCount
                    || unknownRangeCount < 0
                    || unknownRangeCount != unknownRangeRefs.size()) {
                throw new IllegalArgumentException("scenario denominator counts are inconsistent");
            }
        }

        /** @return exact denominator reference after canonical addressing */
        public MirrorArtifactRef artifactRef() {
            if (denominatorFingerprint.isBlank()) {
                throw new IllegalStateException("scenario denominator is not content-addressed");
            }
            return new MirrorArtifactRef(
                    ARTIFACT_KIND, denominatorId, revision, denominatorFingerprint);
        }

        /** @return identical denominator carrying a replacement canonical fingerprint */
        public ScenarioDenominator withFingerprint(String fingerprint) {
            return new ScenarioDenominator(schemaVersion, fingerprint, denominatorId, revision,
                    frozenBy, frozenAt, ownerFreezeAttestationRef, declaredFamilyCount,
                    highRiskObligationCount, coveredHighRiskObligationCount, unknownRangeCount,
                    scenarioFamilyRefs, unknownRangeRefs, limitations);
        }
    }

    /** Customer-owned observation period required before a real acceptance decision. */
    public record ObservationWindow(
            ObservationWindowStatus status,
            Instant plannedFrom,
            Instant plannedTo,
            Instant actualFrom,
            Instant actualTo,
            MirrorArtifactRef authoritativeOutcomePopulationRef,
            List<String> reasonCodes
    ) {
        /** Validates planned and actual observation boundaries. */
        public ObservationWindow {
            status = Objects.requireNonNull(status, "status");
            plannedFrom = Objects.requireNonNull(plannedFrom, "plannedFrom");
            plannedTo = Objects.requireNonNull(plannedTo, "plannedTo");
            reasonCodes = BusinessMirrorPilotAcceptanceManifest.reasonCodes(reasonCodes);
            if (!plannedTo.isAfter(plannedFrom)) {
                throw new IllegalArgumentException("observation plannedTo must be after plannedFrom");
            }
            if (authoritativeOutcomePopulationRef != null) {
                authoritativeOutcomePopulationRef = exactRef(authoritativeOutcomePopulationRef,
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST",
                        "authoritativeOutcomePopulationRef");
            }
            switch (status) {
                case PLANNED -> require(actualFrom == null && actualTo == null
                                && authoritativeOutcomePopulationRef == null,
                        "planned observation must not claim actual outcome evidence");
                case ACTIVE -> require(actualFrom != null && actualTo == null
                                && authoritativeOutcomePopulationRef == null,
                        "active observation requires only actualFrom");
                case COMPLETED -> require(actualFrom != null && actualTo != null
                                && actualTo.isAfter(actualFrom)
                                && authoritativeOutcomePopulationRef != null,
                        "completed observation requires actual interval and outcome population");
                case INVALIDATED -> require(!reasonCodes.isEmpty(),
                        "invalidated observation requires reasonCodes");
            }
        }
    }

    /** External customer decision; accepted/rejected states require an immutable decision ref. */
    public record CustomerAcceptance(
            CustomerAcceptanceStatus status,
            String decidedBy,
            Instant decidedAt,
            MirrorArtifactRef decisionRef,
            List<String> reasonCodes
    ) {
        /** Validates that a decision is never inferred from a local fixture. */
        public CustomerAcceptance {
            status = Objects.requireNonNull(status, "status");
            decidedBy = normalized(decidedBy);
            reasonCodes = BusinessMirrorPilotAcceptanceManifest.reasonCodes(reasonCodes);
            boolean terminal = status == CustomerAcceptanceStatus.ACCEPTED
                    || status == CustomerAcceptanceStatus.REJECTED;
            if (terminal) {
                identifier(decidedBy, "decidedBy");
                Objects.requireNonNull(decidedAt, "decidedAt");
                decisionRef = exactRef(
                        decisionRef, "CUSTOMER_ACCEPTANCE_DECISION", "decisionRef");
            } else if (!decidedBy.isEmpty() || decidedAt != null || decisionRef != null) {
                throw new IllegalArgumentException(
                        "non-terminal customer acceptance must not claim a decision");
            }
            if (status == CustomerAcceptanceStatus.REJECTED && reasonCodes.isEmpty()) {
                throw new IllegalArgumentException("rejected acceptance requires reasonCodes");
            }
        }
    }

    private static OverallStatus derivedStatus(
            CustomerAcceptanceStatus customerStatus, boolean acceptanceReady) {
        return switch (customerStatus) {
            case ACCEPTED -> {
                require(acceptanceReady,
                        "customer acceptance requires all gates and observation complete");
                yield OverallStatus.CUSTOMER_ACCEPTED;
            }
            case REJECTED -> OverallStatus.CUSTOMER_REJECTED;
            case NOT_REQUESTED, IN_REVIEW -> acceptanceReady
                    ? OverallStatus.READY_FOR_CUSTOMER_VALIDATION
                    : OverallStatus.PREPARING;
        };
    }

    private static List<AcceptanceGate> orderedGates(List<AcceptanceGate> values) {
        List<AcceptanceGate> exact = values == null ? List.of() : List.copyOf(values);
        List<GateId> actual = exact.stream().map(AcceptanceGate::gateId).toList();
        List<GateId> required = Arrays.asList(GateId.values());
        if (!actual.equals(required)) {
            throw new IllegalArgumentException(
                    "acceptanceGates must contain all ten gates in protocol order");
        }
        return exact;
    }

    private static Map<GateId, Set<String>> requiredPassEvidence() {
        Map<GateId, Set<String>> values = new EnumMap<>(GateId.class);
        values.put(GateId.PACKAGE_DEFINITION_COMPLETE,
                Set.of("DOMAIN_CAPABILITY_PACKAGE", "PACKAGE_READINESS_REPORT",
                        "SCENARIO_DENOMINATOR"));
        values.put(GateId.HIGH_RISK_BRANCH_OBLIGATIONS,
                Set.of("SCENARIO_DENOMINATOR", "BUSINESS_ACCEPTANCE_SUITE"));
        values.put(GateId.ISOLATED_PROPOSAL_REHEARSAL,
                Set.of("PROPOSAL_SIMULATION_EVIDENCE",
                        "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION"));
        values.put(GateId.SAME_SUITE_IMPLEMENTATION_CONFORMANCE,
                Set.of("IMPLEMENTATION_CONFORMANCE_REPORT", "BUSINESS_ACCEPTANCE_SUITE"));
        values.put(GateId.ZERO_EXTERNAL_BUSINESS_WRITES,
                Set.of("MIRROR_EVIDENCE_BUNDLE", "RUNTIME_CERTIFICATION_REPORT"));
        values.put(GateId.EVIDENCE_TRACEABILITY,
                Set.of("PACKAGE_EVIDENCE_INDEX"));
        values.put(GateId.ANEKE_GOVERNANCE_ROUND_TRIP,
                Set.of("PACKAGE_REGISTRY_INGEST_BUNDLE", "ANEKE_PACKAGE_GATE_DECISION"));
        values.put(GateId.CHANGE_IMPACT_ANALYSIS,
                Set.of("BUSINESS_ASSET_IMPACT_REPORT"));
        values.put(GateId.OUTCOME_FIDELITY_FAIL_CLOSED,
                Set.of("DOMAIN_FIDELITY_PROFILE",
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST"));
        values.put(GateId.TARGET_ENVIRONMENT_CERTIFICATION,
                Set.of("REGIONAL_DATA_PLANE_CERTIFICATION", "RUNTIME_CERTIFICATION_REPORT"));
        return Map.copyOf(values);
    }

    private static Map<GateId, GateAuthority> requiredGateAuthority() {
        Map<GateId, GateAuthority> values = new EnumMap<>(GateId.class);
        values.put(GateId.PACKAGE_DEFINITION_COMPLETE, GateAuthority.RESOURCE_GATEWAY);
        values.put(GateId.HIGH_RISK_BRANCH_OBLIGATIONS,
                GateAuthority.CUSTOMER_BUSINESS_OWNER);
        values.put(GateId.ISOLATED_PROPOSAL_REHEARSAL, GateAuthority.RESOURCE_GATEWAY);
        values.put(GateId.SAME_SUITE_IMPLEMENTATION_CONFORMANCE,
                GateAuthority.RESOURCE_GATEWAY);
        values.put(GateId.ZERO_EXTERNAL_BUSINESS_WRITES, GateAuthority.CUSTOMER_PLATFORM);
        values.put(GateId.EVIDENCE_TRACEABILITY, GateAuthority.RESOURCE_GATEWAY);
        values.put(GateId.ANEKE_GOVERNANCE_ROUND_TRIP, GateAuthority.ANEKE);
        values.put(GateId.CHANGE_IMPACT_ANALYSIS, GateAuthority.RESOURCE_GATEWAY);
        values.put(GateId.OUTCOME_FIDELITY_FAIL_CLOSED,
                GateAuthority.CUSTOMER_BUSINESS_OWNER);
        values.put(GateId.TARGET_ENVIRONMENT_CERTIFICATION,
                GateAuthority.CUSTOMER_PLATFORM);
        return Map.copyOf(values);
    }

    private static Set<String> evidenceKinds(List<MirrorArtifactRef> refs) {
        return refs.stream().map(MirrorArtifactRef::kind).collect(java.util.stream.Collectors.toSet());
    }

    private static List<MirrorArtifactRef> exactRefsOfKind(
            List<MirrorArtifactRef> values, String kind, String field) {
        List<MirrorArtifactRef> exact = exactRefs(values, field);
        if (exact.stream().anyMatch(ref -> !kind.equals(ref.kind()))) {
            throw new IllegalArgumentException(field + " contains an unsupported kind");
        }
        return exact;
    }

    private static List<MirrorArtifactRef> exactRefs(
            List<MirrorArtifactRef> values, String field) {
        List<MirrorArtifactRef> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.size() > MAXIMUM_REFS || exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return List.copyOf(exact);
    }

    private static List<String> reasonCodes(List<String> values) {
        List<String> exact = values == null ? List.of() : values.stream()
                .map(BusinessMirrorPilotAcceptanceManifest::normalized)
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (!REASON_CODE.matcher(value).matches()) {
                        throw new IllegalArgumentException("reasonCode is invalid");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        if (exact.size() > 128) {
            throw new IllegalArgumentException("reasonCodes exceeds its item limit");
        }
        return List.copyOf(exact);
    }

    private static List<String> textList(List<String> values, String field) {
        List<String> exact = values == null ? List.of() : values.stream()
                .map(BusinessMirrorPilotAcceptanceManifest::normalized)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (exact.size() > 128 || exact.stream().anyMatch(value -> value.length() > 2_048)) {
            throw new IllegalArgumentException(field + " must be bounded");
        }
        return List.copyOf(exact);
    }

    private static MirrorArtifactRef exactRef(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return value;
    }

    private static String version(String value, String expected) {
        String exact = value == null || value.isBlank() ? expected : value.trim();
        if (!expected.equals(exact)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + exact);
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.isEmpty() && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
