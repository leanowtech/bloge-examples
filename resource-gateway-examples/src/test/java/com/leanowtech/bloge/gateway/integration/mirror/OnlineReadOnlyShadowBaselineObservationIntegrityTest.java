package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowBaselineObservationIntegrityTest {
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            integrity =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .integrity(mapper);

    @Test
    void signsContentAddressesAndVerifiesOnePayloadFreeObservation()
            throws Exception {
        OnlineReadOnlyShadowBaselineCommand command =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);

        OnlineReadOnlyShadowBaselineObservation signed =
                integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(mapper, command));

        assertThat(integrity.verify(signed))
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .Verification.VERIFIED);
        assertThat(signed.observationFingerprint())
                .startsWith("sha256:");
        assertThat(signed.artifactRef().kind())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservation
                                .ARTIFACT_KIND);
        assertThat(signed.observationId())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservation
                                .deterministicObservationId(
                                        mapper,
                                        command.scope(),
                                        command.executionId(),
                                        command.commandFingerprint(
                                                mapper),
                                        command.baselineBindingRef()));
        String json = mapper.writeValueAsString(signed);
        assertThat(json.toLowerCase())
                .doesNotContain(
                        "\"payload\"",
                        "credentialref",
                        "credentialvalue",
                        "secret",
                        "endpoint",
                        "requestbody",
                        "responsebody");
    }

    @Test
    void rejectsContentMutationAndAnUnrelatedAuthority()
            throws Exception {
        OnlineReadOnlyShadowBaselineObservation signed =
                integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(
                                        mapper,
                                        OnlineReadOnlyShadowBaselineTestFixtures
                                                .command(mapper)));
        ObjectNode tree = (ObjectNode) mapper
                .valueToTree(signed);
        tree.put(
                "semanticResultFingerprint",
                OnlineReadOnlyShadowBaselineTestFixtures
                        .fingerprint('9'));
        OnlineReadOnlyShadowBaselineObservation tampered =
                mapper.treeToValue(
                        tree,
                        OnlineReadOnlyShadowBaselineObservation
                                .class);

        assertThat(integrity.verify(tampered))
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .Verification.INVALID);
        var unrelated =
                new OnlineReadOnlyShadowBaselineObservationIntegrity(
                        mapper,
                        OnlineReadOnlyShadowBaselineEvidenceAuthority
                                .from(
                                        InMemoryVisualEvidenceSigner
                                                .usingClock(
                                                        OnlineReadOnlyShadowBaselineTestFixtures
                                                                .CLOCK)),
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .CLOCK);
        assertThat(unrelated.verify(signed))
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .Verification.INVALID);
    }

    @Test
    void distinguishesInvalidUnsignedIdentityFromUnavailableTrust() {
        OnlineReadOnlyShadowBaselineCommand command =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);
        OnlineReadOnlyShadowBaselineObservation unsigned =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .unsigned(mapper, command);
        ObjectNode tree = mapper.valueToTree(unsigned);
        tree.put("observationId", "wrong-observation");
        OnlineReadOnlyShadowBaselineObservation wrong =
                mapper.convertValue(
                        tree,
                        OnlineReadOnlyShadowBaselineObservation
                                .class);

        assertThatThrownBy(() -> integrity.sign(wrong))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .OBSERVATION_INVALID);

        var unavailable =
                new OnlineReadOnlyShadowBaselineObservationIntegrity(
                        mapper,
                        OnlineReadOnlyShadowBaselineEvidenceAuthority
                                .unavailable(),
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .CLOCK);
        assertThatThrownBy(() -> unavailable.sign(unsigned))
                .isInstanceOf(
                        IllegalStateException.class)
                .hasMessage(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .AUTHORITY_UNAVAILABLE);
    }

    @Test
    void classifiesAuthorityProviderFailureAsUnavailable() {
        OnlineReadOnlyShadowBaselineObservation signed =
                integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(
                                        mapper,
                                        OnlineReadOnlyShadowBaselineTestFixtures
                                                .command(mapper)));
        OnlineReadOnlyShadowBaselineEvidenceAuthority failing =
                new OnlineReadOnlyShadowBaselineEvidenceAuthority() {
                    @Override
                    public com.leanowtech.bloge.gateway.visual.runtime
                            .VisualRunEvidenceSeal seal(
                            String materialFingerprint) {
                        throw new IllegalStateException(
                                "provider offline");
                    }

                    @Override
                    public com.leanowtech.bloge.gateway.visual.runtime
                            .VisualEvidenceSigner.Verification verify(
                            com.leanowtech.bloge.gateway.visual.runtime
                                    .VisualRunEvidenceSeal seal,
                            String actualMaterialFingerprint) {
                        throw new IllegalStateException(
                                "provider offline");
                    }

                    @Override
                    public boolean available() {
                        return true;
                    }
                };
        var verifier =
                new OnlineReadOnlyShadowBaselineObservationIntegrity(
                        mapper,
                        failing,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .CLOCK);

        assertThat(verifier.verify(signed))
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .Verification.UNAVAILABLE);
    }
}
