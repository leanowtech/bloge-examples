package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalCommitServiceTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");
    private static final Instant NOW =
            ScenarioRehearsalEvidenceTestFixtures.STARTED;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AtomicReference<Instant> databaseTime;
    private DatabaseScenarioRehearsalRunRepository requests;
    private DatabaseScenarioRehearsalEvidenceRepository evidence;
    private DatabaseMirrorOperationAuditRepository audit;
    private ScenarioRehearsalCommitService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database));
        databaseTime = new AtomicReference<>(NOW);
        requests = new DatabaseScenarioRehearsalRunRepository(
                jdbc, mapper, databaseTime::get);
        requests.init();
        evidence = new DatabaseScenarioRehearsalEvidenceRepository(
                jdbc, mapper, integrity());
        evidence.init();
        audit = new DatabaseMirrorOperationAuditRepository(jdbc);
        audit.init();
        service = new ScenarioRehearsalCommitService(
                evidence, requests);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void atomicallyStoresEvidenceAndTerminalizesTheExactRequest() {
        ScenarioRehearsalRunRepository.Claim claim =
                claim(NOW.plusSeconds(30));
        checkpoint(claim.lease());
        ScenarioRehearsalEvidenceBundle bundle = bundle();
        databaseTime.set(NOW.plusSeconds(2));

        ScenarioRehearsalEvidenceBundle persisted =
                transactions.execute(status ->
                        service.commit(
                                claim.lease(), bundle,
                                observation()));

        assertThat(persisted).isEqualTo(bundle);
        assertThat(requests.find(
                SCOPE,
                ScenarioRehearsalEvidenceTestFixtures.REQUEST_ID))
                .get()
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(
                            ScenarioRehearsalRunRepository.Status.COMPLETED);
                    assertThat(state.evidenceBundleFingerprint())
                            .isEqualTo(bundle.bundleFingerprint());
                });
        assertThat(evidence.find(
                SCOPE, bundle.attestation().runId()))
                .contains(bundle);
        assertThat(audit.recent(SCOPE, 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.operation()).isEqualTo(
                            MirrorOperationAuditEvent.Operation
                                    .SCENARIO_REHEARSAL_CREATE);
                    assertThat(event.outcome()).isEqualTo(
                            MirrorOperationAuditEvent.Outcome.SUCCEEDED);
                    assertThat(event.runId()).isEqualTo(
                            bundle.attestation().runId());
                });
    }

    @Test
    void rollsBackEvidenceWhenDatabaseClockRevokesTheLeaseAtCommit() {
        ScenarioRehearsalRunRepository.Claim claim =
                claim(NOW.plusSeconds(10));
        checkpoint(claim.lease());
        ScenarioRehearsalEvidenceBundle bundle = bundle();
        databaseTime.set(NOW.plusSeconds(10));

        assertThatThrownBy(() ->
                transactions.execute(status ->
                        service.commit(
                                claim.lease(), bundle,
                                observation())))
                .isInstanceOf(
                        ScenarioRehearsalLeaseLostException.class);

        assertThat(evidence.find(
                SCOPE, bundle.attestation().runId()))
                .isEmpty();
        assertThat(requests.find(
                SCOPE,
                ScenarioRehearsalEvidenceTestFixtures.REQUEST_ID))
                .get()
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(
                            ScenarioRehearsalRunRepository.Status.ACTIVE);
                    assertThat(state.evidenceBundleFingerprint())
                            .isBlank();
                });
        assertThat(audit.recent(SCOPE, 10)).isEmpty();
    }

    @Test
    void rejectsEvidenceThatDiffersFromTheRegisteredPlanBeforeInsert() {
        ScenarioRehearsalRunRepository.Claim claim =
                claim(NOW.plusSeconds(30));
        checkpoint(claim.lease());
        ScenarioRehearsalResult drifted =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, SCOPE, '7');
        ScenarioRehearsalEvidenceBundle bundle =
                integrity().seal(
                        ScenarioRehearsalRunIdentity.derive(
                                mapper, SCOPE, drifted.requestId()),
                        drifted).bundle();

        assertThatThrownBy(() ->
                transactions.execute(status ->
                        service.commit(
                                claim.lease(), bundle,
                                observation())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable request registration");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_evidence",
                Integer.class)).isZero();
        assertThat(audit.recent(SCOPE, 10)).isEmpty();
    }

    private ScenarioRehearsalRunRepository.Claim claim(
            Instant expiresAt) {
        databaseTime.set(NOW);
        return transactions.execute(status ->
                requests.claim(
                        registration(),
                        "owner-a",
                        Duration.between(NOW, expiresAt)));
    }

    private void checkpoint(
            ScenarioRehearsalRunRepository.Lease lease) {
        databaseTime.set(NOW.plusSeconds(1));
        transactions.executeWithoutResult(status ->
                requests.checkpoint(
                        lease,
                        result().caseResults().getFirst()));
    }

    private ScenarioRehearsalRunRepository.Registration registration() {
        ScenarioRehearsalResult result = result();
        return new ScenarioRehearsalRunRepository.Registration(
                SCOPE,
                result.requestId(),
                ScenarioRehearsalEvidenceTestFixtures.fingerprint('a'),
                result.compiledPlanRef(),
                ScenarioRehearsalRunIdentity.derive(
                        mapper, SCOPE, result.requestId()),
                result.caseResults().size(),
                NOW.plus(Duration.ofDays(30)));
    }

    private ScenarioRehearsalResult result() {
        return ScenarioRehearsalEvidenceTestFixtures.result(
                mapper, SCOPE, '5');
    }

    private ScenarioRehearsalEvidenceBundle bundle() {
        ScenarioRehearsalResult result = result();
        return integrity().seal(
                ScenarioRehearsalRunIdentity.derive(
                        mapper, SCOPE, result.requestId()),
                result).bundle();
    }

    private ScenarioRehearsalEvidenceIntegrityService integrity() {
        return new ScenarioRehearsalEvidenceIntegrityService(
                mapper,
                signer,
                Clock.fixed(
                        ScenarioRehearsalEvidenceTestFixtures.COMPLETED
                                .plusSeconds(5),
                        ZoneOffset.UTC));
    }

    private MirrorOperationObservability.Observation observation() {
        return new MirrorOperationObservability(
                audit, MirrorOperationTelemetry.noop(), () -> 0)
                .start(
                MirrorOperationAuditEvent.Operation
                        .SCENARIO_REHEARSAL_CREATE,
                MirrorPersistenceTestFixtures.identity("org-a"),
                ScenarioRehearsalEvidenceTestFixtures.REQUEST_ID,
                result().compiledPlanRef().id(),
                "");
    }
}
