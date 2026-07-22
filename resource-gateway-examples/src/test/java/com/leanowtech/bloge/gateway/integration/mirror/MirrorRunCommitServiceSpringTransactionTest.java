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
            assertThatThrownBy(() -> commits.commit(claim.lease(), bundle))
                    .isInstanceOf(MirrorRunLeaseLostException.class);
            assertThat(evidence.find(scope, bundle.evidence().runId())).isEmpty();
            assertThat(requests.find(scope, registration.requestId())).get()
                    .extracting(MirrorRunRequestRepository.State::status)
                    .isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
        }
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.refresh();
        return context;
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
        MirrorRunCommitService commitService(
                MirrorEvidenceRepository evidence,
                MirrorRunRequestRepository requests) {
            return new MirrorRunCommitService(evidence, requests);
        }
    }
}
