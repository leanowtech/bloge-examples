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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowJobTransactionalIntegrationTest {

    @Test
    void successAuditFailureRollsBackJobAndLifecycleAdmission() {
        try (AnnotationConfigApplicationContext context =
                     context()) {
            ReadOnlyShadowJobService service =
                    context.getBean(
                            ReadOnlyShadowJobService.class);
            JdbcTemplate jdbc =
                    context.getBean(JdbcTemplate.class);

            assertThat(AopUtils.isCglibProxy(service))
                    .isTrue();
            assertThatThrownBy(() ->
                    service.submit(
                            ReadOnlyShadowJobTestFixtures
                                    .request(
                                            "shadow-audit-rollback",
                                            13),
                            ReadOnlyShadowJobTestFixtures
                                    .identity(
                                            "support",
                                            ReadOnlyShadowJobService
                                                    .EXECUTION_PURPOSE)))
                    .isInstanceOfSatisfying(
                            IntegrationProblemException.class,
                            failure -> assertThat(
                                    failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"));
            assertThat(count(
                    jdbc, "mirror_shadow_jobs"))
                    .isZero();
            assertThat(count(
                    jdbc,
                    "mirror_shadow_job_lifecycle"))
                    .isZero();
        }
    }

    private static AnnotationConfigApplicationContext
    context() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.register(
                TransactionConfiguration.class);
        context.registerBean(
                EmbeddedDatabase.class,
                () -> new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true)
                        .build(),
                definition ->
                        definition.setDestroyMethodName(
                                "shutdown"));
        context.registerBean(
                JdbcTemplate.class,
                () -> new JdbcTemplate(
                        context.getBean(
                                EmbeddedDatabase.class)));
        context.registerBean(
                ObjectMapper.class,
                () -> new ObjectMapper()
                        .findAndRegisterModules());
        context.registerBean(
                ReadOnlyShadowComparisonIntegrity.class,
                () -> ReadOnlyShadowJobTestFixtures
                        .integrity(
                                context.getBean(
                                        ObjectMapper.class)));
        context.registerBean(
                ReadOnlyShadowJobRepository.class,
                () -> new DatabaseReadOnlyShadowJobRepository(
                        context.getBean(
                                JdbcTemplate.class),
                        context.getBean(
                                ObjectMapper.class),
                        context.getBean(
                                ReadOnlyShadowComparisonIntegrity
                                        .class),
                        context.getBean(
                                PlatformTransactionManager
                                        .class)));
        context.registerBean(
                MirrorOperationAuditRepository.class,
                FailingAuditRepository::new);
        context.registerBean(
                MirrorOperationObservability.class,
                () -> new MirrorOperationObservability(
                        context.getBean(
                                MirrorOperationAuditRepository
                                        .class),
                        MirrorOperationTelemetry.noop(),
                        () -> 1L));
        context.registerBean(
                ReadOnlyShadowJobPolicy.class,
                () -> ReadOnlyShadowJobTestFixtures
                        .POLICY);
        context.registerBean(
                ReadOnlyShadowJobService.class,
                () -> new ReadOnlyShadowJobService(
                        context.getBean(
                                ReadOnlyShadowJobRepository
                                        .class),
                        context.getBean(
                                ReadOnlyShadowJobPolicy.class),
                        context.getBean(
                                MirrorOperationObservability
                                        .class)));
        context.refresh();
        return context;
    }

    private static int count(
            JdbcTemplate jdbc,
            String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static final class FailingAuditRepository
            implements MirrorOperationAuditRepository {
        @Override
        public MirrorOperationAuditEvent append(
                MirrorOperationAuditEvent event) {
            throw new IllegalStateException(
                    "audit unavailable");
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope,
                int limit) {
            return List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(
            proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(
                EmbeddedDatabase database) {
            return new DataSourceTransactionManager(
                    database);
        }
    }
}
