package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public seam contract for scoped staging, fencing, idempotency and commit. */
class ApiResourceCommitStoreContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final CommandKey KEY = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1");

    @Test
    void stagedIsInvisibleAndCommitPublishesHeadAndRevision() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), "sha256:req1");
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        CommandReceipt receipt = store.commit(lease);
        assertThat(receipt.strongEtag()).isNotEqualTo("\"1-" + staged.resource().fingerprint() + "\"");
        assertThat(store.findHead(SCOPE, "profile")).contains(store.findRevision(SCOPE, "profile", 1).orElseThrow());
    }

    @Test
    void scopeAndIdempotencyCoordinatesAreIsolated() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), "sha256:req1");
        store.stage(lease, "connection", command("one"));
        store.commit(lease);
        AuthoringScope other = new AuthoringScope("other", "project", "dev");
        CommandKey otherKey = new CommandKey(other, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1");
        assertThat(store.claim(otherKey, "sha256:req1", ExpectedRevision.create())).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.claim(KEY, "sha256:req1", ExpectedRevision.create())).isInstanceOf(ClaimResult.Replay.class);
        assertThat(store.claim(KEY, "sha256:other", ExpectedRevision.create())).isInstanceOf(ClaimResult.Conflict.class);
    }

    @Test
    void liveLeaseBusyAndExpiredLeaseTakesOverWithStaleAttemptFenced() {
        MutableClock clock = new MutableClock();
        InMemoryApiResourceCommitStore store = store(clock);
        CommandLease first = acquired(store, KEY, ExpectedRevision.create(), "sha256:req1");
        assertThat(store.claim(KEY, "sha256:req1", ExpectedRevision.create())).isInstanceOf(ClaimResult.Busy.class);
        clock.advance(Duration.ofSeconds(2));
        ClaimResult.Acquired second = (ClaimResult.Acquired) store.claim(KEY, "sha256:req1", ExpectedRevision.create());
        assertThat(second.resumed()).isTrue();
        assertThatThrownBy(() -> store.stage(first, "connection", command("one"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedCoordinateCannotBeRetakenWithDifferentFingerprint() {
        MutableClock clock = new MutableClock();
        InMemoryApiResourceCommitStore store = store(clock);
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), "sha256:req1");
        store.fail(lease);
        assertThat(store.claim(KEY, "sha256:other", ExpectedRevision.create())).isInstanceOf(ClaimResult.Conflict.class);
        assertThat(store.claim(KEY, "sha256:req1", ExpectedRevision.create())).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void failRemovesStageAndLeavesPreviousHeadVisible() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease first = acquired(store, KEY, ExpectedRevision.create(), "sha256:req1");
        store.stage(first, "connection", command("one"));
        store.commit(first);
        CommandKey update = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k2");
        CommandLease second = acquired(store, update, ExpectedRevision.match(1), "sha256:req2");
        store.stage(second, "connection", command("two"));
        store.fail(second);
        assertThat(store.findHead(SCOPE, "profile")).isPresent().get().extracting(v -> v.resource().revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.commit(second)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateUsesExactCasAndStaleRevisionIsRejected() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease create = acquired(store, KEY, ExpectedRevision.create(), "sha256:r1");
        store.stage(create, "connection", command("one"));
        store.commit(create);
        CommandKey updateKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k2");
        CommandLease update = acquired(store, updateKey, ExpectedRevision.match(1), "sha256:r2");
        store.stage(update, "connection", command("two"));
        assertThat(store.commit(update).body().get("revision").asInt()).isEqualTo(2);
        CommandKey staleKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k3");
        CommandLease stale = acquired(store, staleKey, ExpectedRevision.match(1), "sha256:r3");
        assertThatThrownBy(() -> store.stage(stale, "connection", command("three"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void commitRechecksCasAfterAnotherCommandAdvancesHead() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease create = acquired(store, KEY, ExpectedRevision.create(), "sha256:r1");
        store.stage(create, "connection", command("one"));
        store.commit(create);
        CommandLease first = acquired(store, key("k2"), ExpectedRevision.match(1), "sha256:r2");
        store.stage(first, "connection", command("two"));
        CommandLease second = acquired(store, key("k3"), ExpectedRevision.match(1), "sha256:r3");
        store.stage(second, "connection", command("three"));
        store.commit(second);
        assertThatThrownBy(() -> store.commit(first)).isInstanceOf(IllegalStateException.class);
        assertThat(store.findHead(SCOPE, "profile").orElseThrow().resource().revision()).isEqualTo(2);
    }

    @Test
    void projectionDriftFailsClosedBeforeHeadChanges() {
        InMemoryApiResourceCommitStore store = new InMemoryApiResourceCommitStore(Clock.systemUTC(), Duration.ofSeconds(1),
                resource -> new ReadyApiResourceProjections(
                        doc(ProjectionDocument.Kind.DESCRIPTOR, resource),
                        new ProjectionDocument(ProjectionDocument.Kind.DESIGN_CONTRACT, resource.ref(), JSON.createObjectNode(), "sha256:drift", ProjectionDocument.State.READY),
                        doc(ProjectionDocument.Kind.OPERATOR, resource)));
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), "sha256:r1");
        assertThatThrownBy(() -> store.stage(lease, "connection", command("one"))).isInstanceOf(IllegalStateException.class);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
    }

    @Test
    void returnedJsonBodiesAreDefensiveCopies() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), "sha256:r1");
        store.stage(lease, "connection", command("one"));
        CommandReceipt receipt = store.commit(lease);
        ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.body()).put("resourceId", "tampered");
        assertThat(store.findHead(SCOPE, "profile").orElseThrow().receipt().body().get("resourceId").asText()).isEqualTo("profile");
    }

    @Test
    void twoThreadsWithSameExpectedRevisionProduceOneCommittedHead() throws Exception {
        InMemoryApiResourceCommitStore store = store();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ClaimResult> task = () -> store.claim(key("concurrent-" + Thread.currentThread().getId()), "sha256:r", ExpectedRevision.create());
            // Distinct idempotency keys race on the same expected create revision.
            var results = pool.invokeAll(List.of(task, task));
            assertThat(results).hasSize(2);
            CommandLease first = acquired(store, key("race-a"), ExpectedRevision.create(), "sha256:a");
            CommandLease second = acquired(store, key("race-b"), ExpectedRevision.create(), "sha256:b");
            store.stage(first, "connection", command("one"));
            store.stage(second, "connection", command("two"));
            store.commit(first);
            assertThatThrownBy(() -> store.commit(second)).isInstanceOf(IllegalStateException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void restartReusesStateAndReplaysCommittedReceipt() {
        InMemoryApiResourceCommitStore.State state = new InMemoryApiResourceCommitStore.State();
        InMemoryApiResourceCommitStore first = store(Clock.systemUTC(), state);
        CommandLease lease = acquired(first, KEY, ExpectedRevision.create(), "sha256:req1");
        first.stage(lease, "connection", command("one"));
        CommandReceipt receipt = first.commit(lease);
        InMemoryApiResourceCommitStore restarted = store(Clock.systemUTC(), state);
        ClaimResult.Replay replay = (ClaimResult.Replay) restarted.claim(KEY, "sha256:req1", ExpectedRevision.create());
        assertThat(replay.receipt()).isEqualTo(receipt);
        assertThat(restarted.findRevision(SCOPE, "profile", 1)).isPresent();
    }

    private static CommandLease acquired(InMemoryApiResourceCommitStore store, CommandKey key,
                                         ExpectedRevision expected, String fingerprint) {
        return ((ClaimResult.Acquired) store.claim(key, fingerprint, expected)).lease();
    }

    private static CommandKey key(String idempotencyKey) {
        return new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", idempotencyKey);
    }

    private static InMemoryApiResourceCommitStore store() { return store(Clock.systemUTC(), new InMemoryApiResourceCommitStore.State()); }
    private static InMemoryApiResourceCommitStore store(Clock clock) { return store(clock, new InMemoryApiResourceCommitStore.State()); }
    private static InMemoryApiResourceCommitStore store(Clock clock, InMemoryApiResourceCommitStore.State state) {
        return new InMemoryApiResourceCommitStore(clock, Duration.ofSeconds(1), ApiResourceCommitStoreContractTest::compile, state);
    }

    private static ReadyApiResourceProjections compile(ApiResourceSpec resource) {
        return new ReadyApiResourceProjections(doc(ProjectionDocument.Kind.DESCRIPTOR, resource),
                doc(ProjectionDocument.Kind.DESIGN_CONTRACT, resource), doc(ProjectionDocument.Kind.OPERATOR, resource));
    }

    private static ProjectionDocument doc(ProjectionDocument.Kind kind, ApiResourceSpec resource) {
        return new ProjectionDocument(kind, resource.ref(), JSON.createObjectNode().put("kind", kind.name()),
                resource.fingerprint(), ProjectionDocument.State.READY);
    }

    private static ApiResourceCommand command(String name) {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand(name, null, new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(envelope, envelope), new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY, List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
