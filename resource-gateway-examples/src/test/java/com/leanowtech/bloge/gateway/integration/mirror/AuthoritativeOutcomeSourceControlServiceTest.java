package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<MirrorOperationAuditEvent> audits = new ArrayList<>();
    private EmbeddedDatabase database;
    private DatabaseAuthoritativeOutcomeSourceCheckpointRepository repository;
    private MutableAuthority authority;
    private AuthoritativeOutcomeSourceControlService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        var transactions = new DataSourceTransactionManager(database);
        repository = new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                new JdbcTemplate(database), mapper, transactions, () -> NOW);
        repository.init();
        authority = new MutableAuthority();
        MirrorOperationAuditRepository audit = new MirrorOperationAuditRepository() {
            @Override
            public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
                audits.add(event);
                return event;
            }

            @Override
            public List<MirrorOperationAuditEvent> recent(
                    CapabilitySnapshot.Scope scope, int limit) {
                return List.copyOf(audits);
            }
        };
        service = new AuthoritativeOutcomeSourceControlService(
                repository, authority,
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 1_000_000L),
                mapper, transactions);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void registersExactBackfillReplayAndAuditsBothOutcomes() {
        var command = AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);

        var first = service.registerBackfill(command, admin());
        var replay = service.registerBackfill(command, admin());

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(first.snapshot().schemaVersion())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository
                        .SNAPSHOT_SCHEMA_VERSION);
        assertThat(authority.verifications).hasValue(2);
        assertThat(audits)
                .extracting(MirrorOperationAuditEvent::operation)
                .containsOnly(MirrorOperationAuditEvent.Operation
                        .OUTCOME_SOURCE_BACKFILL_REGISTER);
        assertThat(audits)
                .extracting(MirrorOperationAuditEvent::outcome)
                .containsOnly(MirrorOperationAuditEvent.Outcome.SUCCEEDED);
    }

    @Test
    void deniesWrongPurposeAndScopeWithoutLeakingCheckpointExistence() {
        var command = AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);

        assertProblem(
                () -> service.registerBackfill(command, identity(
                        "support", DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                        "staging")),
                403, "RG.MIRROR.OUTCOME_SOURCE.PURPOSE_FORBIDDEN");
        assertProblem(
                () -> service.registerBackfill(command, identity(
                        "another-organization",
                        AuthoritativeOutcomeSourceControlService.ADMIN_PURPOSE,
                        "staging")),
                404, "RG.MIRROR.OUTCOME_SOURCE.COMMAND_SCOPE_NOT_FOUND");

        assertThat(authority.verifications).hasValue(0);
        assertThat(audits)
                .extracting(MirrorOperationAuditEvent::outcome)
                .containsOnly(MirrorOperationAuditEvent.Outcome.REJECTED);
    }

    @Test
    void rejectsExpiredProductionAndExternallyDeniedCommandsFailClosed() {
        assertProblem(
                () -> service.registerBackfill(
                        expiredBackfill(), admin()),
                410, "RG.MIRROR.OUTCOME_SOURCE.COMMAND_EXPIRED");
        assertProblem(
                () -> service.registerBackfill(
                        AuthoritativeOutcomeSourceTestFixtures.backfill(mapper),
                        identity("support",
                                AuthoritativeOutcomeSourceControlService.ADMIN_PURPOSE,
                                "production")),
                403, "RG.MIRROR.OUTCOME_SOURCE.ENVIRONMENT_FORBIDDEN");

        authority.reject = true;
        assertProblem(
                () -> service.registerBackfill(
                        AuthoritativeOutcomeSourceTestFixtures.backfill(mapper), admin()),
                403, "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_REJECTED");
        authority.reject = false;
        authority.available = false;
        assertProblem(
                () -> service.registerBackfill(
                        AuthoritativeOutcomeSourceTestFixtures.backfill(mapper), admin()),
                503, "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_UNAVAILABLE");
    }

    @Test
    void generationRevocationFencesLiveAndReadIsExactScope() {
        repository.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());

        var revoked = service.revokeGeneration(
                AuthoritativeOutcomeSourceTestFixtures.revoke(mapper), admin());
        var checkpoint = service.find(
                "settlement-ledger", 7,
                AuthoritativeOutcomeSourcePage.StreamKind.LIVE, "live",
                identity("support", DomainFidelityPolicy.GOVERNANCE_PURPOSE, "staging"));

        assertThat(revoked.affectedStreamCount()).isEqualTo(1);
        assertThat(checkpoint.status())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.REVOKED);
        assertProblem(
                () -> service.find("settlement-ledger", 7,
                        AuthoritativeOutcomeSourcePage.StreamKind.LIVE, "live",
                        identity("other", DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                                "staging")),
                404, "RG.MIRROR.OUTCOME_SOURCE.CHECKPOINT_NOT_FOUND");
    }

    private AuthoritativeOutcomeConnectorControlCommand expiredBackfill() {
        var base = AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);
        var addressed = new AuthoritativeOutcomeConnectorControlCommand(
                "", base.commandId(), base.revision(), "", base.scope(),
                base.connectorId(), base.connectorGeneration(), base.commandType(),
                base.streamId(), base.eventTimeRange(), base.baselinePageFingerprint(),
                base.baselineCursorRef(), base.reasonCode(),
                NOW.minusSeconds(600), NOW.minusSeconds(1),
                VisualRunEvidenceSeal.unsigned()).seal(mapper);
        return addressed.withAuthoritySeal(
                AuthoritativeOutcomeSourceTestFixtures.signedSeal(
                        addressed.commandFingerprint()));
    }

    private static IntegrationRequestContext admin() {
        return identity("support",
                AuthoritativeOutcomeSourceControlService.ADMIN_PURPOSE, "staging");
    }

    private static IntegrationRequestContext identity(
            String organization, String purpose, String environment) {
        return new IntegrationRequestContext(
                "tenant-a", organization, "refunds", environment, "sg",
                "SERVICE", "aneke-control", "", purpose, "correlation-source",
                Set.of("mirror-admin"), "CONFIDENTIAL", "");
    }

    private static void assertProblem(
            Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status()).isEqualTo(status);
                            assertThat(failure.problem().code()).isEqualTo(code);
                        });
    }

    private static final class MutableAuthority
            implements AuthoritativeOutcomeSourceAuthorityVerifier {
        private final AtomicReference<Integer> verifications =
                new AtomicReference<>(0);
        private boolean available = true;
        private boolean reject;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void verifyPage(AuthoritativeOutcomeSourcePage page) {
        }

        @Override
        public void verifyCommand(AuthoritativeOutcomeConnectorControlCommand command) {
            verifications.updateAndGet(value -> value + 1);
            if (reject) {
                throw new IllegalArgumentException("external authority rejection");
            }
        }
    }
}
