package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAttestationTransactionTest {

    @Test
    void mandatoryAuditFailureRollsBackBodyInitialStatusAndFloorTogether() {
        try (var context = new AnnotationConfigApplicationContext(ConfigurationUnderTest.class)) {
            var fixtures = context.getBean(
                    MirrorDeploymentIsolationAttestationRepositoryTestFixtures.class);
            var authorities = context.getBean(
                    MirrorDeploymentIsolationAuthorityPublicationRepository.class);
            authorities.append(fixtures.authorityPublication());
            var service = context.getBean(MirrorDeploymentIsolationAttestationService.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var attestation = fixtures.attestation(7, fixtures.deployment("cluster-a"),
                    MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));

            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertAuditFailure(() -> service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                    attestation, identity()));
            assertThat(count(jdbc, "mirror_isolation_attestations")).isZero();
            assertThat(count(jdbc, "mirror_isolation_attestation_statuses")).isZero();
            assertThat(count(jdbc, "mirror_isolation_attestation_heads")).isZero();
        }
    }

    @Test
    void mandatoryAuditFailureRollsBackRevocationStatusAndHeadCasTogether() {
        try (var context = new AnnotationConfigApplicationContext(ConfigurationUnderTest.class)) {
            var fixtures = context.getBean(
                    MirrorDeploymentIsolationAttestationRepositoryTestFixtures.class);
            var attestations = context.getBean(
                    MirrorDeploymentIsolationAttestationRepository.class);
            var active = attestations.append(fixtures.bundle(7), 7);
            var service = context.getBean(MirrorDeploymentIsolationAttestationService.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var request = new MirrorDeploymentIsolationAttestationRevocationRequest("", 7,
                    active.attestation().attestationFingerprint(), 1,
                    active.status().statusFingerprint(),
                    MirrorDeploymentIsolationAttestationStatusPublication.Reason.SECURITY_INCIDENT);

            assertAuditFailure(() -> service.revoke("deployment:staging", fixtures.KEY_SET_ID,
                    fixtures.ATTESTATION_ID, request, identity()));
            assertThat(count(jdbc, "mirror_isolation_attestation_statuses")).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT status_revision FROM mirror_isolation_attestation_heads
                    """, Long.class)).isEqualTo(1L);
            assertThat(attestations.current(
                    MirrorDeploymentIsolationAttestationRepository.StreamIdentity.from(active)))
                    .contains(active);
        }
    }

    private static void assertAuditFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "trust-admin", "", "MIRROR_TRUST_ADMIN",
                "corr-transaction", Set.of(), "RESTRICTED", "");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ConfigurationUnderTest {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase database() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true).build();
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
        MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures() {
            return new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
        }

        @Bean
        ObjectMapper objectMapper(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.mapper;
        }

        @Bean
        MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.authorityIntegrity;
        }

        @Bean
        MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.attestationIntegrity;
        }

        @Bean
        MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.bundleIntegrity;
        }

        @Bean
        MirrorDeploymentIsolationAuthorityPublicationRepository authorities(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
                PlatformTransactionManager transactions) {
            return new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                    jdbc, mapper, integrity, transactions);
        }

        @Bean
        MirrorDeploymentIsolationAttestationRepository attestations(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
                MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
                PlatformTransactionManager transactions) {
            return new DatabaseMirrorDeploymentIsolationAttestationRepository(
                    jdbc, mapper, attestationIntegrity, bundleIntegrity, transactions);
        }

        @Bean
        MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicies(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.authorityPolicyProvider();
        }

        @Bean
        MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies(
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return fixtures.admissionPolicyProvider();
        }

        @Bean
        MirrorOperationAuditRepository failingAudit() {
            return new MirrorOperationAuditRepository() {
                @Override
                public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
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
        MirrorOperationObservability observations(MirrorOperationAuditRepository audit) {
            return new MirrorOperationObservability(
                    audit, MirrorOperationTelemetry.noop(), () -> 0);
        }

        @Bean
        MirrorDeploymentIsolationAttestationService service(
                MirrorDeploymentIsolationAttestationRepository attestations,
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies,
                MirrorDeploymentIsolationAuthorityPublicationRepository authorities,
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicies,
                MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
                MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
                MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
                MirrorOperationObservability observations,
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures) {
            return new MirrorDeploymentIsolationAttestationService(
                    attestations, admissionPolicies, authorities, authorityPolicies,
                    authorityIntegrity, attestationIntegrity, bundleIntegrity, observations,
                    fixtures.activeClock);
        }
    }
}
