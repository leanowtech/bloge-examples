package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessMirrorPilotAcceptanceProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void verifiesReferencePilotWithoutClaimingCustomerAcceptance() throws Exception {
        var verified = BusinessMirrorPilotAcceptanceProtocol.verify(fixture());

        assertThat(verified.manifestId())
                .isEqualTo("ride.cancellation-fee-dispute.reference-pilot");
        assertThat(verified.scenarioFamilyCount()).isEqualTo(12);
        assertThat(verified.highRiskObligationCount()).isEqualTo(18);
        assertThat(verified.coveredHighRiskObligationCount()).isEqualTo(14);
        assertThat(verified.unknownRangeCount()).isEqualTo(2);
        assertThat(verified.passedGateCount()).isZero();
        assertThat(verified.blockedGateCount()).isEqualTo(7);
        assertThat(verified.evidenceAvailableGateCount()).isEqualTo(3);
        assertThat(verified.observationStatus()).isEqualTo("PLANNED");
        assertThat(verified.customerAcceptanceStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(verified.overallStatus()).isEqualTo("PREPARING");
    }

    @Test
    void rejectsManifestAndNestedDenominatorTampering() throws Exception {
        ObjectNode manifestTampered = fixture();
        manifestTampered.put("assembler", "attacker");
        ObjectNode denominatorTampered = fixture();
        denominatorTampered.withObject("/scenarioDenominator")
                .put("coveredHighRiskObligationCount", 18);
        addressManifestOnly(denominatorTampered);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(manifestTampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_FINGERPRINT_MISMATCH");
        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(denominatorTampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_DENOMINATOR_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsShortenedReorderedOrUnknownGateProtocol() throws Exception {
        ObjectNode shortened = fixture();
        ((ArrayNode) shortened.path("acceptanceGates")).remove(9);
        ObjectNode reordered = fixture();
        ArrayNode gates = (ArrayNode) reordered.path("acceptanceGates");
        JsonNode first = gates.path(0).deepCopy();
        gates.set(0, gates.path(1).deepCopy());
        gates.set(1, first);
        ObjectNode unknown = fixture();
        unknown.put("waiver", true);

        assertInvalid(shortened);
        assertInvalid(reordered);
        assertInvalid(unknown);
    }

    @Test
    void rejectsGateAuthoritySubstitutionAndUnexplainedFailure() throws Exception {
        ObjectNode forged = fixture();
        ((ObjectNode) forged.path("acceptanceGates").path(6))
                .put("authority", "RESOURCE_GATEWAY");
        addressManifestOnly(forged);
        ObjectNode unexplained = fixture();
        ObjectNode failedGate = (ObjectNode) unexplained.path("acceptanceGates").path(0);
        failedGate.put("state", "FAILED");
        failedGate.put("assessedAt", "2026-08-15T01:00:00Z");
        addressManifestOnly(unexplained);

        assertInvalid(forged);
        assertInvalid(unexplained);
    }

    @Test
    void rejectsPassedGateWithoutItsRequiredEvidenceKinds() throws Exception {
        ObjectNode forged = fixture();
        ObjectNode first = (ObjectNode) forged.path("acceptanceGates").path(0);
        first.put("state", "PASSED");
        first.put("assessedAt", "2026-08-15T02:00:00Z");
        ((ArrayNode) first.path("evidenceRefs")).removeAll();
        first.withArray("evidenceRefs").add(forged.path("packageSnapshotRef").deepCopy());
        addressManifestOnly(forged);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_GATE_PASS_EVIDENCE_INCOMPLETE");
    }

    @Test
    void rejectsLocallyForgedCustomerAcceptance() throws Exception {
        ObjectNode forged = fixture();
        ObjectNode acceptance = (ObjectNode) forged.path("customerAcceptance");
        acceptance.put("status", "ACCEPTED");
        acceptance.put("decidedBy", "customer-owner");
        acceptance.put("decidedAt", "2026-08-15T02:00:00Z");
        ObjectNode decision = acceptance.putObject("decisionRef");
        decision.put("kind", "CUSTOMER_ACCEPTANCE_DECISION");
        decision.put("id", "decision-1");
        decision.put("revision", 1);
        decision.put("fingerprint", "sha256:" + "f".repeat(64));
        forged.put("status", "CUSTOMER_ACCEPTED");
        addressManifestOnly(forged);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_CUSTOMER_ACCEPTANCE_UNPROVEN");
    }

    @Test
    void highRiskGateCannotPassWithAnIncompleteFrozenDenominator() throws Exception {
        ObjectNode forged = fixture();
        ObjectNode gate = (ObjectNode) forged.path("acceptanceGates").path(1);
        gate.put("state", "PASSED");
        gate.put("assessedAt", "2026-08-15T01:00:00Z");
        addressManifestOnly(forged);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_HIGH_RISK_COVERAGE_INCOMPLETE");
    }

    @Test
    void plannedObservationCannotSmuggleActualOutcomeEvidence() throws Exception {
        ObjectNode forged = fixture();
        forged.withObject("/observationWindow")
                .put("actualFrom", "2026-09-01T00:00:00Z");
        addressManifestOnly(forged);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_OBSERVATION_STATE_INVALID");
    }

    @Test
    void activeObservationCannotStartAfterManifestAssembly() throws Exception {
        ObjectNode forged = fixture();
        forged.withObject("/observationWindow")
                .put("status", "ACTIVE")
                .put("actualFrom", "2026-09-01T00:00:00Z");
        addressManifestOnly(forged);
        ObjectNode preFreeze = fixture();
        ObjectNode firstGate = (ObjectNode) preFreeze.path("acceptanceGates").path(0);
        firstGate.put("state", "PASSED");
        firstGate.put("assessedAt", "2026-08-14T01:00:00Z");
        addressManifestOnly(preFreeze);

        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_TIME_INVALID");
        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(preFreeze))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PILOT_ACCEPTANCE_TIME_INVALID");
    }

    @Test
    void independentlyDerivesACompleteSyntheticAcceptance() throws Exception {
        ObjectNode accepted = acceptedFixture();

        var verified = BusinessMirrorPilotAcceptanceProtocol.verify(accepted);

        assertThat(verified.passedGateCount()).isEqualTo(10);
        assertThat(verified.blockedGateCount()).isZero();
        assertThat(verified.coveredHighRiskObligationCount())
                .isEqualTo(verified.highRiskObligationCount());
        assertThat(verified.observationStatus()).isEqualTo("COMPLETED");
        assertThat(verified.customerAcceptanceStatus()).isEqualTo("ACCEPTED");
        assertThat(verified.overallStatus()).isEqualTo("CUSTOMER_ACCEPTED");
    }

    private static ObjectNode fixture() throws Exception {
        try (InputStream input = BusinessMirrorPilotAcceptanceProtocolTest.class
                .getResourceAsStream(
                        BusinessMirrorPilotAcceptanceProtocol.REFERENCE_FIXTURE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("reference fixture missing");
            }
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private static void addressManifestOnly(ObjectNode value) {
        value.put("manifestFingerprint", "");
        value.put("manifestFingerprint", BusinessMirrorCanonical.fingerprint(value,
                "RG.BUSINESS_MIRROR.CLIENT.TEST_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.TEST_CANONICALIZATION_FAILED"));
    }

    private static ObjectNode acceptedFixture() throws Exception {
        ObjectNode value = fixture();
        ObjectNode denominator = (ObjectNode) value.path("scenarioDenominator");
        denominator.put("coveredHighRiskObligationCount",
                denominator.path("highRiskObligationCount").asInt());
        denominator.put("denominatorFingerprint", "");
        String denominatorFingerprint = BusinessMirrorCanonical.fingerprint(denominator,
                "RG.BUSINESS_MIRROR.CLIENT.TEST_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.TEST_CANONICALIZATION_FAILED");
        denominator.put("denominatorFingerprint", denominatorFingerprint);

        String[] evidenceKinds = {
                "DOMAIN_CAPABILITY_PACKAGE", "PACKAGE_READINESS_REPORT", "SCENARIO_DENOMINATOR",
                "BUSINESS_ACCEPTANCE_SUITE", "PROPOSAL_SIMULATION_EVIDENCE",
                "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION", "IMPLEMENTATION_CONFORMANCE_REPORT",
                "MIRROR_EVIDENCE_BUNDLE", "RUNTIME_CERTIFICATION_REPORT",
                "PACKAGE_EVIDENCE_INDEX", "PACKAGE_REGISTRY_INGEST_BUNDLE",
                "ANEKE_PACKAGE_GATE_DECISION", "BUSINESS_ASSET_IMPACT_REPORT",
                "DOMAIN_FIDELITY_PROFILE", "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST",
                "REGIONAL_DATA_PLANE_CERTIFICATION"
        };
        int gateIndex = 0;
        for (JsonNode node : value.path("acceptanceGates")) {
            ObjectNode gate = (ObjectNode) node;
            gate.put("state", "PASSED");
            gate.put("assessedAt", "2026-10-01T00:30:00Z");
            gate.withArray("reasonCodes").removeAll();
            ArrayNode refs = gate.withArray("evidenceRefs");
            for (String kind : evidenceKinds) {
                boolean exists = false;
                for (JsonNode ref : refs) {
                    exists |= kind.equals(ref.path("kind").asText());
                }
                if (!exists) {
                    ObjectNode ref = refs.addObject();
                    ref.put("kind", kind);
                    ref.put("id", "synthetic:" + gateIndex + ":" + kind.toLowerCase());
                    ref.put("revision", 1);
                    ref.put("fingerprint", "sha256:" + "f".repeat(64));
                }
            }
            for (JsonNode ref : refs) {
                if ("SCENARIO_DENOMINATOR".equals(ref.path("kind").asText())) {
                    ((ObjectNode) ref).put("id", denominator.path("denominatorId").asText());
                    ((ObjectNode) ref).put("revision", denominator.path("revision").asLong());
                    ((ObjectNode) ref).put("fingerprint", denominatorFingerprint);
                }
            }
            gateIndex++;
        }
        ObjectNode observation = (ObjectNode) value.path("observationWindow");
        observation.put("status", "COMPLETED");
        observation.put("actualFrom", "2026-09-01T00:00:00Z");
        observation.put("actualTo", "2026-10-01T00:00:00Z");
        ObjectNode outcome = observation.putObject("authoritativeOutcomePopulationRef");
        outcome.put("kind", "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST");
        outcome.put("id", "synthetic-outcome-window");
        outcome.put("revision", 1);
        outcome.put("fingerprint", "sha256:" + "e".repeat(64));
        for (JsonNode ref : value.path("acceptanceGates").path(8).path("evidenceRefs")) {
            if ("AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST"
                    .equals(ref.path("kind").asText())) {
                ((ObjectNode) ref).setAll(outcome);
            }
        }
        observation.withArray("reasonCodes").removeAll();

        ObjectNode acceptance = (ObjectNode) value.path("customerAcceptance");
        acceptance.put("status", "ACCEPTED");
        acceptance.put("decidedBy", "customer-owner");
        acceptance.put("decidedAt", "2026-10-01T01:00:00Z");
        ObjectNode decision = acceptance.putObject("decisionRef");
        decision.put("kind", "CUSTOMER_ACCEPTANCE_DECISION");
        decision.put("id", "synthetic-customer-decision");
        decision.put("revision", 1);
        decision.put("fingerprint", "sha256:" + "d".repeat(64));
        value.put("status", "CUSTOMER_ACCEPTED");
        value.put("assembledAt", "2026-10-01T02:00:00Z");
        addressManifestOnly(value);
        return value;
    }

    private static void assertInvalid(JsonNode value) {
        assertThatThrownBy(() -> BusinessMirrorPilotAcceptanceProtocol.verify(value))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
