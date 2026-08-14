package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceProtocolVerifierTest {
    private final AuthoritativeOutcomeSourceProtocolVerifier verifier =
            new AuthoritativeOutcomeSourceProtocolVerifier();

    @Test
    void independentlyVerifiesAllServerProducedSourceFixtures() {
        JsonNode page = CapabilityMirrorProtocol.authoritativeOutcomeSourcePageFixture();
        JsonNode command = CapabilityMirrorProtocol.authoritativeOutcomeSourceCommandFixture();
        JsonNode checkpoint =
                CapabilityMirrorProtocol.authoritativeOutcomeSourceCheckpointFixture();

        assertThat(verifier.requirePage(page, (seal, artifact) -> true)
                .path("pageFingerprint").asText()).startsWith("sha256:");
        assertThat(verifier.requireCommand(command, (seal, artifact) -> true)
                .path("commandType").asText()).isEqualTo("BACKFILL");
        assertThat(verifier.requireCheckpoint(checkpoint)
                .path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsAddressTamperBeforeCallingExternalAuthority() {
        ObjectNode page = (ObjectNode) CapabilityMirrorProtocol
                .authoritativeOutcomeSourcePageFixture();
        page.put("streamId", "tampered");
        java.util.concurrent.atomic.AtomicBoolean authorityCalled =
                new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(() -> verifier.requirePage(page, (seal, artifact) -> {
            authorityCalled.set(true);
            return true;
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_FINGERPRINT_INVALID");
        assertThat(authorityCalled).isFalse();
    }

    @Test
    void rejectsUnknownFieldsAuthorityDenialAndCheckpointClosureTamper() {
        ObjectNode command = (ObjectNode) CapabilityMirrorProtocol
                .authoritativeOutcomeSourceCommandFixture();
        command.put("credential", "must-never-be-representable");
        assertThatThrownBy(() -> verifier.requireCommand(
                command, (seal, artifact) -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.OUTCOME_SOURCE_COMMAND_SCHEMA_INVALID");

        assertThatThrownBy(() -> verifier.requireCommand(
                CapabilityMirrorProtocol.authoritativeOutcomeSourceCommandFixture(),
                (seal, artifact) -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.OUTCOME_SOURCE_AUTHORITY_REJECTED");

        ObjectNode checkpoint = (ObjectNode) CapabilityMirrorProtocol
                .authoritativeOutcomeSourceCheckpointFixture();
        checkpoint.put("committedSequence", 1);
        assertThatThrownBy(() -> verifier.requireCheckpoint(checkpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.OUTCOME_SOURCE_CHECKPOINT_CLOSURE_INVALID");
    }

    @Test
    void fixtureAccessorsReturnDefensiveCopies() {
        ObjectNode first = (ObjectNode) CapabilityMirrorProtocol
                .authoritativeOutcomeSourcePageFixture();
        first.put("connectorId", "changed");

        assertThat(CapabilityMirrorProtocol.authoritativeOutcomeSourcePageFixture()
                .path("connectorId").asText())
                .isEqualTo("settlement-ledger");
    }
}
