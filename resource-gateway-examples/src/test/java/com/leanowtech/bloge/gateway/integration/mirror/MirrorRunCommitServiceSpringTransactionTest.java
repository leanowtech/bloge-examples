package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorRunCommitServiceSpringTransactionTest {

    @Test
    void springProxyRollsBackEvidenceWhenDatabaseClockRejectsExpiredCompletion()
            throws Exception {
        try (AnnotationConfigApplicationContext context = context()) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            VisualEvidenceSigner signer = context.getBean(VisualEvidenceSigner.class);
            MirrorRunRequestRepository requests =
                    context.getBean(MirrorRunRequestRepository.class);
            MirrorEvidenceRepository evidence = context.getBean(MirrorEvidenceRepository.class);
            MirrorRunCommitService commits = context.getBean(MirrorRunCommitService.class);
            CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-spring");
            MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                    mapper, scope, "plan-spring-transaction", '1');
            MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                    mapper, signer, plan, "run-spring-transaction", '2');
            MirrorRunRequestRepository.Registration registration =
                    new MirrorRunRequestRepository.Registration(scope,
                            bundle.evidence().requestId(),
                            MirrorPersistenceTestFixtures.fingerprint('3'),
                            bundle.evidence().requestContextFingerprint(), plan.planId(),
                            plan.planFingerprint(),
                            MirrorPersistenceTestFixtures.COMPILED_AT.plus(Duration.ofDays(30)));
            MirrorRunRequestRepository.Claim claim = requests.claim(
                    registration, "owner-spring", Duration.ofMillis(5));
            Thread.sleep(20);

            assertThat(AopUtils.isCglibProxy(commits)).isTrue();
            assertThatThrownBy(() -> commits.commit(
                    claim.lease(), bundle, observation(scope)))
                    .isInstanceOf(MirrorRunLeaseLostException.class);
            assertThat(evidence.find(scope, bundle.evidence().runId())).isEmpty();
            assertThat(requests.find(scope, registration.requestId())).get()
                    .extracting(MirrorRunRequestRepository.State::status)
                    .isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
        }
    }

    @Test
    void springProxyCommitsEvidenceRequestStateAndSuccessAuditAtomically() {
        try (AnnotationConfigApplicationContext context = context()) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            VisualEvidenceSigner signer = context.getBean(VisualEvidenceSigner.class);
            MirrorRunRequestRepository requests =
                    context.getBean(MirrorRunRequestRepository.class);
            MirrorEvidenceRepository evidence = context.getBean(MirrorEvidenceRepository.class);
            MirrorOperationAuditRepository audit =
                    context.getBean(MirrorOperationAuditRepository.class);
            MirrorOperationObservability observations =
                    context.getBean(MirrorOperationObservability.class);
            MirrorRunCommitService commits = context.getBean(MirrorRunCommitService.class);
            CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-success");
            MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                    mapper, scope, "plan-success", '4');
            MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                    mapper, signer, plan, "run-success", '5');
            MirrorRunRequestRepository.Claim claim = requests.claim(
                    registration(plan, bundle), "owner-success", Duration.ofMinutes(1));
            MirrorOperationObservability.Observation observation = observations.start(
                    MirrorOperationAuditEvent.Operation.RUN_CREATE,
                    MirrorPersistenceTestFixtures.identity("org-success"),
                    bundle.evidence().requestId(), plan.planId(), "");

            assertThat(commits.commit(claim.lease(), bundle, observation)).isEqualTo(bundle);

            assertThat(evidence.find(scope, bundle.evidence().runId())).contains(bundle);
            assertThat(requests.find(scope, bundle.evidence().requestId())).get()
                    .extracting(MirrorRunRequestRepository.State::status)
                    .isEqualTo(MirrorRunRequestRepository.Status.COMPLETED);
            assertThat(audit.recent(scope, 10)).singleElement().satisfies(event -> {
                assertThat(event.operation())
                        .isEqualTo(MirrorOperationAuditEvent.Operation.RUN_CREATE);
                assertThat(event.outcome())
                        .isEqualTo(MirrorOperationAuditEvent.Outcome.SUCCEEDED);
                assertThat(event.runId()).isEqualTo("run-success");
            });
        }
    }

    @Test
    void mandatorySuccessAuditFailureRollsBackEvidenceAndRequestCompletion() {
        try (AnnotationConfigApplicationContext context = context()) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            VisualEvidenceSigner signer = context.getBean(VisualEvidenceSigner.class);
            MirrorRunRequestRepository requests =
                    context.getBean(MirrorRunRequestRepository.class);
            MirrorEvidenceRepository evidence = context.getBean(MirrorEvidenceRepository.class);
            ControlledAuditRepository audit = context.getBean(ControlledAuditRepository.class);
            MirrorOperationObservability observations =
                    context.getBean(MirrorOperationObservability.class);
            MirrorRunCommitService commits = context.getBean(MirrorRunCommitService.class);
            CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-audit-down");
            MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                    mapper, scope, "plan-audit-down", '6');
            MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                    mapper, signer, plan, "run-audit-down", '7');
            MirrorRunRequestRepository.Claim claim = requests.claim(
                    registration(plan, bundle), "owner-audit-down", Duration.ofMinutes(1));
            MirrorOperationObservability.Observation observation = observations.start(
                    MirrorOperationAuditEvent.Operation.RUN_CREATE,
                    MirrorPersistenceTestFixtures.identity("org-audit-down"),
                    bundle.evidence().requestId(), plan.planId(), "");
            audit.fail.set(true);

            assertThatThrownBy(() -> commits.commit(claim.lease(), bundle, observation))
                    .isInstanceOfSatisfying(
                            com.leanowtech.bloge.gateway.integration.IntegrationProblemException.class,
                            failure -> assertThat(failure.problem().code())
                                    .isEqualTo("RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"));

            assertThat(evidence.find(scope, bundle.evidence().runId())).isEmpty();
            assertThat(requests.find(scope, bundle.evidence().requestId())).get()
                    .extracting(MirrorRunRequestRepository.State::status)
                    .isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
        }
    }

    private static MirrorRunRequestRepository.Registration registration(
            MirrorPlan plan, MirrorEvidenceBundle bundle) {
        return new MirrorRunRequestRepository.Registration(plan.scope(),
                bundle.evidence().requestId(), MirrorPersistenceTestFixtures.fingerprint('8'),
                bundle.evidence().requestContextFingerprint(), plan.planId(),
                plan.planFingerprint(),
                MirrorPersistenceTestFixtures.COMPILED_AT.plus(Duration.ofDays(30)));
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.refresh();
        return context;
    }

    private static MirrorOperationObservability.Observation observation(
            CapabilitySnapshot.Scope scope) {
        return MirrorOperationObservability.noop().start(
                MirrorOperationAuditEvent.Operation.RUN_CREATE,
                MirrorPersistenceTestFixtures.identity(scope.organizationId()),
                "request-1", "plan-1", "");
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
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        VisualEvidenceSigner evidenceSigner() {
            return new InMemoryVisualEvidenceSigner();
        }

        @Bean
        MirrorEvidenceIntegrityService evidenceIntegrity(
                ObjectMapper mapper, VisualEvidenceSigner signer) {
            return new MirrorEvidenceIntegrityService(mapper, signer,
                    Clock.fixed(MirrorPersistenceTestFixtures.COMPILED_AT.plusSeconds(30),
                            ZoneOffset.UTC));
        }

        @Bean
        MirrorEvidenceRepository evidenceRepository(
                JdbcTemplate jdbc, ObjectMapper mapper,
                MirrorEvidenceIntegrityService integrity) {
            return new DatabaseMirrorEvidenceRepository(jdbc, mapper, integrity);
        }

        @Bean
        MirrorRunRequestRepository requestRepository(JdbcTemplate jdbc) {
            return new DatabaseMirrorRunRequestRepository(jdbc);
        }

        @Bean
        ControlledAuditRepository operationAuditRepository(JdbcTemplate jdbc) {
            return new ControlledAuditRepository(jdbc);
        }

        @Bean
        MirrorOperationFailureAuditService failureAudit(
                ControlledAuditRepository audit,
                PlatformTransactionManager transactionManager) {
            return new MirrorOperationFailureAuditService(audit, transactionManager);
        }

        @Bean
        MirrorOperationObservability observability(
                ControlledAuditRepository audit,
                MirrorOperationFailureAuditService failureAudit) {
            return new MirrorOperationObservability(
                    audit, failureAudit, MirrorOperationTelemetry.noop());
        }

        @Bean
        MirrorRunCommitService commitService(
                MirrorEvidenceRepository evidence,
                MirrorRunRequestRepository requests) {
            return new MirrorRunCommitService(evidence, requests);
        }
    }

    static final class ControlledAuditRepository implements MirrorOperationAuditRepository {
        private final DatabaseMirrorOperationAuditRepository delegate;
        private final AtomicBoolean fail = new AtomicBoolean();

        ControlledAuditRepository(JdbcTemplate jdbc) {
            delegate = new DatabaseMirrorOperationAuditRepository(jdbc);
            delegate.init();
        }

        @Override
        public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
            if (fail.get()) {
                throw new IllegalStateException("audit store unavailable");
            }
            return delegate.append(event);
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return delegate.recent(scope, limit);
        }
    }
}
