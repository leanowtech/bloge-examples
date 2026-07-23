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

class MirrorDeploymentIsolationAuthorityPublicationTransactionTest {

    @Test
    void mandatoryAuditFailureRollsBackPublicationAndTrustedFloorTogether() {
        try (var context = new AnnotationConfigApplicationContext(ConfigurationUnderTest.class)) {
            var fixtures = context.getBean(
                    MirrorDeploymentIsolationAuthorityPublicationTestFixtures.class);
            var service = context.getBean(
                    MirrorDeploymentIsolationAuthorityPublicationService.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var publication = fixtures.publication(1, "");

            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThatThrownBy(() -> service.publish(publication, identity()))
                    .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                        assertThat(failure.problem().status()).isEqualTo(503);
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
                        assertThat(failure.problem().details()).isEmpty();
                    });
            assertThat(count(jdbc, "mirror_isolation_authority_publications")).isZero();
            assertThat(count(jdbc, "mirror_isolation_authority_trusted_floors")).isZero();
        }
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
        MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures() {
            return new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();
        }

        @Bean
        ObjectMapper objectMapper(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures) {
            return fixtures.mapper;
        }

        @Bean
        MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures) {
            return fixtures.integrity;
        }

        @Bean
        MirrorDeploymentIsolationAuthorityPublicationRepository publications(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
                PlatformTransactionManager transactionManager) {
            return new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                    jdbc, mapper, integrity, transactionManager);
        }

        @Bean
        MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures) {
            return fixtures.provider();
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
        MirrorDeploymentIsolationAuthorityPublicationService service(
                MirrorDeploymentIsolationAuthorityPublicationRepository publications,
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies,
                MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
                MirrorOperationObservability observations,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures) {
            return new MirrorDeploymentIsolationAuthorityPublicationService(
                    publications, trustPolicies, integrity, observations, fixtures.activeClock);
        }
    }
}
