package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityObservationAdmissionTransactionTest {

    @Test
    void mandatoryAuditFailureRollsBackObservationAndAdmissionTogether() {
        try (var context = new AnnotationConfigApplicationContext(
                ConfigurationUnderTest.class)) {
            var service =
                    context.getBean(CapabilityObservationAdmissionService.class);
            var envelope =
                    context.getBean(CapabilityObservationEnvelope.class);
            var jdbc = context.getBean(JdbcTemplate.class);

            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThatThrownBy(() -> service.ingest(
                    envelope, CapabilityObservationTestFixtures.identity("org-a")))
                    .isInstanceOfSatisfying(
                            IntegrationProblemException.class, failure -> {
                                assertThat(failure.problem().status()).isEqualTo(503);
                                assertThat(failure.problem().code()).isEqualTo(
                                        "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
                                assertThat(failure.problem().details()).isEmpty();
                            });
            assertThat(count(jdbc, "mirror_capability_observations")).isZero();
        }
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ConfigurationUnderTest {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase database() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        JdbcTemplate jdbc(EmbeddedDatabase database) {
            return new JdbcTemplate(database);
        }

        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        InMemoryVisualEvidenceSigner signer() {
            return new InMemoryVisualEvidenceSigner();
        }

        @Bean
        CapabilitySnapshot capability(ObjectMapper mapper) {
            return CapabilityObservationTestFixtures.capability(
                    mapper, CapabilityObservationTestFixtures.scope("org-a"));
        }

        @Bean
        CapabilityObservationEnvelope observation(
                ObjectMapper mapper,
                InMemoryVisualEvidenceSigner signer,
                CapabilitySnapshot capability) {
            return CapabilityObservationTestFixtures.envelope(
                    mapper, signer, capability, "observation-transaction");
        }

        @Bean
        CapabilityObservationIntegrity observationIntegrity(ObjectMapper mapper) {
            return new CapabilityObservationIntegrity(mapper);
        }

        @Bean
        CapabilityObservationAdmissionIntegrity admissionIntegrity(
                ObjectMapper mapper) {
            return new CapabilityObservationAdmissionIntegrity(mapper);
        }

        @Bean
        CapabilityObservationRepository observations(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                CapabilityObservationIntegrity observationIntegrity,
                CapabilityObservationAdmissionIntegrity admissionIntegrity) {
            return new DatabaseCapabilityObservationRepository(
                    jdbc, mapper, observationIntegrity, admissionIntegrity);
        }

        @Bean
        CapabilitySnapshotRepository capabilities(CapabilitySnapshot capability) {
            return new CapabilitySnapshotRepository() {
                @Override
                public CapabilitySnapshot create(CapabilitySnapshot snapshot) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<CapabilitySnapshot> find(
                        CapabilitySnapshot.Scope scope,
                        String capabilityId,
                        long revision) {
                    return capability.scope().equals(scope)
                            && capability.capabilityId().equals(capabilityId)
                            && capability.revision() == revision
                            ? Optional.of(capability) : Optional.empty();
                }

                @Override
                public Optional<CapabilitySnapshot> findLatest(
                        CapabilitySnapshot.Scope scope, String capabilityId) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        CapabilityObservationAdmissionPolicyProvider policies(
                CapabilityObservationEnvelope envelope,
                InMemoryVisualEvidenceSigner signer) {
            CapabilityObservationIntegrity.AuthorityKey key =
                    CapabilityObservationTestFixtures.authorityKey(
                            envelope,
                            signer,
                            CapabilityObservationIntegrity.KeyState.ACTIVE);
            CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy =
                    CapabilityObservationTestFixtures.policy(envelope, key);
            return new CapabilityObservationAdmissionPolicyProvider() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public Optional<AdmissionPolicy> resolve(
                        CapabilitySnapshot.Scope scope,
                        MirrorArtifactRef capabilityRef,
                        MirrorArtifactRef grantRef,
                        String keyId) {
                    return Optional.of(policy);
                }
            };
        }

        @Bean
        CapabilityObservationPayloadReferenceVerifier payloadReferences() {
            return new CapabilityObservationPayloadReferenceVerifier() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public VerificationResult verify(
                        CapabilityObservationEnvelope envelope,
                        CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy,
                        java.time.Instant verificationTime) {
                    return VerificationResult.verified();
                }
            };
        }

        @Bean
        MirrorOperationAuditRepository failingAudit() {
            return new MirrorOperationAuditRepository() {
                @Override
                public MirrorOperationAuditEvent append(
                        MirrorOperationAuditEvent event) {
                    throw new IllegalStateException("sensitive-database-detail");
                }

                @Override
                public List<MirrorOperationAuditEvent> recent(
                        CapabilitySnapshot.Scope scope, int limit) {
                    return List.of();
                }
            };
        }

        @Bean
        MirrorOperationObservability operationObservability(
                MirrorOperationAuditRepository audit) {
            return new MirrorOperationObservability(
                    audit, MirrorOperationTelemetry.noop(), () -> 0);
        }

        @Bean
        CapabilityObservationAdmissionService service(
                CapabilityObservationRepository observations,
                CapabilitySnapshotRepository capabilities,
                CapabilityObservationAdmissionPolicyProvider policies,
                CapabilityObservationPayloadReferenceVerifier payloadReferences,
                CapabilityObservationIntegrity observationIntegrity,
                CapabilityObservationAdmissionIntegrity admissionIntegrity,
                MirrorOperationObservability operationObservability,
                ObjectMapper mapper,
                CapabilityObservationEnvelope envelope) {
            return new CapabilityObservationAdmissionService(
                    observations,
                    capabilities,
                    policies,
                    payloadReferences,
                    observationIntegrity,
                    admissionIntegrity,
                    operationObservability,
                    mapper,
                    Clock.fixed(
                            envelope.seal().signedAt().plusSeconds(1),
                            ZoneOffset.UTC));
        }
    }
}
