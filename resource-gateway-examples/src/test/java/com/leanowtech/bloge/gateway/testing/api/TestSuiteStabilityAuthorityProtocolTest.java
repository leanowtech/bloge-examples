package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityAuthorityProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsDeterministicMinimalCredentialFreeRequest() throws Exception {
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);
        String json = objectMapper.writeValueAsString(request);

        assertThat(request.fingerprintsVerified(objectMapper)).isTrue();
        assertThat(request.principal().groups()).containsExactly("payments", "release");
        assertThat(json).contains("suite.checkout", "grant-17", "CONFIDENTIAL")
                .doesNotContain("correlation-secret", "business-secret", "metadata",
                        "credential", "Bearer ", "fixture", "context", "payload");
    }

    @Test
    void principalAndRequestTamperingInvalidateIndependentFingerprints() {
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);
        TestSuiteStabilityAuthorityRequest.Principal changed =
                new TestSuiteStabilityAuthorityRequest.Principal(
                        request.principal().tenantId(), request.principal().organizationId(),
                        request.principal().projectId(), request.principal().environmentId(),
                        request.principal().region(), request.principal().actorType(),
                        request.principal().actorId(), request.principal().delegatedBy(),
                        request.principal().purpose(), new ArrayList<>(java.util.List.of("other")),
                        request.principal().clearance(),
                        request.principal().delegationGrantId());
        TestSuiteStabilityAuthorityRequest tampered = new TestSuiteStabilityAuthorityRequest(
                request.schemaVersion(), request.requestId(), request.challenge(),
                request.requestedAt(), request.action(), request.jobId(),
                request.jobRequestFingerprint(), request.suiteRef(), request.classification(),
                request.deadlineAt(), changed, request.principalFingerprint(),
                request.authorizationRequestFingerprint());

        assertThat(tampered.fingerprintsVerified(objectMapper)).isFalse();
    }

    @Test
    void responseFingerprintCoversDecisionAndEveryEchoedBinding() {
        var keyPair = keyPair();
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);
        TestSuiteStabilityAuthorityResponse response = response(
                objectMapper, keyPair, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
        TestSuiteStabilityAuthorityResponse tampered =
                new TestSuiteStabilityAuthorityResponse(
                        response.schemaVersion(), response.requestId(), response.challenge(),
                        response.jobId(), response.authorizationRequestFingerprint(),
                        response.principalFingerprint(),
                        TestSuiteStabilityAuthorityResponse.Decision.REVOKED,
                        "RG.POLICY.REVOKED", response.authorityId(),
                        response.policyRevision(), response.decisionId(), response.issuedAt(),
                        response.expiresAt(), response.materialFingerprint(), response.signature());

        assertThat(response.fingerprintVerified(objectMapper)).isTrue();
        assertThat(tampered.fingerprintVerified(objectMapper)).isFalse();
    }

    @Test
    void capabilityDescriptorsRejectUnboundedOrSensitiveExtensionFields() {
        assertThatThrownBy(() -> new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", true, "CUSTOM", "", java.util.Map.of(
                "baseUri", "https://iam.example",
                "privateKey", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorizer descriptor");
        assertThatThrownBy(() -> new TestSuiteStabilityAuthorityTrustStore.Descriptor(
                "", true, "STATIC_ED25519", AUTHORITY_ID, 1,
                java.util.Map.of("publicKeyBase64", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust descriptor");
    }
}
