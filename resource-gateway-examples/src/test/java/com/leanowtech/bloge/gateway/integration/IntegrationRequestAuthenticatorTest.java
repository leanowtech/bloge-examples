package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationRequestAuthenticatorTest {
    private RecordingIntegrationAccessAuditRepository audit;
    private IntegrationRequestAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        audit = new RecordingIntegrationAccessAuditRepository();
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity("aneke-sync-workload", "tenant-a",
                "org-a", "project-a", "prod", "ap-southeast-1", "WORKLOAD", "aneke-sync", "",
                Set.of("CHANGE_SYNC", "PAYLOAD_REPLAY", "GOVERNANCE_EVIDENCE_INGESTION"), Instant.MAX, true,
                Set.of("knowledge-owners", "tool-authors"), "CONFIDENTIAL", "", Instant.MAX);
        authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), audit);
    }

    @Test
    void buildsContextOnlyFromVerifiedServerClaims() {
        HttpHeaders headers = authorized("CHANGE_SYNC");

        IntegrationRequestContext context = authenticator.authenticate(headers, IntegrationOperation.CHANGE_SYNC);

        assertThat(context).extracting(IntegrationRequestContext::tenantId,
                        IntegrationRequestContext::organizationId, IntegrationRequestContext::projectId,
                        IntegrationRequestContext::environmentId, IntegrationRequestContext::actorId,
                        IntegrationRequestContext::clearance)
                .containsExactly("tenant-a", "org-a", "project-a", "prod", "aneke-sync", "CONFIDENTIAL");
        assertThat(context.groups()).containsExactlyInAnyOrder("knowledge-owners", "tool-authors");
        assertThat(audit.recent(1).getFirst()).extracting(IntegrationAccessAuditRecord::outcome,
                        IntegrationAccessAuditRecord::identityId, IntegrationAccessAuditRecord::credentialId,
                        IntegrationAccessAuditRecord::operation)
                .containsExactly("ALLOWED", "aneke-sync-workload", "static-bearer", "CHANGE_SYNC");
    }

    @Test
    void rejectsSelfAssertedScopeThatConflictsWithVerifiedClaims() {
        HttpHeaders headers = authorized("CHANGE_SYNC");
        headers.set("X-Tenant-Id", "tenant-b");

        assertThatThrownBy(() -> authenticator.authenticate(headers, IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH");
                    assertThat(failure.problem().details()).containsEntry(
                            "X-Tenant-Id", "does-not-match-verified-identity");
                });
        assertThat(audit.recent(1).getFirst().reasonCode())
                .isEqualTo("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH");
    }

    @Test
    void rejectsSelfAssertedClearanceEscalation() {
        HttpHeaders headers = authorized("CHANGE_SYNC");
        headers.set("X-Clearance", "RESTRICTED");

        assertThatThrownBy(() -> authenticator.authenticate(headers, IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().details())
                            .containsEntry("X-Clearance", "does-not-match-verified-identity");
                });
    }

    @Test
    void rejectsPurposeEscalationAndOperationPurposeMismatch() {
        assertThatThrownBy(() -> authenticator.authenticate(authorized("GOVERNANCE_GATE_FEEDBACK"),
                IntegrationOperation.GATE_RESULT_WRITE))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.PURPOSE_FORBIDDEN");
                });

        assertThatThrownBy(() -> authenticator.authenticate(authorized("CHANGE_SYNC"),
                IntegrationOperation.RECORDED_REPLAY))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
        assertThat(audit.recent(10)).extracting(IntegrationAccessAuditRecord::outcome)
                .containsOnly("DENIED");
    }

    @Test
    void rejectsMissingOrInvalidCredentialsWithoutTrustingIdentityHeaders() {
        HttpHeaders spoofed = new HttpHeaders();
        spoofed.set("X-Tenant-Id", "tenant-a");
        spoofed.set("X-Organization-Id", "org-a");
        spoofed.set("X-Environment-Id", "prod");
        spoofed.set("X-Actor-Id", "aneke-sync");
        spoofed.set("X-Purpose", "CHANGE_SYNC");

        assertThatThrownBy(() -> authenticator.authenticate(spoofed, IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(401);
                    assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.AUTHENTICATION_REQUIRED");
                });
        spoofed.setBearerAuth("wrong-token");
        assertThatThrownBy(() -> authenticator.authenticate(spoofed, IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.AUTHENTICATION_FAILED"));
        assertThat(audit.recent(10)).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.toString()).doesNotContain("wrong-token");
        });
    }

    @Test
    void failsClosedWhenNoEnterpriseOrDemoIdentityResolverIsAvailable() {
        IntegrationRequestAuthenticator unavailable = new IntegrationRequestAuthenticator(
                IntegrationIdentityResolver.unavailable(), new RecordingIntegrationAccessAuditRepository());

        assertThatThrownBy(() -> unavailable.authenticate(authorized("CHANGE_SYNC"),
                IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE");
                });
        assertThat(unavailable.descriptor().available()).isFalse();
    }

    @Test
    void returnsRetryableServiceUnavailableWhenIdentityAuthorityCannotDecide() {
        IntegrationIdentityResolver unavailableAuthority = new IntegrationIdentityResolver() {
            @Override
            public java.util.Optional<IntegrationWorkloadIdentity> resolve(String credential) {
                return java.util.Optional.empty();
            }

            @Override
            public ResolutionAttempt resolveAttempt(String credential) {
                return ResolutionAttempt.providerUnavailable();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("SIGNED_JWT", "DYNAMIC_JWKS", false, false, true);
            }
        };
        IntegrationRequestAuthenticator failClosed = new IntegrationRequestAuthenticator(
                unavailableAuthority, audit);

        assertThatThrownBy(() -> failClosed.authenticate(authorized("CHANGE_SYNC"),
                IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().retryable()).isTrue();
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE");
                });
        assertThat(audit.recent(1).getFirst()).extracting(IntegrationAccessAuditRecord::outcome,
                        IntegrationAccessAuditRecord::reasonCode)
                .containsExactly("DENIED", "RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE");
    }

    @Test
    void failsClosedWithStableRetryableProblemWhenSecurityAuditCannotCommit() {
        IntegrationAccessAuditRepository failingAudit = new IntegrationAccessAuditRepository() {
            @Override
            public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
                throw new IllegalStateException("audit store unavailable");
            }

            @Override
            public java.util.List<IntegrationAccessAuditRecord> recent(int limit) {
                return java.util.List.of();
            }
        };
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity("aneke", "tenant-a", "org-a",
                "project-a", "prod", "sg", "WORKLOAD", "sync", "", Set.of("CHANGE_SYNC"), Instant.MAX,
                true);
        IntegrationRequestAuthenticator failClosed = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false), failingAudit);

        assertThatThrownBy(() -> failClosed.authenticate(authorized("CHANGE_SYNC"),
                IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().retryable()).isTrue();
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE");
                });
    }

    @Test
    void boundsPurposeBeforeWritingSecurityAudit() {
        HttpHeaders headers = authorized("X".repeat(129));

        assertThatThrownBy(() -> authenticator.authenticate(headers, IntegrationOperation.CHANGE_SYNC))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code()).isEqualTo("RG.INTEGRATION.PURPOSE_INVALID");
                });
        assertThat(audit.recent(1).getFirst().purpose()).hasSize(128);
    }

    private static HttpHeaders authorized(String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", purpose);
        headers.set("X-Correlation-Id", "corr-auth-test");
        return headers;
    }
}
