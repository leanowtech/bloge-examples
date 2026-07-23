package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAttestationDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAttestationDecoderTest {
    private final MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
    private final MirrorDeploymentIsolationAttestationDecoder decoder =
            new MirrorDeploymentIsolationAttestationDecoder(fixtures.mapper);

    @Test
    void decodesExactClosedAttestationAndRevocationProtocols() throws Exception {
        var bundle = fixtures.bundle(7);
        var revocation = revocation(bundle);

        assertThat(decoder.decodeAttestation(
                fixtures.mapper.writeValueAsBytes(bundle.attestation()), identity()))
                .isEqualTo(bundle.attestation());
        assertThat(decoder.decodeRevocation(
                fixtures.mapper.writeValueAsBytes(revocation), identity()))
                .isEqualTo(revocation);
    }

    @Test
    void rejectsDuplicateAndUnknownFieldsBeforeDomainMapping() throws Exception {
        String attestation = fixtures.mapper.writeValueAsString(
                fixtures.bundle(7).attestation());
        assertMalformed(() -> decoder.decodeAttestation(attestation.replaceFirst(
                        "\\{", "{\"schemaVersion\":\"duplicate\",")
                .getBytes(StandardCharsets.UTF_8), identity()));

        ObjectNode revocation = fixtures.mapper.valueToTree(revocation(fixtures.bundle(7)));
        revocation.put("reactivate", true);
        assertMalformed(() -> decoder.decodeRevocation(bytes(revocation), identity()));
    }

    @Test
    void rejectsWrongVersionOversizedDepthAndAcceptedAsRevocationReason() {
        ObjectNode wrongVersion = fixtures.mapper.valueToTree(fixtures.bundle(7).attestation());
        wrongVersion.put("schemaVersion", "resourceGateway.mirrorIsolationAttestation.v2");
        assertMalformed(() -> decoder.decodeAttestation(bytes(wrongVersion), identity()));

        assertMalformed(() -> decoder.decodeAttestation(new byte[
                MirrorDeploymentIsolationAttestationDecoder.MAXIMUM_ATTESTATION_BYTES + 1],
                identity()));

        ObjectNode deep = fixtures.mapper.valueToTree(revocation(fixtures.bundle(7)));
        ObjectNode cursor = fixtures.mapper.createObjectNode();
        deep.set("unexpected", cursor);
        for (int index = 0;
             index < MirrorDeploymentIsolationAttestationDecoder.MAXIMUM_DEPTH + 2;
             index++) {
            ObjectNode next = fixtures.mapper.createObjectNode();
            cursor.set("next", next);
            cursor = next;
        }
        assertMalformed(() -> decoder.decodeRevocation(bytes(deep), identity()));

        ObjectNode accepted = fixtures.mapper.valueToTree(revocation(fixtures.bundle(7)));
        accepted.put("reason", "ACCEPTED");
        assertMalformed(() -> decoder.decodeRevocation(bytes(accepted), identity()));
    }

    private MirrorDeploymentIsolationAttestationRevocationRequest revocation(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        return new MirrorDeploymentIsolationAttestationRevocationRequest("",
                bundle.attestation().material().revision(),
                bundle.attestation().attestationFingerprint(),
                bundle.status().material().statusRevision(),
                bundle.status().statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED);
    }

    private byte[] bytes(ObjectNode value) {
        try {
            return fixtures.mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertMalformed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .startsWith("RG.MIRROR.ISOLATION_ATTESTATION_")
                            .endsWith("_MALFORMED");
                    assertThat(failure.problem().details())
                            .containsEntry("maximumDepth",
                                    MirrorDeploymentIsolationAttestationDecoder.MAXIMUM_DEPTH)
                            .containsEntry("maximumNodes",
                                    MirrorDeploymentIsolationAttestationDecoder.MAXIMUM_NODES);
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "trust-admin", "", "MIRROR_TRUST_ADMIN",
                "corr-decoder", Set.of(), "RESTRICTED", "");
    }
}
