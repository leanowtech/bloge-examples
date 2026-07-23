package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAuthorityKeySetVerifierTest {
    private final MirrorDeploymentIsolationAuthorityKeySetVerifier verifier =
            new MirrorDeploymentIsolationAuthorityKeySetVerifier();

    @Test
    void fixedFixtureVerifiesOfflineAndReturnsAUsableAttestationKey() {
        var fixture = fixture();

        var result = verifier.verify(fixture.publication(), fixture.expectedBinding(),
                fixture.bootstrapRoots(), null, fixture.verificationTime());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.authorityKeys()).hasSize(1);
        assertThat(result.authorityKey(result.authorityKeys().getFirst().keyId()))
                .get()
                .extracting(MirrorDeploymentIsolationVerificationKey::issuer)
                .isEqualTo("sre:mirror-isolation-fixture");
    }

    @Test
    void strictSchemaAndCanonicalCollectionOrderFailClosed() {
        var fixture = fixture();
        ObjectNode extraField = (ObjectNode) fixture.publication().deepCopy();
        extraField.put("producerTrusted", true);
        assertThat(verify(extraField, fixture).reasonCode())
                .isEqualTo("PUBLICATION_SCHEMA_INVALID");

        ObjectNode reversed = (ObjectNode) fixture.publication().deepCopy();
        ArrayNode signatures = (ArrayNode) reversed.path("signatures");
        JsonNode first = signatures.get(0).deepCopy();
        JsonNode second = signatures.get(1).deepCopy();
        signatures.removeAll().add(second).add(first);
        assertThat(verify(reversed, fixture).reasonCode())
                .isEqualTo("PUBLICATION_ROOT_SIGNATURE_ORDER_INVALID");

        ObjectNode noActiveKey = (ObjectNode) fixture.publication().deepCopy();
        ((ObjectNode) noActiveKey.at("/material/authorityKeys/0")).put("state", "REVOKED");
        assertThat(verify(noActiveKey, fixture).reasonCode())
                .isEqualTo("PUBLICATION_ACTIVE_AUTHORITY_KEY_UNAVAILABLE");
    }

    @Test
    void materialTamperingIsRejectedBeforeLocalPolicyCanTrustItsLabels() {
        var fixture = fixture();
        ObjectNode altered = (ObjectNode) fixture.publication().deepCopy();
        ((ObjectNode) altered.path("material")).put(
                "attestationIssuer", "sre:attacker-controlled-label");

        var result = verify(altered, fixture);

        assertThat(result.reasonCode()).isEqualTo("PUBLICATION_MATERIAL_FINGERPRINT_INVALID");
        assertThat(result.authorityKeys()).isEmpty();
    }

    @Test
    void exactEnterpriseBindingAndThresholdCannotBeChosenByThePublication() {
        var fixture = fixture();
        var expected = fixture.expectedBinding();
        var drifted = new MirrorDeploymentIsolationAuthorityKeySetBinding(
                new MirrorDeploymentIsolationAuthorityKeySetBinding.Scope(
                        expected.scope().tenantId(), expected.scope().organizationId(),
                        expected.scope().projectId(), "production", expected.scope().region()),
                expected.deployment(), expected.attestationIssuer(), expected.keySetId(),
                expected.rootTrustDomain(), expected.rootThreshold(),
                expected.acceptedPolicyFingerprints());

        assertThat(verifier.verify(fixture.publication(), drifted, fixture.bootstrapRoots(), null,
                fixture.verificationTime()).reasonCode())
                .isEqualTo("PUBLICATION_BINDING_MISMATCH");

        var strongerThreshold = new MirrorDeploymentIsolationAuthorityKeySetBinding(
                expected.scope(), expected.deployment(), expected.attestationIssuer(),
                expected.keySetId(), expected.rootTrustDomain(), 3,
                expected.acceptedPolicyFingerprints());
        assertThat(verifier.verify(fixture.publication(), strongerThreshold,
                fixture.bootstrapRoots(), null, fixture.verificationTime()).reasonCode())
                .isEqualTo("PUBLICATION_BINDING_MISMATCH");
    }

    @Test
    void everySuppliedRootSignatureMustBePinnedAllowedAndCryptographicallyValid() {
        var fixture = fixture();
        assertThat(verifier.verify(fixture.publication(), fixture.expectedBinding(),
                fixture.bootstrapRoots().subList(0, 1), null,
                fixture.verificationTime()).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_UNKNOWN");

        MirrorDeploymentIsolationRootVerificationKey first =
                fixture.bootstrapRoots().getFirst();
        MirrorDeploymentIsolationRootVerificationKey second =
                fixture.bootstrapRoots().get(1);
        var aliasedRoot = new MirrorDeploymentIsolationRootVerificationKey(
                second.schemaVersion(), second.authorityId(), second.keyId(), second.algorithm(),
                first.encodedPublicKey(), second.notBefore(), second.notAfter(),
                MirrorDeploymentIsolationRootVerificationKey.State.ACTIVE);
        assertThat(verifier.verify(fixture.publication(), fixture.expectedBinding(),
                List.of(first, aliasedRoot), null, fixture.verificationTime()).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOTS_AMBIGUOUS");

        var revoked = new MirrorDeploymentIsolationRootVerificationKey(
                first.schemaVersion(), first.authorityId(), first.keyId(), first.algorithm(),
                first.encodedPublicKey(), first.notBefore(), first.notAfter(),
                MirrorDeploymentIsolationRootVerificationKey.State.REVOKED);
        assertThat(verifier.verify(fixture.publication(), fixture.expectedBinding(),
                List.of(revoked, fixture.bootstrapRoots().get(1)), null,
                fixture.verificationTime()).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_POLICY_REJECTED");

        var wrongKey = new MirrorDeploymentIsolationRootVerificationKey(
                first.schemaVersion(), first.authorityId(), first.keyId(), first.algorithm(),
                fixture.publication().at("/material/authorityKeys/0/encodedPublicKey").asText(),
                first.notBefore(),
                first.notAfter(), MirrorDeploymentIsolationRootVerificationKey.State.ACTIVE);
        assertThat(verifier.verify(fixture.publication(), fixture.expectedBinding(),
                List.of(wrongKey, fixture.bootstrapRoots().get(1)), null,
                fixture.verificationTime()).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_SIGNATURE_INVALID");
    }

    @Test
    void trustedFloorAcceptsIdempotencyAndRejectsForkRollbackAndWrongStream() {
        var fixture = fixture();
        JsonNode publication = fixture.publication();
        String keySetId = publication.at("/material/keySetId").asText();
        String fingerprint = publication.path("publicationFingerprint").asText();
        var exactFloor = new MirrorDeploymentIsolationAuthorityKeySetVerifier.TrustedFloor(
                keySetId, 1, fingerprint);
        assertThat(verifier.verify(publication, fixture.expectedBinding(),
                fixture.bootstrapRoots(), exactFloor, fixture.verificationTime()).verified())
                .isTrue();

        var forkFloor = new MirrorDeploymentIsolationAuthorityKeySetVerifier.TrustedFloor(
                keySetId, 1, fingerprint('a'));
        assertThat(verifier.verify(publication, fixture.expectedBinding(),
                fixture.bootstrapRoots(), forkFloor, fixture.verificationTime()).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_FORK");

        var futureFloor = new MirrorDeploymentIsolationAuthorityKeySetVerifier.TrustedFloor(
                keySetId, 2, fingerprint('b'));
        assertThat(verifier.verify(publication, fixture.expectedBinding(),
                fixture.bootstrapRoots(), futureFloor, fixture.verificationTime()).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_ROLLBACK");

        var wrongStream = new MirrorDeploymentIsolationAuthorityKeySetVerifier.TrustedFloor(
                "another-key-set", 1, fingerprint);
        assertThat(verifier.verify(publication, fixture.expectedBinding(),
                fixture.bootstrapRoots(), wrongStream, fixture.verificationTime()).reasonCode())
                .isEqualTo("PUBLICATION_FLOOR_KEY_SET_MISMATCH");
    }

    @Test
    void fixtureCopiesAreDetachedFromCallerMutation() {
        var first = fixture();
        ((ObjectNode) first.publication()).put("publicationFingerprint", "changed");

        var second = fixture();
        assertThat(second.publication().path("publicationFingerprint").asText())
                .startsWith("sha256:");
        assertThat(verify(second.publication(), second).verified()).isTrue();
    }

    @Test
    void localBindingAndFixtureDecodersRequireExactBoundedCoordinates() {
        var fixture = fixture();
        ObjectNode malformed = (ObjectNode) fixture.publication().deepCopy();
        ObjectNode scope = (ObjectNode) malformed.at("/material/scope");
        scope.remove("projectId");
        scope.put("unexpectedProject", "tool-studio");

        assertThat(verify(malformed, fixture).reasonCode())
                .isEqualTo("PUBLICATION_SCHEMA_INVALID");
        assertThatThrownBy(() ->
                new MirrorDeploymentIsolationAuthorityKeySetBinding.Scope(
                        "tenant a", "org-a", "", "staging", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope.tenantId");
    }

    private MirrorDeploymentIsolationAuthorityKeySetVerifier.VerificationResult verify(
            JsonNode publication,
            MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture fixture) {
        return verifier.verify(publication, fixture.expectedBinding(), fixture.bootstrapRoots(),
                null, fixture.verificationTime());
    }

    private static MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture fixture() {
        return CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture();
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
