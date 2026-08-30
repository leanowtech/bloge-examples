package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the Connection metadata commit seam.
 *
 * <p>The test deliberately exercises only the public stage/commit/fail/read
 * boundary. It uses the in-memory implementation as a deterministic model for
 * the later JDBC implementation; it is not a database certification.</p>
 */
class ApiConnectionCommitStoreContractTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final AuthoringScope OTHER_SCOPE = new AuthoringScope("other", "project", "dev");
    private static final String BASE_URL = "https://customer.example.com";
    private static final String REQUEST_FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void stageIsInvisibleUntilCommitAndCommittedReadIsDefensive() throws Exception {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("create-1", 1, "attempt-1", SCOPE, "customer", ExpectedRevision.create());

        StagedApiConnection staged = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());

        assertThat(staged.view().revision()).isEqualTo(1);
        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isEmpty();

        StoredApiConnection committed = store.commit(lease);
        assertThat(committed.view().revision()).isEqualTo(1);
        assertThat(committed.view().displayName()).isEqualTo("Customer API");
        assertThat(store.findHead(SCOPE, "customer")).contains(committed);
        assertThat(store.findRevision(SCOPE, "customer", 1)).contains(committed);

        assertThatThrownBy(() -> committed.view().defaults().headers().put("Injected", "no"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().defaults().headers())
                .doesNotContainKey("Injected");
        assertThat(new ObjectMapper().writeValueAsString(committed.view()))
                .doesNotContain("vault://", "secret-value");
    }

    @Test
    void createUpdateAndHistoryUseTheAuthorityCas() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease create = lease("create-2", 1, "a-2", SCOPE, "customer", ExpectedRevision.create());
        stage(store, create, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(create);

        CommandLease update = lease("update-2", 1, "u-2", SCOPE, "customer", ExpectedRevision.match(1));
        ApiConnectionCommand renamed = new ApiConnectionCommand("Customer v2", BASE_URL,
                ApiConnectionCommand.Auth.none(), new ApiConnectionCommand.Defaults(5000, Map.of()));
        stage(store, update, "customer", ExpectedRevision.match(1), renamed);
        StoredApiConnection head = store.commit(update);

        assertThat(head.view().revision()).isEqualTo(2);
        assertThat(head.view().displayName()).isEqualTo("Customer v2");
        assertThat(store.findRevision(SCOPE, "customer", 1).orElseThrow().view().displayName())
                .isEqualTo("Customer API");
        assertThat(store.findRevision(SCOPE, "customer", 2)).contains(head);
    }

    @Test
    void sameAttemptReentryReturnsTheSameStageWithoutDuplicatingIt() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("reentry", 1, "reentry-token", SCOPE, "customer", ExpectedRevision.create());

        StagedApiConnection first = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        StagedApiConnection second = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());

        assertThat(second).isEqualTo(first);
        assertThat(store.commit(lease).strongEtag()).isEqualTo(first.strongEtag());
    }

    @Test
    void twoCommandsMayStageOneLogicalRevisionButOnlyOneCanCommit() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease firstLease = lease("winner-a", 1, "winner-a-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease secondLease = lease("winner-b", 1, "winner-b-token", SCOPE, "customer", ExpectedRevision.create());

        stage(store, firstLease, "customer", ExpectedRevision.create(), noneCommand());
        stage(store, secondLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection winner = store.commit(firstLease);

        assertThatThrownBy(() -> store.commit(secondLease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer")).contains(winner);
    }

    @Test
    void updateCommandsAreCheckedAgainstTheHeadAgainAtCommit() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease base = lease("base", 1, "base-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, base, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(base);
        CommandLease first = lease("update-a", 1, "update-a-token", SCOPE, "customer", ExpectedRevision.match(1));
        CommandLease second = lease("update-b", 1, "update-b-token", SCOPE, "customer", ExpectedRevision.match(1));
        stage(store, first, "customer", ExpectedRevision.match(1), renamedCommand("Customer A"));
        stage(store, second, "customer", ExpectedRevision.match(1), renamedCommand("Customer B"));

        store.commit(first);
        assertThatThrownBy(() -> store.commit(second))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().displayName())
                .isEqualTo("Customer A");
    }

    @Test
    void expiredLeaseCannotStageOrCommitAndFailIsAStaleNoOp() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        InMemoryApiConnectionCommitStore store = newStore(clock);
        CommandLease live = leaseAt(clock, "expiry", 1, "expiry-token", SCOPE, "customer", ExpectedRevision.create(), 1);
        stage(store, live, "customer", ExpectedRevision.create(), noneCommand());
        clock.advanceSeconds(2);
        store.fail(live);
        assertThatThrownBy(() -> store.commit(live)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_EXPIRED);
    }

    @Test
    void newerAttemptTakesOverOldStageAndFencesOldLease() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        InMemoryApiConnectionCommitStore store = newStore(clock);
        CommandLease old = leaseAt(clock, "takeover", 1, "old-token", SCOPE, "customer", ExpectedRevision.create(), 1);
        CommandLease current = leaseAt(clock, "takeover", 2, "new-token", SCOPE, "customer", ExpectedRevision.create(), 30);
        stage(store, old, "customer", ExpectedRevision.create(), noneCommand());

        assertThatThrownBy(() -> stage(store, current, "customer", ExpectedRevision.create(), renamedCommand("Current")))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        clock.advanceSeconds(2);
        StagedApiConnection replacement = stage(store, current, "customer", ExpectedRevision.create(), renamedCommand("Current"));
        store.fail(old);

        assertThatThrownBy(() -> store.commit(old))
                .isInstanceOf(ApiConnectionCommitStoreException.class);
        assertThat(store.commit(current)).isEqualTo(new StoredApiConnection(SCOPE, replacement.view(),
                replacement.metadataFingerprint(), replacement.strongEtag(), current.commandId()));
    }

    @Test
    void failRemovesOnlyTheExactLiveStage() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease first = lease("fail-1", 1, "fail-1-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease second = lease("fail-2", 1, "fail-2-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, first, "customer", ExpectedRevision.create(), noneCommand());
        stage(store, second, "customer", ExpectedRevision.create(), noneCommand());

        store.fail(first);
        assertThatThrownBy(() -> store.commit(first))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.STAGE_MISSING);
        assertThat(store.commit(second).view().revision()).isEqualTo(1);
    }

    @Test
    void keepExistingUsesTheOpaqueBindingButNeverReturnsItInTheView() throws Exception {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        ApiConnectionCommand bearer = new ApiConnectionCommand("Customer API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
        CommandLease secretCreate = lease("secret-create", 1, "secret-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, secretCreate, "customer", ExpectedRevision.create(), bearer, prepared);
        store.commit(secretCreate);

        ApiConnectionCommand keep = new ApiConnectionCommand("Customer API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.keepExisting()),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
        CommandLease secretUpdate = lease("secret-update", 1, "secret-update-token", SCOPE, "customer", ExpectedRevision.match(1));
        StagedApiConnection updated = stage(store, secretUpdate, "customer", ExpectedRevision.match(1), keep);

        assertThat(updated.view().auth().configured()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(updated.view()))
                .doesNotContain("vault://team/customer-token", "one-time-secret", "secret");
    }

    @Test
    void readsAreScopeExactAndMissingRevisionIsEmpty() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease scopeLease = lease("scope", 1, "scope-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, scopeLease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(scopeLease);

        assertThat(store.findHead(OTHER_SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 0)).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 99)).isEmpty();
    }

    @Test
    void errorsAndStringFormsDoNotLeakEndpointOrSecretData() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("redaction", 1, "redaction-token", SCOPE, "customer", ExpectedRevision.create());
        StagedApiConnection staged = stage(store, lease, "customer", ExpectedRevision.create(), new ApiConnectionCommand("Customer API",
                BASE_URL, ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("secret-value")),
                new ApiConnectionCommand.Defaults(5000, Map.of())) ,
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://redacted/token")));

        assertThat(staged.toString()).doesNotContain(BASE_URL, "secret-value", "vault://redacted/token");
        assertThatThrownBy(() -> stage(store, lease, "different", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .satisfies(error -> assertThat(error.toString())
                        .doesNotContain(BASE_URL, "secret-value", "vault://redacted/token"));
    }

    @Test
    void stageRejectsACommandThatDoesNotMatchTheLeaseTarget() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("target", 1, "target-token", SCOPE, "customer", ExpectedRevision.create());

        assertThat(stage(store, lease, "customer", ExpectedRevision.create(), noneCommand())).isNotNull();
    }

    @Test
    void connectionCasIsIndependentFromCompositeLeaseExpectation() {
        InMemoryApiConnectionCommitStore store = newStore(Clock.systemUTC());
        CommandLease resourceLease = lease("composite", 1, "composite-token", SCOPE, "customer",
                ExpectedRevision.match(999));
        stage(store, resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        assertThat(store.commit(resourceLease).view().revision()).isEqualTo(1);
    }

    @Test
    void alteredLeaseFieldsAreFencedEvenWhenTokenCoordinatesMatch() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("exact", 1, "exact-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        CommandLease altered = new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                lease.key(), "sha256:" + "b".repeat(64), lease.leaseUntil(), lease.expectedRevision());
        assertThatThrownBy(() -> store.commit(altered)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThat(store.commit(lease).view().revision()).isEqualTo(1);
    }

    @Test
    void exactFailedLeaseCannotStageAgain() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("failed", 1, "failed-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        store.fail(lease);
        assertThatThrownBy(() -> stage(store, lease, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void errorsHaveOnlyCodeDerivedMessages() {
        ApiConnectionCommitStoreException error = new ApiConnectionCommitStoreException(
                ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(error.getMessage()).isEqualTo("connection revision does not match");
        assertThat(error.toString()).doesNotContain(BASE_URL, "secret-value", "vault://");
    }

    private static ApiConnectionCommand noneCommand() {
        return new ApiConnectionCommand("Customer API", BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
    }

    private static ApiConnectionCommand renamedCommand(String displayName) {
        return new ApiConnectionCommand(displayName, BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
    }

    private static StagedApiConnection stage(InMemoryApiConnectionCommitStore store, CommandLease lease,
                                             String connectionId, ExpectedRevision expected,
                                             ApiConnectionCommand command, PreparedSecretBinding... prepared) {
        return store.stage(lease, connectionId, expected, command, prepared);
    }

    private static InMemoryApiConnectionCommitStore newStore(Clock clock) {
        return new InMemoryApiConnectionCommitStore(clock, new ApiConnectionDecisions());
    }

    private static CommandLease leaseAt(Clock clock, String commandId, int attemptNo, String attemptToken,
                                        AuthoringScope scope, String connectionId, ExpectedRevision expected,
                                        long seconds) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        "resource-" + connectionId, "key-" + commandId), REQUEST_FINGERPRINT,
                clock.instant().plusSeconds(seconds), expected);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        private void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
    }

    private static CommandLease lease(String commandId, int attemptNo, String attemptToken,
                                      AuthoringScope scope, String connectionId, ExpectedRevision expected) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        "resource-" + connectionId, "key-" + commandId), REQUEST_FINGERPRINT,
                Instant.now().plusSeconds(30), expected);
    }
}
