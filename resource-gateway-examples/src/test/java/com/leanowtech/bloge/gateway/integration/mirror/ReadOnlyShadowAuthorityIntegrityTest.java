package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityIntegrityTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T08:40:00Z");
    private static final Instant ISSUED =
            NOW.minusSeconds(60);
    private static final Instant EXPIRES =
            NOW.plusSeconds(600);
    private static final String ISSUER =
            "data-governance:shadow";

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    private final ReadOnlyShadowAuthorityIntegrity integrity =
            new ReadOnlyShadowAuthorityIntegrity(mapper);

    private ReadOnlyShadowGuardPolicyPublication policy;
    private ReadOnlyShadowSamplingGrantPublication grant;
    private ReadOnlyShadowKillSwitchPublication killSwitch;
    private ReadOnlyShadowAuthorityIntegrity.AuthorityKey
            grantKey;
    private ReadOnlyShadowAuthorityIntegrity.AuthorityKey
            policyKey;
    private ReadOnlyShadowAuthorityIntegrity.AuthorityKey
            killSwitchKey;

    @BeforeEach
    void setUp() {
        policy = integrity.sealGuardPolicy(
                policyMaterial(), signer);
        grant = integrity.sealSamplingGrant(
                grantMaterial(policy.artifactRef(), true),
                signer);
        killSwitch = integrity.sealKillSwitch(
                killSwitchMaterial(true), signer);
        VisualEvidenceSigner.VerificationKey verificationKey =
                signer.key(grant.seal().keyId())
                        .orElseThrow();
        grantKey = key(
                verificationKey,
                executionScope(),
                ReadOnlyShadowAuthorityIntegrity
                        .PublicationKind.SAMPLING_GRANT);
        policyKey = key(
                verificationKey,
                guardScope(),
                ReadOnlyShadowAuthorityIntegrity
                        .PublicationKind.GUARD_POLICY);
        killSwitchKey = key(
                verificationKey,
                executionScope(),
                ReadOnlyShadowAuthorityIntegrity
                        .PublicationKind.KILL_SWITCH);
    }

    @Test
    void independentlyVerifiesAllThreeDomainSeparatedPublications() {
        assertThat(integrity.verifyGuardPolicy(
                policy, policyKey, NOW).verified()).isTrue();
        assertThat(integrity.verifySamplingGrant(
                grant, grantKey, NOW).verified()).isTrue();
        assertThat(integrity.verifyKillSwitch(
                killSwitch, killSwitchKey, NOW).verified()).isTrue();

        assertThat(policy.artifactRef().kind())
                .isEqualTo(
                        "SHADOW_EXECUTION_GUARD_POLICY");
        assertThat(grant.artifactRef().fingerprint())
                .isEqualTo(grant.materialFingerprint());
        assertThat(grant.attestationRef().fingerprint())
                .isEqualTo(
                        grant.publicationFingerprint());
        assertThat(killSwitch.attestationRef().kind())
                .isEqualTo(
                        "SHADOW_KILL_SWITCH_ATTESTATION");
    }

    @Test
    void rejectsMaterialTamperingAndCrossProtocolSignatureReuse() {
        ReadOnlyShadowSamplingGrantPublication.Material altered =
                grantMaterial(
                        policy.artifactRef(), false);
        ReadOnlyShadowSamplingGrantPublication tampered =
                new ReadOnlyShadowSamplingGrantPublication(
                        "",
                        grant.publicationFingerprint(),
                        grant.materialFingerprint(),
                        altered,
                        grant.seal());

        assertThat(integrity.verifySamplingGrant(
                tampered, grantKey, NOW))
                .extracting(
                        ReadOnlyShadowAuthorityIntegrity
                                .VerificationResult::outcome,
                        ReadOnlyShadowAuthorityIntegrity
                                .VerificationResult::reasonCode)
                .containsExactly(
                        ReadOnlyShadowAuthorityIntegrity
                                .Outcome.INVALID,
                        "PUBLICATION_FINGERPRINT_INVALID");

        ReadOnlyShadowKillSwitchPublication crossProtocol =
                new ReadOnlyShadowKillSwitchPublication(
                        "",
                        killSwitch.publicationFingerprint(),
                        grant.materialFingerprint(),
                        killSwitch.material(),
                        grant.seal());
        assertThat(integrity.verifyKillSwitch(
                crossProtocol, killSwitchKey, NOW).reasonCode())
                .isEqualTo(
                        "PUBLICATION_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsRevokedWrongIssuerAndOutOfWindowKeys() {
        var revoked = keyWith(
                grantKey.issuer(),
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.REVOKED,
                grantKey.notBefore(),
                grantKey.notAfter(),
                null);
        var wrongIssuer = keyWith(
                "operations:other",
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.ACTIVE,
                grantKey.notBefore(),
                grantKey.notAfter(),
                null);
        var expired = keyWith(
                grantKey.issuer(),
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.ACTIVE,
                grantKey.notBefore().minusSeconds(100),
                NOW,
                null);

        assertThat(integrity.verifySamplingGrant(
                grant, revoked, NOW).reasonCode())
                .isEqualTo(
                        "AUTHORITY_KEY_POLICY_REJECTED");
        assertThat(integrity.verifySamplingGrant(
                grant, wrongIssuer, NOW).reasonCode())
                .isEqualTo(
                        "AUTHORITY_IDENTITY_MISMATCH");
        assertThat(integrity.verifySamplingGrant(
                grant, expired, NOW).reasonCode())
                .isEqualTo(
                        "AUTHORITY_KEY_OUTSIDE_VALIDITY");
    }

    @Test
    void rejectsUseBeforeSignatureAndAtExclusiveExpiry() {
        assertThat(integrity.verifyGuardPolicy(
                policy,
                policyKey,
                NOW.minusMillis(1)).reasonCode())
                .isEqualTo(
                        "PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
        assertThat(integrity.verifyGuardPolicy(
                policy,
                policyKey,
                EXPIRES).reasonCode())
                .isEqualTo(
                        "PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
    }

    @Test
    void retiredKeyAcceptsOnlySignaturesStrictlyBeforeRetirement() {
        var historical = keyWith(
                grantKey.issuer(),
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.RETIRED,
                grantKey.notBefore(),
                grantKey.notAfter(),
                NOW.plusSeconds(1));
        var retiredAtSigning = keyWith(
                grantKey.issuer(),
                ReadOnlyShadowAuthorityIntegrity
                        .KeyState.RETIRED,
                grantKey.notBefore(),
                grantKey.notAfter(),
                NOW);

        assertThat(integrity.verifySamplingGrant(
                grant, historical, NOW).verified())
                .isTrue();
        assertThat(integrity.verifySamplingGrant(
                grant, retiredAtSigning, NOW)
                .reasonCode())
                .isEqualTo(
                        "AUTHORITY_KEY_POLICY_REJECTED");
    }

    @Test
    void keyDelegationCannotCrossScopeOrAuthorityProtocol() {
        var wrongScope =
                new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                        grantKey.keyId(),
                        grantKey.algorithm(),
                        grantKey.encodedPublicKey(),
                        grantKey.issuer(),
                        guardScope(),
                        grantKey.publicationKind(),
                        grantKey.notBefore(),
                        grantKey.notAfter(),
                        null,
                        ReadOnlyShadowAuthorityIntegrity
                                .KeyState.ACTIVE);
        var wrongProtocol =
                new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                        grantKey.keyId(),
                        grantKey.algorithm(),
                        grantKey.encodedPublicKey(),
                        grantKey.issuer(),
                        grantKey.scope(),
                        ReadOnlyShadowAuthorityIntegrity
                                .PublicationKind.KILL_SWITCH,
                        grantKey.notBefore(),
                        grantKey.notAfter(),
                        null,
                        ReadOnlyShadowAuthorityIntegrity
                                .KeyState.ACTIVE);

        assertThat(integrity.verifySamplingGrant(
                grant, wrongScope, NOW).reasonCode())
                .isEqualTo(
                        "AUTHORITY_IDENTITY_MISMATCH");
        assertThat(integrity.verifySamplingGrant(
                grant, wrongProtocol, NOW).reasonCode())
                .isEqualTo(
                        "AUTHORITY_IDENTITY_MISMATCH");
    }

    private ReadOnlyShadowAuthorityIntegrity.AuthorityKey
    keyWith(
            String issuer,
            ReadOnlyShadowAuthorityIntegrity.KeyState state,
            Instant notBefore,
            Instant notAfter,
            Instant retiredAt) {
        return new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                grantKey.keyId(),
                grantKey.algorithm(),
                grantKey.encodedPublicKey(),
                issuer,
                grantKey.scope(),
                grantKey.publicationKind(),
                notBefore,
                notAfter,
                retiredAt,
                state);
    }

    private static ReadOnlyShadowAuthorityIntegrity.AuthorityKey
    key(
            VisualEvidenceSigner.VerificationKey key,
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind
                    publicationKind) {
        return new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                key.keyId(),
                key.algorithm(),
                key.encodedPublicKey(),
                ISSUER,
                scope,
                publicationKind,
                key.createdAt().minusSeconds(1),
                EXPIRES.plusSeconds(600),
                null,
                ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE);
    }

    private static ReadOnlyShadowGuardPolicyPublication.Material
    policyMaterial() {
        return new ReadOnlyShadowGuardPolicyPublication.Material(
                "provider:credit-primary",
                1,
                "",
                guardScope(),
                new ReadOnlyShadowExecutionGuard.Limits(
                        4,
                        20,
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofMinutes(2)),
                ISSUED,
                ISSUED,
                EXPIRES,
                ISSUER);
    }

    private static ReadOnlyShadowSamplingGrantPublication.Material
    grantMaterial(
            MirrorArtifactRef policyRef,
            boolean active) {
        return new ReadOnlyShadowSamplingGrantPublication.Material(
                "grant:loan-risk",
                1,
                "",
                executionScope(),
                active,
                100,
                guardScope(),
                policyRef,
                ISSUED,
                ISSUED,
                EXPIRES,
                ISSUER);
    }

    private static ReadOnlyShadowKillSwitchPublication.Material
    killSwitchMaterial(boolean enabled) {
        return new ReadOnlyShadowKillSwitchPublication.Material(
                "switch:loan-risk",
                1,
                "",
                executionScope(),
                enabled,
                ISSUED,
                ISSUED,
                EXPIRES,
                ISSUER);
    }

    private static CapabilitySnapshot.Scope executionScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "risk",
                "loan",
                "staging",
                "sg");
    }

    private static CapabilitySnapshot.Scope guardScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "shared-provider",
                "",
                "staging",
                "sg");
    }
}
