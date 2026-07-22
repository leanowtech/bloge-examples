package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorOperationTransactionBoundaryTest {

    @Test
    void failureAuditSurvivesTheBusinessRollbackThatItExplains() {
        try (AnnotationConfigApplicationContext context = context()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            MirrorOperationAuditRepository audit =
                    context.getBean(MirrorOperationAuditRepository.class);
            MirrorOperationObservability observations =
                    context.getBean(MirrorOperationObservability.class);
            TransactionProbe probe = context.getBean(TransactionProbe.class);
            MirrorOperationObservability.Observation observation = observations.start(
                    MirrorOperationAuditEvent.Operation.PLAN_CREATE,
                    MirrorPersistenceTestFixtures.identity("org-a"), "", "plan-1", "");

            assertThatThrownBy(() -> probe.failAfterMutation(observation))
                    .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                            assertThat(failure.problem().code())
                                    .isEqualTo("RG.MIRROR.PLAN_CONFLICT"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_marker", Integer.class))
                    .isZero();
            assertThat(audit.recent(MirrorPersistenceTestFixtures.scope("org-a"), 10))
                    .singleElement().satisfies(event -> {
                        assertThat(event.outcome())
                                .isEqualTo(MirrorOperationAuditEvent.Outcome.REJECTED);
                        assertThat(event.reason())
                                .isEqualTo(MirrorOperationAuditEvent.Reason.CONFLICT);
                        assertThat(event.reasonCode()).isEqualTo("RG.MIRROR.PLAN_CONFLICT");
                    });
        }
    }

    @Test
    void successAuditRollsBackAtomicallyWithTheUnpublishedBusinessResult() {
        try (AnnotationConfigApplicationContext context = context()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            MirrorOperationAuditRepository audit =
                    context.getBean(MirrorOperationAuditRepository.class);
            MirrorOperationObservability observations =
                    context.getBean(MirrorOperationObservability.class);
            TransactionProbe probe = context.getBean(TransactionProbe.class);
            MirrorOperationObservability.Observation observation = observations.start(
                    MirrorOperationAuditEvent.Operation.PLAN_CREATE,
                    MirrorPersistenceTestFixtures.identity("org-a"), "", "plan-1", "");

            assertThatThrownBy(() -> probe.succeedThenRollback(observation))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("simulated publication rollback");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_marker", Integer.class))
                    .isZero();
            assertThat(audit.recent(MirrorPersistenceTestFixtures.scope("org-a"), 10)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.refresh();
        context.getBean(JdbcTemplate.class).execute("""
                CREATE TABLE business_marker (
                    marker_id VARCHAR(64) PRIMARY KEY
                )
                """);
        return context;
    }

    static class TransactionProbe {
        private final JdbcTemplate jdbc;

        TransactionProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void failAfterMutation(MirrorOperationObservability.Observation observation) {
            jdbc.update("INSERT INTO business_marker (marker_id) VALUES ('failed')");
            throw observation.failed(new IntegrationProblemException(
                    IntegrationProblem.conflict("RG.MIRROR.PLAN_CONFLICT",
                            "The reviewed plan has changed.", "corr-mirror-test", Map.of())));
        }

        @Transactional
        public void succeedThenRollback(MirrorOperationObservability.Observation observation) {
            jdbc.update("INSERT INTO business_marker (marker_id) VALUES ('succeeded')");
            observation.succeeded("");
            throw new IllegalStateException("simulated publication rollback");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true).build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MirrorOperationAuditRepository auditRepository(JdbcTemplate jdbc) {
            return new DatabaseMirrorOperationAuditRepository(jdbc);
        }

        @Bean
        MirrorOperationFailureAuditService failureAudit(
                MirrorOperationAuditRepository audit,
                PlatformTransactionManager transactionManager) {
            return new MirrorOperationFailureAuditService(audit, transactionManager);
        }

        @Bean
        MirrorOperationObservability observability(
                MirrorOperationAuditRepository audit,
                MirrorOperationFailureAuditService failureAudit) {
            return new MirrorOperationObservability(
                    audit, failureAudit, MirrorOperationTelemetry.noop());
        }

        @Bean
        TransactionProbe transactionProbe(JdbcTemplate jdbc) {
            return new TransactionProbe(jdbc);
        }
    }
}
