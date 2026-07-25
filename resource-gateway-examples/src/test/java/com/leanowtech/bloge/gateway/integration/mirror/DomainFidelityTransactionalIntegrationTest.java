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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainFidelityTransactionalIntegrationTest {

    @Test
    void successAuditFailureRollsBackTheInventoryAppend() {
        try (AnnotationConfigApplicationContext context =
                     failingAuditContext()) {
            DomainFidelityService service =
                    context.getBean(DomainFidelityService.class);
            DomainFidelityRepository repository =
                    context.getBean(DomainFidelityRepository.class);
            JdbcTemplate jdbc =
                    context.getBean(JdbcTemplate.class);

            assertThat(AopUtils.isCglibProxy(service)).isTrue();
            assertThat(AopUtils.isCglibProxy(repository)).isTrue();
            assertThatThrownBy(() ->
                    service.registerInventory(
                            DomainFidelityTestFixtures.registration(
                                    1,
                                    "",
                                    DomainFidelityTestFixtures.units()),
                            DomainFidelityTestFixtures
                                    .ownerIdentity("support")))
                    .isInstanceOf(
                            IntegrationProblemException.class)
                    .extracting(failure ->
                            ((IntegrationProblemException) failure)
                                    .problem().code())
                    .isEqualTo(
                            "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mirror_domain_fidelity_inventories",
                    Integer.class))
                    .isZero();
        }
    }

    private static AnnotationConfigApplicationContext
    failingAuditContext() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
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
                DomainFidelityPolicy.class,
                DomainFidelityTestFixtures::policy);
        context.registerBean(
                DomainFidelityProfileIntegrity.class,
                () -> new DomainFidelityProfileIntegrity(
                        context.getBean(ObjectMapper.class),
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        DomainFidelityTestFixtures.CLOCK));
        context.registerBean(
                DomainFidelityRepository.class,
                () -> new DatabaseDomainFidelityRepository(
                        context.getBean(JdbcTemplate.class),
                        context.getBean(ObjectMapper.class),
                        context.getBean(
                                DomainFidelityProfileIntegrity
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
                DomainFidelityService.class,
                () -> new DomainFidelityService(
                        context.getBean(
                                DomainFidelityRepository.class),
                        context.getBean(
                                DomainFidelityPolicy.class),
                        context.getBean(
                                DomainFidelityProfileIntegrity
                                        .class),
                        context.getBean(ObjectMapper.class),
                        context.getBean(
                                MirrorOperationObservability
                                        .class),
                        DomainFidelityTestFixtures.CLOCK));
        context.refresh();
        return context;
    }

    private static final class FailingAuditRepository
            implements MirrorOperationAuditRepository {
        @Override
        public MirrorOperationAuditEvent append(
                MirrorOperationAuditEvent event) {
            throw new IllegalStateException(
                    "audit store unavailable");
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(
                EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }
    }
}
