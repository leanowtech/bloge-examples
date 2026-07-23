package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureMirrorTrajectoryBindingsVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureMirrorTrajectoryBindingsVerifier verifier =
            new FixtureMirrorTrajectoryBindingsVerifier();

    @Test
    void verifiesPackagedCanonicalFixtureWithoutClaimingLiveAuthority()
            throws Exception {
        FixtureMirrorTrajectoryBindingsVerifier.VerificationResult result =
                verifier.verify(fixture());

        assertThat(result.verified()).isTrue();
        assertThat(result.outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.VERIFIED);
        assertThat(result.checkedBindings()).isEqualTo(2);
    }

    @Test
    void rejectsUnknownFieldsWrongKindsDuplicatesAndNonCanonicalOrder()
            throws Exception {
        JsonNode unknown = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown)
                .put("fallbackToLatest", true);
        JsonNode wrongKind = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongKind.at(
                "/trajectories/0/trajectoryPublicationRef"))
                .put("kind", "CAPABILITY_CORPUS_PUBLICATION");
        JsonNode duplicate = fixture();
        JsonNode first = duplicate.at(
                "/trajectories/0/trajectoryPublicationRef").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.at(
                "/trajectories/1")).set(
                "trajectoryPublicationRef", first);
        JsonNode reversed = fixture();
        com.fasterxml.jackson.databind.node.ArrayNode trajectories =
                (com.fasterxml.jackson.databind.node.ArrayNode) reversed.path(
                        "trajectories");
        JsonNode firstBinding = trajectories.get(0);
        JsonNode secondBinding = trajectories.get(1);
        trajectories.removeAll();
        trajectories.add(secondBinding);
        trajectories.add(firstBinding);

        assertThat(verifier.verify(unknown).outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.SCHEMA_INVALID);
        assertThat(verifier.verify(wrongKind).outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.SCHEMA_INVALID);
        assertThat(verifier.verify(duplicate).outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome
                        .DUPLICATE_TRAJECTORY);
        assertThat(verifier.verify(reversed).outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.ORDER_INVALID);
    }

    @Test
    void ordersMultiDigitTrajectoryRevisionsNumerically()
            throws Exception {
        JsonNode bindings = fixture();
        com.fasterxml.jackson.databind.node.ObjectNode first =
                (com.fasterxml.jackson.databind.node.ObjectNode) bindings.at(
                        "/trajectories/0/trajectoryPublicationRef");
        com.fasterxml.jackson.databind.node.ObjectNode second =
                (com.fasterxml.jackson.databind.node.ObjectNode) bindings.at(
                        "/trajectories/1/trajectoryPublicationRef");
        first.put("id", "same-trajectory");
        first.put("revision", 2);
        second.put("id", "same-trajectory");
        second.put("revision", 10);

        assertThat(verifier.verify(bindings).outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.VERIFIED);
    }

    @Test
    void reportsOversizedInputAsStableSchemaFailure()
            throws Exception {
        JsonNode bindings = fixture();
        com.fasterxml.jackson.databind.node.ArrayNode trajectories =
                (com.fasterxml.jackson.databind.node.ArrayNode) bindings.path(
                        "trajectories");
        JsonNode template = trajectories.get(0).deepCopy();
        while (trajectories.size() <= 1_000) {
            trajectories.add(template.deepCopy());
        }

        FixtureMirrorTrajectoryBindingsVerifier.VerificationResult result =
                verifier.verify(bindings);

        assertThat(result.outcome()).isEqualTo(
                FixtureMirrorTrajectoryBindingsVerifier.Outcome.SCHEMA_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo("TRAJECTORY_BINDINGS_SCHEMA_INVALID");
        assertThat(result.checkedBindings()).isEqualTo(1_000);
    }

    private JsonNode fixture() throws Exception {
        try (InputStream input =
                CapabilityMirrorProtocol.class.getResourceAsStream(
                        CapabilityMirrorProtocol
                                .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE)) {
            assertThat(input).isNotNull();
            return mapper.readTree(input);
        }
    }
}
