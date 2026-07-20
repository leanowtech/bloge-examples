package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSecretAuthorityProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void requestBindsExactContextWithoutCredentialsCorrelationOrBusinessPayload() throws Exception {
        TestSecretAuthorityRequest request = request(objectMapper);
        String json = objectMapper.writeValueAsString(request);

        assertThat(request.fingerprintsVerified(objectMapper)).isTrue();
        assertThat(request.action()).isEqualTo("RESOLVE_TEST_SECRET_CLOSURE");
        assertThat(json).contains(REFERENCE, "tenant-a", "fixture-payment")
                .doesNotContain("Bearer ", "credential", "correlationId", "graphInput",
                        "fixturePayload", VALUE, "privateKey");

        TestSecretResolutionContext other = new TestSecretResolutionContext("", "tenant-b",
                request.context().organizationId(), request.context().projectId(),
                request.context().environmentId(), request.context().region(),
                request.context().actorType(), request.context().actorId(),
                request.context().delegatedBy(), request.context().purpose(),
                request.context().groups(), request.context().clearance(),
                request.context().delegationGrantId(), request.context().authorizedPurpose(),
                request.context().executionTargetFingerprint(),
                request.context().fixtureTargetFingerprint(),
                request.context().fixtureBundleId(), request.context().fixtureRevision(),
                request.context().fixtureFingerprint(), request.context().secretRefs());
        TestSecretAuthorityRequest tampered = new TestSecretAuthorityRequest(
                request.schemaVersion(), request.requestId(), request.challenge(),
                request.requestedAt(), request.action(), other, request.contextFingerprint(),
                request.requestFingerprint());
        assertThat(tampered.fingerprintsVerified(objectMapper)).isFalse();
    }

    @Test
    void responseFingerprintCoversSecretValuesAndConvertsOnlyAuthorizedClosures() {
        TestSecretAuthorityRequest request = request(objectMapper);
        var keyPair = keyPair();
        TestSecretAuthorityResponse authorized = response(objectMapper, keyPair, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "");

        assertThat(authorized.fingerprintVerified(objectMapper)).isTrue();
        assertThat(authorized.toResolvedSecrets().resolve(ALIAS)).isEqualTo(VALUE);

        TestSecretAuthorityResponse.SecretMaterial changed =
                new TestSecretAuthorityResponse.SecretMaterial(ALIAS, REFERENCE, VERSION,
                        authorized.secrets().get(ALIAS).bindingFingerprint(), "changed-value");
        TestSecretAuthorityResponse tampered = new TestSecretAuthorityResponse(
                authorized.schemaVersion(), authorized.requestId(), authorized.challenge(),
                authorized.requestFingerprint(), authorized.contextFingerprint(),
                authorized.decision(), authorized.failureCode(), authorized.authorityId(),
                authorized.authorityGeneration(), authorized.decisionId(), authorized.issuedAt(),
                authorized.expiresAt(), java.util.Map.of(ALIAS, changed),
                authorized.materialFingerprint(), authorized.signature());
        assertThat(tampered.fingerprintVerified(objectMapper)).isFalse();

        TestSecretAuthorityResponse denied = response(objectMapper, keyPair, request,
                TestSecretAuthorityResponse.Decision.DENIED, "RG.POLICY.SECRET_DENIED");
        assertThat(denied.fingerprintVerified(objectMapper)).isTrue();
        assertThatThrownBy(denied::toResolvedSecrets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(REFERENCE)
                .hasMessageNotContaining(ALIAS)
                .hasMessageNotContaining(VALUE);
    }

    @Test
    void constructorsRejectWeakChallengeAmbiguousDecisionAndMalformedSignature() {
        TestSecretAuthorityRequest request = request(objectMapper);
        assertThatThrownBy(() -> new TestSecretAuthorityRequest(
                request.schemaVersion(), request.requestId(), "weak", request.requestedAt(),
                request.action(), request.context(), request.contextFingerprint(),
                request.requestFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);

        TestSecretAuthorityResponse valid = response(objectMapper, keyPair(), request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "");
        assertThatThrownBy(() -> new TestSecretAuthorityResponse(
                valid.schemaVersion(), valid.requestId(), valid.challenge(),
                valid.requestFingerprint(), valid.contextFingerprint(), valid.decision(),
                "RG.SHOULD_BE_EMPTY", valid.authorityId(), valid.authorityGeneration(),
                valid.decisionId(), valid.issuedAt(), valid.expiresAt(), valid.secrets(),
                valid.materialFingerprint(), valid.signature()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSecretAuthorityResponse.SignatureBlock(
                KEY_ID, "RS256", Base64.getEncoder().encodeToString(new byte[64])))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(List.copyOf(valid.secrets().values()))
                .allSatisfy(secret -> assertThat(secret.value()).isNotBlank());
    }
}
