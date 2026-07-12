package com.leanowtech.bloge.gateway.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDynamicRunControlRepositoryTest {
    private DatabaseDynamicRunControlRepository first;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setUp() {
        var dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        first = repository();
    }

    @Test
    void runningStateSurvivesRepositoryRestart() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("restart-run", "fence-a", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));
        first.start(intent.requestId(), claim.state().owner(), now, now.plusSeconds(10));

        DatabaseDynamicRunControlRepository restarted = repository();
        DynamicRunControlRepository.State restored = restarted.find(intent.requestId(), now.plusSeconds(1))
                .orElseThrow();

        assertThat(restored.view().status()).isEqualTo("RUNNING");
        assertThat(restored.view().revision()).isEqualTo(2);
        assertThat(restored.owner()).isEqualTo(new DynamicRunControlRepository.Owner("owner-a", 1));
        assertThat(restored.fenceDigest()).doesNotContain("fence-a");
    }

    @Test
    void secondInstanceCanPersistFencedCancellationForOwner() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("remote-cancel", "fence-b", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));
        DynamicRunControlRepository.State running = first.start(intent.requestId(), claim.state().owner(), now,
                now.plusSeconds(10)).orElseThrow();
        DatabaseDynamicRunControlRepository second = repository();

        DynamicRunControlRepository.CommandResult cancelled = second.requestCallerCancel(
                new DynamicRunControlCommand(intent.requestId(), "fence-b", running.view().revision(), "operator"),
                now.plusMillis(20));

        assertThat(cancelled.accepted()).isTrue();
        assertThat(cancelled.state().view().status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelled.state().view().sideEffectsMayBeInFlight()).isTrue();
        assertThat(first.find(intent.requestId(), now.plusMillis(30)).orElseThrow().view().status())
                .isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void staleRevisionAndWrongFenceCannotCancel() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("fenced-run", "right-fence", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));
        DynamicRunControlRepository.State running = first.start(intent.requestId(), claim.state().owner(), now,
                now.plusSeconds(10)).orElseThrow();

        assertThat(first.requestCallerCancel(new DynamicRunControlCommand(intent.requestId(), "wrong", 0, ""), now)
                .code()).isEqualTo("RG.RUN_CONTROL.FENCE_MISMATCH");
        assertThat(first.requestCallerCancel(new DynamicRunControlCommand(intent.requestId(), "right-fence",
                running.view().revision() - 1, ""), now).code()).isEqualTo("RG.RUN_CONTROL.REVISION_CONFLICT");
        assertThat(first.find(intent.requestId(), now).orElseThrow().view().status()).isEqualTo("RUNNING");
    }

    @Test
    void expiredOwnerLeaseBecomesDurableAbandonmentInsteadOfDisappearing() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("abandoned-run", "fence-c", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "dead-owner", now.minusMillis(1));

        DynamicRunControlRepository.State recovered = repository().find(intent.requestId(), now).orElseThrow();

        assertThat(recovered.view().status()).isEqualTo("TERMINATION_UNCONFIRMED");
        assertThat(recovered.view().reasonCode()).isEqualTo("OWNER_LEASE_EXPIRED");
        assertThat(recovered.view().terminationConfirmed()).isFalse();
        assertThat(recovered.view().sideEffectsMayBeInFlight()).isTrue();
        assertThat(recovered.recoveryDisposition()).isEqualTo("ABANDONED");
        assertThat(repository().find(intent.requestId(), now.plusSeconds(1))).contains(recovered);
        assertThat(first.finish(intent.requestId(), claim.state().owner(), "SUCCEEDED", "EXECUTION_COMPLETED",
                now.plusSeconds(1))).isEmpty();
        assertThat(first.find(intent.requestId(), now.plusSeconds(1)).orElseThrow().view().status())
                .isEqualTo("TERMINATION_UNCONFIRMED");
    }

    @Test
    void cancellationWonBeforeStartCannotBeOverwrittenByLateOwnerStart() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("cancel-before-start", "fence-e", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));
        assertThat(first.requestCallerCancel(new DynamicRunControlCommand(intent.requestId(), "fence-e", 1, ""),
                now.plusMillis(1)).accepted()).isTrue();

        DynamicRunControlRepository.State afterStart = first.start(intent.requestId(), claim.state().owner(),
                now.plusMillis(2), now.plusSeconds(10)).orElseThrow();

        assertThat(afterStart.view().status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(afterStart.view().reasonCode()).isEqualTo("USER_CANCEL_REQUESTED");
    }

    @Test
    void oldOwnerIdentityCannotMutateAnotherOwnersRow() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("owner-fence", "fence-d", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));

        assertThat(first.start(intent.requestId(), new DynamicRunControlRepository.Owner("owner-b", 1), now,
                now.plusSeconds(10))).isEmpty();
        assertThat(first.finish(intent.requestId(), new DynamicRunControlRepository.Owner("owner-a", 2),
                "SUCCEEDED", "EXECUTION_COMPLETED", now)).isEmpty();
        assertThat(first.find(intent.requestId(), now).orElseThrow().view().status()).isEqualTo("QUEUED");
        assertThat(claim.state().owner().epoch()).isEqualTo(1);
    }

    @Test
    void concurrentCancelCommandsHaveOneDurableWinner() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("cancel-race", "race-fence", now.plusSeconds(30));
        DynamicRunControlRepository.Claim claim = first.claim(intent, "owner-a", now.plusSeconds(10));
        DynamicRunControlRepository.State running = first.start(intent.requestId(), claim.state().owner(), now,
                now.plusSeconds(10)).orElseThrow();
        DatabaseDynamicRunControlRepository second = repository();
        DynamicRunControlCommand command = new DynamicRunControlCommand(intent.requestId(), "race-fence",
                running.view().revision(), "race");

        CompletableFuture<DynamicRunControlRepository.CommandResult> firstCancel =
                CompletableFuture.supplyAsync(() -> first.requestCallerCancel(command, now.plusMillis(1)));
        CompletableFuture<DynamicRunControlRepository.CommandResult> secondCancel =
                CompletableFuture.supplyAsync(() -> second.requestCallerCancel(command, now.plusMillis(1)));
        List<DynamicRunControlRepository.CommandResult> results = List.of(firstCancel.join(), secondCancel.join());

        assertThat(results).filteredOn(DynamicRunControlRepository.CommandResult::accepted).hasSize(1);
        assertThat(results).filteredOn(result -> !result.accepted()).singleElement()
                .satisfies(result -> assertThat(result.code())
                        .isIn("RG.RUN_CONTROL.REVISION_CONFLICT", "RG.RUN_CONTROL.ALREADY_TERMINAL"));
        assertThat(first.find(intent.requestId(), now.plusMillis(2)).orElseThrow().view().status())
                .isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void databaseNeverStoresRawFencingToken() {
        Instant now = Instant.now();
        first.claim(intent("secret-fence-run", "plain-secret-token", now.plusSeconds(30)), "owner-a",
                now.plusSeconds(10));

        String digest = jdbc.queryForObject(
                "SELECT fence_digest FROM dynamic_run_controls WHERE request_id = 'secret-fence-run'", String.class);

        assertThat(digest).hasSize(64).doesNotContain("plain-secret-token");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dynamic_run_controls WHERE fence_digest = 'plain-secret-token'", Long.class))
                .isZero();
    }

    @Test
    void purgeNeverDeletesUnconfirmedAbandonmentEvidence() {
        Instant now = Instant.now();
        DynamicRunIntent intent = intent("retained-abandonment", "fence-f", now.plusSeconds(30));
        first.claim(intent, "dead-owner", now.minusSeconds(2));
        assertThat(first.find(intent.requestId(), now)).isPresent();

        first.purgeTerminalBefore(now.plusSeconds(1));

        assertThat(first.find(intent.requestId(), now.plusSeconds(2))).isPresent()
                .get().extracting(state -> state.view().reasonCode()).isEqualTo("OWNER_LEASE_EXPIRED");
    }

    private DatabaseDynamicRunControlRepository repository() {
        DatabaseDynamicRunControlRepository repository = new DatabaseDynamicRunControlRepository(jdbc, transactions);
        repository.init();
        return repository;
    }

    private static DynamicRunIntent intent(String requestId, String fence, Instant deadline) {
        return new DynamicRunIntent("", requestId, deadline, fence, 2_000);
    }
}
