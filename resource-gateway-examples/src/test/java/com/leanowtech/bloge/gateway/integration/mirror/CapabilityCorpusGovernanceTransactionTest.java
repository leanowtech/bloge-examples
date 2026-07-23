package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusGovernanceTransactionTest {

    @Test
    void mandatoryAuditFailureRollsBackQuarantineReview() {
        try (var context = context()) {
            CapabilityObservationRepository observations =
                    context.getBean(CapabilityObservationRepository.class);
            CapabilityObservationRepository.StoredObservation quarantined =
                    CapabilityCorpusTestFixtures.quarantined(
                            context.getBean(ObjectMapper.class),
                            CapabilityObservationTestFixtures.scope("org-a"),
                            "observation-review-transaction");
            observations.append(quarantined);

            assertAuditFailure(() -> context.getBean(
                    CapabilityCorpusGovernanceService.class).reviewQuarantine(
                    CapabilityCorpusTestFixtures.reviewRequest(quarantined),
                    identity(Set.of("corpus-reviewers"))));

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(count(jdbc, "mirror_capability_observation_reviews"))
                    .isZero();
            assertThat(count(jdbc, "mirror_capability_observations")).isOne();
        }
    }

    @Test
    void mandatoryAuditFailureRollsBackCorpusCandidate() {
        try (var context = context()) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            CapabilityObservationRepository observations =
                    context.getBean(CapabilityObservationRepository.class);
            CapabilityObservationRepository.StoredObservation admitted =
                    CapabilityCorpusTestFixtures.admitted(
                            mapper,
                            CapabilityObservationTestFixtures.scope("org-a"),
                            "observation-candidate-transaction");
            observations.append(admitted);

            assertAuditFailure(() -> context.getBean(
                    CapabilityCorpusGovernanceService.class).createCandidate(
                    CapabilityCorpusTestFixtures.candidateRequest(
                            "transaction-corpus", 1, null, List.of(admitted)),
                    identity(Set.of("corpus-curators"))));

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(count(jdbc, "mirror_capability_corpus_revisions"))
                    .isZero();
            assertThat(count(jdbc, "mirror_capability_observations")).isOne();
        }
    }

    @Test
    void mandatoryAuditFailureRollsBackPublicationButPreservesCandidate() {
        try (var context = context()) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            CapabilityObservationRepository observations =
                    context.getBean(CapabilityObservationRepository.class);
            CapabilityObservationRepository.StoredObservation admitted =
                    CapabilityCorpusTestFixtures.admitted(
                            mapper,
                            CapabilityObservationTestFixtures.scope("org-a"),
                            "observation-publication-transaction");
            observations.append(admitted);
            CapabilityCorpusRevision revision =
                    CapabilityCorpusTestFixtures.revision(
                            mapper,
                            admitted,
                            "transaction-corpus",
                            1,
                            null,
                            admitted.admission().decidedAt().plusSeconds(2));
            context.getBean(CapabilityCorpusRepository.class)
                    .appendRevision(revision);

            CapabilityCorpusPublishRequest request =
                    new CapabilityCorpusPublishRequest(
                            "",
                            revision.corpusId(),
                            1,
                            null,
                            revision.artifactRef(),
                            CapabilityObservationTestFixtures.ref(
                                    "GOVERNANCE_REVIEW_TICKET",
                                    "transaction-publication-review",
                                    1,
                                    '7'),
                            "OWNER_APPROVED");
            assertAuditFailure(() -> context.getBean(
                    CapabilityCorpusGovernanceService.class).publish(
                    request, identity(Set.of("corpus-publishers"))));

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(count(jdbc, "mirror_capability_corpus_publications"))
                    .isZero();
            assertThat(count(jdbc, "mirror_capability_corpus_revisions"))
                    .isOne();
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext(
                ConfigurationUnderTest.class);
        assertThat(AopUtils.isAopProxy(context.getBean(
                CapabilityCorpusGovernanceService.class))).isTrue();
        return context;
    }

    private static com.leanowtech.bloge.gateway.integration.IntegrationRequestContext
            identity(Set<String> groups) {
        return CapabilityCorpusTestFixtures.identity("org-a", groups);
    }

    private static void assertAuditFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code()).isEqualTo(
                            "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
                    assertThat(failure.problem().details()).isEmpty();
                });
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
        PlatformTransactionManager transactionManager(
                EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CapabilityObservationIntegrity observationIntegrity(
                ObjectMapper mapper) {
            return new CapabilityObservationIntegrity(mapper);
        }

        @Bean
        CapabilityObservationAdmissionIntegrity admissionIntegrity(
                ObjectMapper mapper) {
            return new CapabilityObservationAdmissionIntegrity(mapper);
        }

        @Bean
        CapabilityCorpusIntegrity corpusIntegrity(ObjectMapper mapper) {
            return new CapabilityCorpusIntegrity(mapper);
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
        CapabilityObservationReviewRepository reviews(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                CapabilityCorpusIntegrity integrity) {
            return new DatabaseCapabilityObservationReviewRepository(
                    jdbc, mapper, integrity);
        }

        @Bean
        CapabilityCorpusRepository corpora(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                CapabilityCorpusIntegrity integrity) {
            return new DatabaseCapabilityCorpusRepository(
                    jdbc, mapper, integrity);
        }

        @Bean
        CapabilityCorpusGovernancePolicyProvider policies() {
            return new CapabilityCorpusGovernancePolicyProvider() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public Optional<GovernancePolicy> resolve(
                        CapabilitySnapshot.Scope scope,
                        MirrorArtifactRef capabilityRef) {
                    return Optional.of(new GovernancePolicy(
                            scope,
                            capabilityRef,
                            CapabilityObservationTestFixtures.ref(
                                    "CORPUS_GOVERNANCE_POLICY",
                                    "support-corpus-policy",
                                    2,
                                    '1'),
                            CapabilityObservationTestFixtures.ref(
                                    "CORPUS_PUBLICATION_POLICY",
                                    "support-publication-policy",
                                    4,
                                    '2'),
                            Set.of("corpus-reviewers"),
                            Set.of("corpus-publishers"),
                            1,
                            1_000,
                            10_000,
                            1,
                            java.time.Duration.ofHours(1)));
                }
            };
        }

        @Bean
        CapabilityCorpusSourceVerifier sourceVerifier() {
            return new CapabilityCorpusSourceVerifier() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public VerificationResult verify(
                        CapabilityObservationRepository.StoredObservation source,
                        CapabilityCorpusGovernancePolicyProvider
                                .GovernancePolicy policy,
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
                    throw new IllegalStateException(
                            "sensitive-database-detail");
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
        CapabilityCorpusGovernanceService service(
                CapabilityObservationRepository observations,
                CapabilityObservationReviewRepository reviews,
                CapabilityCorpusRepository corpora,
                CapabilityCorpusGovernancePolicyProvider policies,
                CapabilityCorpusSourceVerifier sourceVerifier,
                CapabilityCorpusIntegrity integrity,
                MirrorOperationObservability observability) {
            return new CapabilityCorpusGovernanceService(
                    observations,
                    reviews,
                    corpora,
                    policies,
                    sourceVerifier,
                    integrity,
                    observability,
                    Clock.systemUTC());
        }
    }
}
