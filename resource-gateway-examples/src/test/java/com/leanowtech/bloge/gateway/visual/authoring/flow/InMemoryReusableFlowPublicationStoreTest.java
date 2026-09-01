package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryReusableFlowPublicationStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void exactReplayAndChangedIntentConflictAreActorAndScopeIsolated() {
        InMemoryReusableFlowPublicationStore store = new InMemoryReusableFlowPublicationStore(
                () -> "publication-tool", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ReusableFlowPublishIntent intent = intent(SCOPE, "alice", "key-1", FINGERPRINT);

        ReusableFlowPublishResult first = store.publish(intent);
        ReusableFlowPublishResult replay = store.publish(intent);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(store.findVersion(SCOPE, "publication-tool", 1)).contains(first.version());
        assertThatThrownBy(() -> store.publish(intent(SCOPE, "alice", "key-1",
                "sha256:" + "b".repeat(64))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CONFLICT);

        ReusableFlowPublishResult otherActor = store.publish(intent(SCOPE, "bob", "key-1", FINGERPRINT));
        assertThat(otherActor.version().revision()).isEqualTo(2);
        assertThat(store.findLatestVersion(SCOPE, "customer-tool")).contains(otherActor.version());
        AuthoringScope other = new AuthoringScope("tenant-b", "project-a", "test");
        ReusableFlowPublishResult otherScope = store.publish(intent(other, "alice", "key-1", FINGERPRINT));
        assertThat(otherScope.version().revision()).isEqualTo(1);
    }

    private static ReusableFlowPublishIntent intent(
            AuthoringScope scope, String actor, String key, String requestFingerprint) {
        return new ReusableFlowPublishIntent(scope, actor, "customer-tool", key,
                requestFingerprint, FINGERPRINT, draft());
    }

    private static ReusableFlowDraft draft() {
        SchemaEnvelope schema = SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id"));
        ReusableFlowCommand.Node node = new ReusableFlowCommand.Node("node", "Node",
                new ReusableFlowCommand.ComposableRef.ApiResource("customer.get", 1, FINGERPRINT),
                List.of(new ReusableFlowCommand.Input("$.id",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.id"))));
        return new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION, "customer-tool", "draft-tool", 1,
                FINGERPRINT, "Customer tool", ReusableFlowCommand.Kind.TOOL, "description",
                new ReusableFlowCommand.Contract(schema, schema),
                new ReusableFlowCommand.Graph(List.of(node), new ReusableFlowCommand.Output("node", "$")),
                new ReusableFlowCommand.Layout(Map.of("node", new ReusableFlowCommand.Position(0, 0))),
                ReusableFlowDraft.Status.DRAFT);
    }
}
