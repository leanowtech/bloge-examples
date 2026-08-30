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

        StagedApiConnection staged = store.stage(lease, "customer", noneCommand());

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
        store.commit(store.stage(lease("create-2", 1, "a-2", SCOPE, "customer", ExpectedRevision.create()),
                "customer", noneCommand()));

        CommandLease update = lease("update-2", 1, "u-2", SCOPE, "customer", ExpectedRevision.match(1));
        ApiConnectionCommand renamed = new ApiConnectionCommand("Customer v2", BASE_URL,
                ApiConnectionCommand.Auth.none(), new ApiConnectionCommand.Defaults(5000, Map.of()));
        store.stage(update, "customer", renamed);
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

        StagedApiConnection first = store.stage(lease, "customer", noneCommand());
        StagedApiConnection second = store.stage(lease, "customer", noneCommand());

        assertThat(second).isEqualTo(first);
        assertThat(store.commit(lease).strongEtag()).isEqualTo(first.strongEtag());
    }

    @Test
    void twoCommandsMayStageOneLogicalRevisionButOnlyOneCanCommit() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease firstLease = lease("winner-a", 1, "winner-a-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease secondLease = lease("winner-b", 1, "winner-b-token", SCOPE, "customer", ExpectedRevision.create());

        store.stage(firstLease, "customer", noneCommand());
        store.stage(secondLease, "customer", noneCommand());
        StoredApiConnection winner = store.commit(firstLease);

        assertThatThrownBy(() -> store.commit(secondLease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer")).contains(winner);
    }

    @Test
    void updateCommandsAreCheckedAgainstTheHeadAgainAtCommit() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        store.commit(store.stage(lease("base", 1, "base-token", SCOPE, "customer", ExpectedRevision.create()),
                "customer", noneCommand()));
        CommandLease first = lease("update-a", 1, "update-a-token", SCOPE, "customer", ExpectedRevision.match(1));
        CommandLease second = lease("update-b", 1, "update-b-token", SCOPE, "customer", ExpectedRevision.match(1));
        store.stage(first, "customer", renamedCommand("Customer A"));
        store.stage(second, "customer", renamedCommand("Customer B"));

        store.commit(first);
        assertThatThrownBy(() -> store.commit(second))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().displayName())
                .isEqualTo("Customer A");
    }

    @Test
    void expiredLeaseCannotStageOrCommitAndFailIsAStaleNoOp() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease live = lease("expiry", 1, "expiry-token", SCOPE, "customer", ExpectedRevision.create());
        store.stage(live, "customer", noneCommand());
        CommandLease expired = new CommandLease(live.commandId(), live.attemptNo(), live.attemptToken(), live.key(),
                live.requestFingerprint(), Instant.now().minusSeconds(1), live.expectedRevision());

        assertThatThrownBy(() -> store.stage(expired, "customer", noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_EXPIRED);
        store.fail(expired);
        assertThat(store.commit(live).view().revision()).isEqualTo(1);
    }

    @Test
    void newerAttemptTakesOverOldStageAndFencesOldLease() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease old = lease("takeover", 1, "old-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease current = lease("takeover", 2, "new-token", SCOPE, "customer", ExpectedRevision.create());
        store.stage(old, "customer", noneCommand());

        StagedApiConnection replacement = store.stage(current, "customer", renamedCommand("Current"));
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
        store.stage(first, "customer", noneCommand());
        store.stage(second, "customer", noneCommand());

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
        store.commit(store.stage(lease("secret-create", 1, "secret-token", SCOPE, "customer", ExpectedRevision.create()),
                "customer", bearer, prepared));

        ApiConnectionCommand keep = new ApiConnectionCommand("Customer API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.keepExisting()),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
        StagedApiConnection updated = store.stage(
                lease("secret-update", 1, "secret-update-token", SCOPE, "customer", ExpectedRevision.match(1)),
                "customer", keep);

        assertThat(updated.view().auth().configured()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(updated.view()))
                .doesNotContain("vault://team/customer-token", "one-time-secret", "secret");
    }

    @Test
    void readsAreScopeExactAndMissingRevisionIsEmpty() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        store.commit(store.stage(lease("scope", 1, "scope-token", SCOPE, "customer", ExpectedRevision.create()),
                "customer", noneCommand()));

        assertThat(store.findHead(OTHER_SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 0)).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 99)).isEmpty();
    }

    @Test
    void errorsAndStringFormsDoNotLeakEndpointOrSecretData() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("redaction", 1, "redaction-token", SCOPE, "customer", ExpectedRevision.create());
        StagedApiConnection staged = store.stage(lease, "customer", new ApiConnectionCommand("Customer API",
                BASE_URL, ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("secret-value")),
                new ApiConnectionCommand.Defaults(5000, Map.of())) ,
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://redacted/token")));

        assertThat(staged.toString()).doesNotContain(BASE_URL, "secret-value", "vault://redacted/token");
        assertThatThrownBy(() -> store.stage(lease, "different", noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .satisfies(error -> assertThat(error.toString())
                        .doesNotContain(BASE_URL, "secret-value", "vault://redacted/token"));
    }

    @Test
    void stageRejectsACommandThatDoesNotMatchTheLeaseTarget() {
        InMemoryApiConnectionCommitStore store = new InMemoryApiConnectionCommitStore();
        CommandLease lease = lease("target", 1, "target-token", SCOPE, "customer", ExpectedRevision.create());

        assertThatThrownBy(() -> store.stage(lease, "other", noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    private static ApiConnectionCommand noneCommand() {
        return new ApiConnectionCommand("Customer API", BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
    }

    private static ApiConnectionCommand renamedCommand(String displayName) {
        return new ApiConnectionCommand(displayName, BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
    }

    private static CommandLease lease(String commandId, int attemptNo, String attemptToken,
                                      AuthoringScope scope, String connectionId, ExpectedRevision expected) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_RESOURCE_SAVE,
                        connectionId, "key-" + commandId), REQUEST_FINGERPRINT,
                Instant.now().plusSeconds(30), expected);
    }
}
