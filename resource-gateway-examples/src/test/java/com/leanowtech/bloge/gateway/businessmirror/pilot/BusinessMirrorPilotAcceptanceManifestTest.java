package com.leanowtech.bloge.gateway.businessmirror.pilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.AcceptanceGate;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.CustomerAcceptance;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.CustomerAcceptanceStatus;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.GateAuthority;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.GateState;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ObservationWindow;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ObservationWindowStatus;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.OverallStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessMirrorPilotAcceptanceManifestTest {
    @Test
    void referencePilotFreezesAllTwelveFamiliesAndKeepsUnknownRangeVisible() {
        BusinessMirrorPilotAcceptanceManifest manifest =
                BusinessMirrorPilotAcceptanceFixtures.manifest();

        assertThat(new BusinessMirrorPilotAcceptanceManifestIntegrity(
                BusinessMirrorPilotAcceptanceFixtures.MAPPER).canonicalVerified(manifest)).isTrue();
        assertThat(manifest.scenarioDenominator().declaredFamilyCount()).isEqualTo(12);
        assertThat(manifest.scenarioDenominator().scenarioFamilyRefs()).hasSize(12);
        assertThat(manifest.scenarioDenominator().unknownRangeCount()).isEqualTo(2);
        assertThat(manifest.acceptanceGates()).hasSize(10);
        assertThat(manifest.acceptanceGates().stream()
                .filter(gate -> gate.state() == GateState.PASSED)).isEmpty();
        assertThat(manifest.status()).isEqualTo(OverallStatus.PREPARING);
        assertThat(manifest.customerAcceptance().status())
                .isEqualTo(CustomerAcceptanceStatus.NOT_REQUESTED);
    }

    @Test
    void gateDenominatorCannotBeShortenedOrReordered() {
        BusinessMirrorPilotAcceptanceManifest source =
                BusinessMirrorPilotAcceptanceFixtures.manifest();
        List<AcceptanceGate> missing = source.acceptanceGates().subList(0, 9);
        List<AcceptanceGate> reordered = new ArrayList<>(source.acceptanceGates());
        java.util.Collections.swap(reordered, 0, 1);

        assertThatThrownBy(() -> copy(source, missing, source.customerAcceptance(), source.status()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all ten gates");
        assertThatThrownBy(() -> copy(
                source, reordered, source.customerAcceptance(), source.status()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol order");
    }

    @Test
    void customerAcceptanceCannotBeInferredFromReferenceEvidence() {
        BusinessMirrorPilotAcceptanceManifest source =
                BusinessMirrorPilotAcceptanceFixtures.manifest();
        CustomerAcceptance forged = new CustomerAcceptance(CustomerAcceptanceStatus.ACCEPTED,
                "customer-owner", BusinessMirrorPilotAcceptanceFixtures.ASSEMBLED_AT,
                BusinessMirrorPilotAcceptanceFixtures.ref("CUSTOMER_ACCEPTANCE_DECISION",
                        "decision-1", 1,
                        BusinessMirrorPilotAcceptanceFixtures.repeated('f')),
                List.of());

        assertThatThrownBy(() -> copy(
                source, source.acceptanceGates(), forged, OverallStatus.CUSTOMER_ACCEPTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all gates");
    }

    @Test
    void completeSyntheticEvidenceCanReachCustomerAcceptedWithoutABypass() {
        BusinessMirrorPilotAcceptanceManifest accepted =
                BusinessMirrorPilotAcceptanceFixtures.syntheticAcceptedManifest();

        assertThat(new BusinessMirrorPilotAcceptanceManifestIntegrity(
                BusinessMirrorPilotAcceptanceFixtures.MAPPER)
                .canonicalVerified(accepted)).isTrue();
        assertThat(accepted.acceptanceGates())
                .allMatch(gate -> gate.state() == GateState.PASSED);
        assertThat(accepted.scenarioDenominator().coveredHighRiskObligationCount())
                .isEqualTo(accepted.scenarioDenominator().highRiskObligationCount());
        assertThat(accepted.observationWindow().status())
                .isEqualTo(ObservationWindowStatus.COMPLETED);
        assertThat(accepted.status()).isEqualTo(OverallStatus.CUSTOMER_ACCEPTED);
    }

    @Test
    void passedGateRequiresAssessmentAndGateSpecificEvidenceKinds() {
        AcceptanceGate source = BusinessMirrorPilotAcceptanceFixtures.manifest()
                .acceptanceGates().getFirst();

        assertThatThrownBy(() -> new AcceptanceGate(source.gateId(), source.authority(),
                GateState.PASSED, source.evidenceRefs(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assessedAt");
        assertThatThrownBy(() -> new AcceptanceGate(source.gateId(), source.authority(),
                GateState.PASSED, List.of(source.evidenceRefs().getFirst()), List.of(),
                BusinessMirrorPilotAcceptanceFixtures.ASSEMBLED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required evidence kinds");
        assertThatThrownBy(() -> new AcceptanceGate(source.gateId(), source.authority(),
                GateState.FAILED, source.evidenceRefs(), List.of(),
                BusinessMirrorPilotAcceptanceFixtures.ASSEMBLED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAILED gate requires reasonCodes");
    }

    @Test
    void tamperedManifestOrDenominatorAddressFailsClosed() {
        BusinessMirrorPilotAcceptanceManifest source =
                BusinessMirrorPilotAcceptanceFixtures.manifest();
        var integrity = new BusinessMirrorPilotAcceptanceManifestIntegrity(
                BusinessMirrorPilotAcceptanceFixtures.MAPPER);
        BusinessMirrorPilotAcceptanceManifest tamperedManifest = source.withFingerprint(
                "sha256:" + BusinessMirrorPilotAcceptanceFixtures.repeated('0'));
        var tamperedDenominator = source.scenarioDenominator().withFingerprint(
                "sha256:" + BusinessMirrorPilotAcceptanceFixtures.repeated('1'));

        assertThat(integrity.canonicalVerified(tamperedManifest)).isFalse();
        assertThatThrownBy(() -> source.withScenarioDenominator(tamperedDenominator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core evidence references");
    }

    @Test
    void gateAuthorityAndActualObservationTimeCannotBeForged() {
        BusinessMirrorPilotAcceptanceManifest source =
                BusinessMirrorPilotAcceptanceFixtures.manifest();
        AcceptanceGate anekeGate = source.acceptanceGates().get(6);

        assertThatThrownBy(() -> new AcceptanceGate(anekeGate.gateId(),
                GateAuthority.RESOURCE_GATEWAY, anekeGate.state(), anekeGate.evidenceRefs(),
                anekeGate.reasonCodes(), anekeGate.assessedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol authority");

        ObservationWindow futureActive = new ObservationWindow(ObservationWindowStatus.ACTIVE,
                source.observationWindow().plannedFrom(), source.observationWindow().plannedTo(),
                source.assembledAt().plusSeconds(1), null, null, List.of());
        assertThatThrownBy(() -> new BusinessMirrorPilotAcceptanceManifest(
                source.schemaVersion(), source.manifestFingerprint(), source.manifestId(),
                source.revision(), source.scope(), source.pilotDomainId(),
                source.packageSnapshotRef(), source.scenarioDenominator(),
                source.acceptanceGates(), futureActive, source.customerAcceptance(),
                source.status(), source.assembledAt(), source.assembler(), source.limitations()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future assessment evidence");

        AcceptanceGate preFreezeAssessment = new AcceptanceGate(
                source.acceptanceGates().getFirst().gateId(),
                source.acceptanceGates().getFirst().authority(), GateState.PASSED,
                source.acceptanceGates().getFirst().evidenceRefs(), List.of(),
                BusinessMirrorPilotAcceptanceFixtures.FROZEN_AT.minusSeconds(1));
        List<AcceptanceGate> gates = new ArrayList<>(source.acceptanceGates());
        gates.set(0, preFreezeAssessment);
        assertThatThrownBy(() -> copy(
                source, gates, source.customerAcceptance(), source.status()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predates the frozen scenario denominator");
    }

    @Test
    void fixedFixtureIsProducedByServerModelAndSchemaIsStrict() throws Exception {
        JsonNode fixture = json("business-mirror-pilot-acceptance-manifest-v1.fixture.json");
        JsonNode schema = json("business-mirror-pilot-acceptance-manifest-v1.schema.json");
        JsonNode expected = BusinessMirrorPilotAcceptanceFixtures.MAPPER
                .valueToTree(BusinessMirrorPilotAcceptanceFixtures.manifest());

        assertThat(firstDifference(expected, fixture, "$")).isEmpty();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fixture.toString().toLowerCase())
                .doesNotContain("requestpayload", "responsepayload", "credential", "password");
        assertThat(fixture.path("observationWindow").path("status").asText())
                .isEqualTo(ObservationWindowStatus.PLANNED.name());
    }

    private static BusinessMirrorPilotAcceptanceManifest copy(
            BusinessMirrorPilotAcceptanceManifest source,
            List<AcceptanceGate> gates,
            CustomerAcceptance acceptance,
            OverallStatus status) {
        return new BusinessMirrorPilotAcceptanceManifest(source.schemaVersion(),
                source.manifestFingerprint(), source.manifestId(), source.revision(),
                source.scope(), source.pilotDomainId(), source.packageSnapshotRef(),
                source.scenarioDenominator(), gates, source.observationWindow(), acceptance,
                status, source.assembledAt(), source.assembler(), source.limitations());
    }

    private static JsonNode json(String name) throws Exception {
        return BusinessMirrorPilotAcceptanceFixtures.MAPPER.readTree(Files.readString(
                Path.of("..", "docs", "schemas", "resource-gateway-business-mirror", name)));
    }

    private static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0
                    ? "" : path + " value";
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + " type";
        }
        if (expected.isObject()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!expected.has(name) || !actual.has(name)) {
                    return path + "." + name + " missing";
                }
                String difference = firstDifference(
                        expected.get(name), actual.get(name), path + "." + name);
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return path + " size";
            }
            for (int index = 0; index < expected.size(); index++) {
                String difference = firstDifference(
                        expected.get(index), actual.get(index), path + "[" + index + "]");
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        return expected.equals(actual) ? "" : path + " value";
    }
}
