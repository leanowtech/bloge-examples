package com.leanowtech.bloge.gateway.businessmirror.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.AcceptanceGate;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.CustomerAcceptance;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.CustomerAcceptanceStatus;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.GateAuthority;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.GateId;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.GateState;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ObservationWindow;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ObservationWindowStatus;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.OverallStatus;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ScenarioDenominator;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.List;

/** Deterministic cancellation-fee pilot acceptance protocol fixture. */
public final class BusinessMirrorPilotAcceptanceFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    static final Instant FROZEN_AT = Instant.parse("2026-08-14T02:00:00Z");
    static final Instant ASSEMBLED_AT = Instant.parse("2026-08-15T02:00:00Z");

    private BusinessMirrorPilotAcceptanceFixtures() {
    }

    /** Emits the server-produced reference fixture to standard output. */
    public static void main(String[] args) throws Exception {
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest()));
    }

    static BusinessMirrorPilotAcceptanceManifest manifest() {
        var integrity = new BusinessMirrorPilotAcceptanceManifestIntegrity(MAPPER);
        ScenarioDenominator denominator = integrity.addressDenominator(denominator());
        BusinessMirrorPilotAcceptanceManifest material =
                new BusinessMirrorPilotAcceptanceManifest("", "",
                        "ride.cancellation-fee-dispute.reference-pilot", 1, SCOPE,
                        "ride.cancellation-fee-dispute.v1",
                        ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-package", 7,
                                "131279a278840732b8a6e1cbf954945b270729220a03df4ca11cfc1b44b90fe1"),
                        denominator, gates(denominator.artifactRef()),
                        new ObservationWindow(ObservationWindowStatus.PLANNED,
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Instant.parse("2026-10-01T00:00:00Z"),
                                null, null, null,
                                List.of("CUSTOMER_OUTCOME_WINDOW_NOT_STARTED")),
                        new CustomerAcceptance(CustomerAcceptanceStatus.NOT_REQUESTED,
                                "", null, null,
                                List.of("REFERENCE_FIXTURE_ONLY")),
                        OverallStatus.PREPARING, ASSEMBLED_AT,
                        "resource-gateway:reference-pilot",
                        List.of("Customer ANEKE and target-environment evidence are pending",
                                "This reference fixture is not a customer acceptance decision"));
        return integrity.address(material);
    }

    static BusinessMirrorPilotAcceptanceManifest syntheticAcceptedManifest() {
        var integrity = new BusinessMirrorPilotAcceptanceManifestIntegrity(MAPPER);
        ScenarioDenominator source = denominator();
        ScenarioDenominator denominator = integrity.addressDenominator(new ScenarioDenominator(
                source.schemaVersion(), "", source.denominatorId(), source.revision(),
                source.frozenBy(), source.frozenAt(), source.ownerFreezeAttestationRef(),
                source.declaredFamilyCount(), source.highRiskObligationCount(),
                source.highRiskObligationCount(), source.unknownRangeCount(),
                source.scenarioFamilyRefs(), source.unknownRangeRefs(), source.limitations()));
        MirrorArtifactRef outcomePopulation = ref(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST",
                "cancellation-outcome-window", 1, repeated('7'));
        BusinessMirrorPilotAcceptanceManifest material =
                new BusinessMirrorPilotAcceptanceManifest("", "",
                        "ride.cancellation-fee-dispute.synthetic-accepted", 1, SCOPE,
                        "ride.cancellation-fee-dispute.v1",
                        ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-package", 7,
                                "131279a278840732b8a6e1cbf954945b270729220a03df4ca11cfc1b44b90fe1"),
                        denominator, passedGates(denominator.artifactRef(), outcomePopulation),
                        new ObservationWindow(ObservationWindowStatus.COMPLETED,
                                Instant.parse("2026-08-14T03:00:00Z"),
                                Instant.parse("2026-08-15T00:00:00Z"),
                                Instant.parse("2026-08-14T03:00:00Z"),
                                Instant.parse("2026-08-15T00:00:00Z"),
                                outcomePopulation, List.of()),
                        new CustomerAcceptance(CustomerAcceptanceStatus.ACCEPTED,
                                "customer-acceptance-owner",
                                Instant.parse("2026-08-15T01:00:00Z"),
                                ref("CUSTOMER_ACCEPTANCE_DECISION",
                                        "synthetic-acceptance-decision", 1, repeated('8')),
                                List.of("SYNTHETIC_POSITIVE_PROTOCOL_TEST")),
                        OverallStatus.CUSTOMER_ACCEPTED, ASSEMBLED_AT,
                        "resource-gateway:protocol-test", List.of(
                        "Synthetic object proves protocol transitions, not customer acceptance"));
        return integrity.address(material);
    }

    static ScenarioDenominator denominator() {
        return new ScenarioDenominator("", "",
                "ride.cancellation-fee-dispute.denominator", 1,
                "cancellation-service-owner", FROZEN_AT,
                ref("BUSINESS_OWNER_FREEZE_ATTESTATION",
                        "reference:cancellation-owner-freeze", 1, repeated('a')),
                12, 18, 14, 2,
                List.of(
                        family("driver-not-arrived-waiver", '1'),
                        family("driver-arrived-rider-timeout-fee-valid", '2'),
                        family("time-threshold-minus-plus-one-minute", '3'),
                        family("rule-version-city-vehicle-variation", '4'),
                        family("order-state-delay-or-conflict", '5'),
                        family("duplicate-dispute-refund-idempotency", '6'),
                        family("upstream-timeout-partial-fallback-handoff", '7'),
                        family("high-risk-or-insufficient-evidence-abstain", '8'),
                        family("proposal-fixture-unmatched-fail-closed", '9'),
                        family("simulation-implementation-difference", 'a'),
                        family("conversation-recovery-and-missing-information", 'b'),
                        family("outcome-late-conflict-censored", 'c')),
                List.of(
                        ref("SCENARIO_UNKNOWN_RANGE", "future-city-policy", 1, repeated('d')),
                        ref("SCENARIO_UNKNOWN_RANGE", "unmapped-risk-code", 1, repeated('e'))),
                List.of("Coverage is a reference denominator, not a customer-approved count"));
    }

    static List<AcceptanceGate> gates(MirrorArtifactRef denominatorRef) {
        MirrorArtifactRef packageRef = ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-package", 7,
                "131279a278840732b8a6e1cbf954945b270729220a03df4ca11cfc1b44b90fe1");
        MirrorArtifactRef readinessRef = ref("PACKAGE_READINESS_REPORT",
                "cancellation-readiness", 7,
                "bf2bd6dd0e02a9382cc610ed5ba9b415b0a070a678b0941fe0c98ca60ef52a80");
        MirrorArtifactRef evidenceRef = ref("PACKAGE_EVIDENCE_INDEX", "cancellation-package", 3,
                "b0b55653f6ed236986e98bd253f7f3e16c66aed4c632a80d6877be4ed9a6c8fe");
        MirrorArtifactRef registryBundleRef = ref("PACKAGE_REGISTRY_INGEST_BUNDLE",
                "package-registry-ingest:cancellation-package", 7,
                "02e3441e2989c88f8a1c45d63d8c90d00437b507ea505b47fb3e7eb15a4c313b");
        return List.of(
                gate(GateId.PACKAGE_DEFINITION_COMPLETE, GateAuthority.RESOURCE_GATEWAY,
                        GateState.EVIDENCE_AVAILABLE,
                        List.of(packageRef, readinessRef, denominatorRef), List.of()),
                gate(GateId.HIGH_RISK_BRANCH_OBLIGATIONS,
                        GateAuthority.CUSTOMER_BUSINESS_OWNER,
                        GateState.EVIDENCE_AVAILABLE,
                        List.of(denominatorRef,
                                ref("BUSINESS_ACCEPTANCE_SUITE",
                                        "cancellation-business-acceptance", 1, repeated('f'))),
                        List.of()),
                blocked(GateId.ISOLATED_PROPOSAL_REHEARSAL,
                        GateAuthority.RESOURCE_GATEWAY,
                        "COHERENT_PILOT_SIMULATION_EVIDENCE_PENDING"),
                blocked(GateId.SAME_SUITE_IMPLEMENTATION_CONFORMANCE,
                        GateAuthority.RESOURCE_GATEWAY,
                        "CUSTOMER_IMPLEMENTATION_BINDING_PENDING"),
                blocked(GateId.ZERO_EXTERNAL_BUSINESS_WRITES,
                        GateAuthority.CUSTOMER_PLATFORM,
                        "CUSTOMER_MIRROR_EXECUTION_WINDOW_PENDING"),
                gate(GateId.EVIDENCE_TRACEABILITY, GateAuthority.RESOURCE_GATEWAY,
                        GateState.EVIDENCE_AVAILABLE, List.of(evidenceRef), List.of()),
                gate(GateId.ANEKE_GOVERNANCE_ROUND_TRIP, GateAuthority.ANEKE,
                        GateState.BLOCKED, List.of(registryBundleRef),
                        List.of("CUSTOMER_ANEKE_TRUST_AND_GATE_PENDING")),
                blocked(GateId.CHANGE_IMPACT_ANALYSIS, GateAuthority.RESOURCE_GATEWAY,
                        "COHERENT_PILOT_IMPACT_REPORT_PENDING"),
                blocked(GateId.OUTCOME_FIDELITY_FAIL_CLOSED,
                        GateAuthority.CUSTOMER_BUSINESS_OWNER,
                        "CUSTOMER_AUTHORITATIVE_OUTCOME_WINDOW_PENDING"),
                blocked(GateId.TARGET_ENVIRONMENT_CERTIFICATION,
                        GateAuthority.CUSTOMER_PLATFORM,
                        "CUSTOMER_TARGET_ENVIRONMENT_CERTIFICATION_PENDING"));
    }

    private static List<AcceptanceGate> passedGates(
            MirrorArtifactRef denominatorRef, MirrorArtifactRef outcomePopulation) {
        Instant assessedAt = Instant.parse("2026-08-15T00:30:00Z");
        return List.of(
                passed(GateId.PACKAGE_DEFINITION_COMPLETE,
                        GateAuthority.RESOURCE_GATEWAY, assessedAt,
                        ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-package", 7,
                                "131279a278840732b8a6e1cbf954945b270729220a03df4ca11cfc1b44b90fe1"),
                        ref("PACKAGE_READINESS_REPORT", "cancellation-readiness", 7,
                                "bf2bd6dd0e02a9382cc610ed5ba9b415b0a070a678b0941fe0c98ca60ef52a80"),
                        denominatorRef),
                passed(GateId.HIGH_RISK_BRANCH_OBLIGATIONS,
                        GateAuthority.CUSTOMER_BUSINESS_OWNER, assessedAt,
                        denominatorRef, evidence("BUSINESS_ACCEPTANCE_SUITE", '1')),
                passed(GateId.ISOLATED_PROPOSAL_REHEARSAL,
                        GateAuthority.RESOURCE_GATEWAY, assessedAt,
                        evidence("PROPOSAL_SIMULATION_EVIDENCE", '2'),
                        evidence("MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION", '3')),
                passed(GateId.SAME_SUITE_IMPLEMENTATION_CONFORMANCE,
                        GateAuthority.RESOURCE_GATEWAY, assessedAt,
                        evidence("IMPLEMENTATION_CONFORMANCE_REPORT", '4'),
                        evidence("BUSINESS_ACCEPTANCE_SUITE", '1')),
                passed(GateId.ZERO_EXTERNAL_BUSINESS_WRITES,
                        GateAuthority.CUSTOMER_PLATFORM, assessedAt,
                        evidence("MIRROR_EVIDENCE_BUNDLE", '5'),
                        evidence("RUNTIME_CERTIFICATION_REPORT", '6')),
                passed(GateId.EVIDENCE_TRACEABILITY,
                        GateAuthority.RESOURCE_GATEWAY, assessedAt,
                        evidence("PACKAGE_EVIDENCE_INDEX", '7')),
                passed(GateId.ANEKE_GOVERNANCE_ROUND_TRIP,
                        GateAuthority.ANEKE, assessedAt,
                        evidence("PACKAGE_REGISTRY_INGEST_BUNDLE", '8'),
                        evidence("ANEKE_PACKAGE_GATE_DECISION", '9')),
                passed(GateId.CHANGE_IMPACT_ANALYSIS,
                        GateAuthority.RESOURCE_GATEWAY, assessedAt,
                        evidence("BUSINESS_ASSET_IMPACT_REPORT", 'a')),
                passed(GateId.OUTCOME_FIDELITY_FAIL_CLOSED,
                        GateAuthority.CUSTOMER_BUSINESS_OWNER, assessedAt,
                        evidence("DOMAIN_FIDELITY_PROFILE", 'b'), outcomePopulation),
                passed(GateId.TARGET_ENVIRONMENT_CERTIFICATION,
                        GateAuthority.CUSTOMER_PLATFORM, assessedAt,
                        evidence("REGIONAL_DATA_PLANE_CERTIFICATION", 'c'),
                        evidence("RUNTIME_CERTIFICATION_REPORT", '6')));
    }

    private static AcceptanceGate passed(
            GateId id,
            GateAuthority authority,
            Instant assessedAt,
            MirrorArtifactRef... refs) {
        return new AcceptanceGate(
                id, authority, GateState.PASSED, List.of(refs), List.of(), assessedAt);
    }

    private static MirrorArtifactRef evidence(String kind, char fingerprint) {
        return ref(kind, "synthetic:" + kind.toLowerCase(java.util.Locale.ROOT), 1,
                repeated(fingerprint));
    }

    private static AcceptanceGate gate(
            GateId id,
            GateAuthority authority,
            GateState state,
            List<MirrorArtifactRef> refs,
            List<String> reasons) {
        return new AcceptanceGate(id, authority, state, refs, reasons, null);
    }

    private static AcceptanceGate blocked(
            GateId id, GateAuthority authority, String reason) {
        return gate(id, authority, GateState.BLOCKED, List.of(), List.of(reason));
    }

    private static MirrorArtifactRef family(String id, char fingerprint) {
        return ref("SCENARIO_FAMILY", id, 1, repeated(fingerprint));
    }

    static MirrorArtifactRef ref(
            String kind, String id, long revision, String fingerprintMaterial) {
        String fingerprint = fingerprintMaterial.startsWith("sha256:")
                ? fingerprintMaterial : "sha256:" + fingerprintMaterial;
        return new MirrorArtifactRef(kind, id, revision, fingerprint);
    }

    static String repeated(char material) {
        return String.valueOf(material).repeat(64);
    }
}
