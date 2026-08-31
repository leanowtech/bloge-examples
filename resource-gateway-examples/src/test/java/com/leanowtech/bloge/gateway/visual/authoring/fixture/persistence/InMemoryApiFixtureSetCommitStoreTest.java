package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ReadyApiResourceProjections;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ProjectionDocument;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StagedApiResource;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryApiFixtureSetCommitStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void childIsInvisibleUntilTheExactOuterReceiptPublishesIt() {
        InMemoryApiFixtureSetCommitStore store = new InMemoryApiFixtureSetCommitStore();
        CommandLease lease = lease();
        GeneratedDefaultFixture generated = generated();

        store.stage(lease, generated);
        assertThat(store.findHead(SCOPE, generated.view().fixtureSetId())).isEmpty();
        assertThat(store.commitChild(lease).generated().view()).isEqualTo(generated.view());
        assertThat(store.findHead(SCOPE, generated.view().fixtureSetId())).isEmpty();

        CommandReceipt outerReceipt = outerReceipt(lease, generated);
        StoredFixtureSet published = store.publishChild(lease, outerReceipt);

        assertThat(published.generated()).isEqualTo(generated);
        assertThat(store.findHead(SCOPE, generated.view().fixtureSetId())).contains(published);
        assertThat(store.findRevision(SCOPE, generated.view().fixtureSetId(), 1)).contains(published);
        assertThat(store.listSummariesBySubject(SCOPE, generated.view().subject()))
                .containsExactly(generated.summary());
        assertThat(store.publishChild(lease, outerReceipt)).isEqualTo(published);
    }

    @Test
    void alteredReceiptAndStaleLeaseCannotPublishAChild() {
        InMemoryApiFixtureSetCommitStore store = new InMemoryApiFixtureSetCommitStore();
        CommandLease lease = lease();
        GeneratedDefaultFixture generated = generated();
        store.stage(lease, generated);
        store.commitChild(lease);

        CommandReceipt receipt = outerReceipt(lease, generated);
        var altered = receipt.body().deepCopy();
        altered.withObject("/defaultFixture").put("fingerprint", "sha256:" + "0".repeat(64));
        CommandReceipt alteredReceipt = new CommandReceipt(receipt.schemaVersion(), altered,
                AuthoringFingerprints.of(altered), receipt.strongEtag());
        assertThatThrownBy(() -> store.publishChild(lease, alteredReceipt))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class);

        CommandLease stale = new CommandLease(lease.commandId(), lease.attemptNo() + 1, "other-token",
                lease.key(), lease.requestFingerprint(), lease.leaseUntil(), lease.expectedRevision());
        assertThatThrownBy(() -> store.publishChild(stale, receipt))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class);
        assertThat(store.findHead(SCOPE, generated.view().fixtureSetId())).isEmpty();
    }

    @Test
    void failureRemovesOnlyTheExactUnpublishedChild() {
        InMemoryApiFixtureSetCommitStore store = new InMemoryApiFixtureSetCommitStore();
        CommandLease lease = lease();
        GeneratedDefaultFixture generated = generated();
        store.stage(lease, generated);
        store.commitChild(lease);

        store.failChild(lease);

        assertThat(store.findHead(SCOPE, generated.view().fixtureSetId())).isEmpty();
        assertThatThrownBy(() -> store.publishChild(lease, outerReceipt(lease, generated)))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class);
    }

    @Test
    void generatedValueRejectsContentFingerprintDriftAtConstruction() {
        GeneratedDefaultFixture generated = generated();
        FixtureSetCommand.Case original = generated.view().cases().getFirst();
        var alteredInput = original.input().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) alteredInput).put("id", "tampered");
        FixtureSetCommand.Case alteredCase = new FixtureSetCommand.Case(original.caseId(), original.name(),
                alteredInput, original.controls(), original.expect());
        FixtureSetView alteredView = new FixtureSetView(generated.view().schemaVersion(),
                generated.view().fixtureSetId(), generated.view().revision(), generated.view().fingerprint(),
                generated.view().statusRevision(), generated.view().displayName(), generated.view().subject(),
                List.of(alteredCase), generated.view().status());
        assertThatThrownBy(() -> new GeneratedDefaultFixture(alteredView, generated.receipt(),
                generated.summary(), generated.caseMappings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    private static CommandReceipt outerReceipt(CommandLease lease, GeneratedDefaultFixture generated) {
        ApiResourceSpec resource = resource();
        StagedApiResource staged = new StagedApiResource(lease, resource,
                projections(resource), "\"resource-etag\"");
        return ApiResourceSaveReceiptClosure.create(staged, generated);
    }

    private static ReadyApiResourceProjections projections(ApiResourceSpec resource) {
        var body = JSON.createObjectNode().put("ready", true);
        String fingerprint = AuthoringFingerprints.of(body);
        return new ReadyApiResourceProjections(
                new ProjectionDocument(ProjectionDocument.Kind.DESCRIPTOR, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ProjectionDocument(ProjectionDocument.Kind.DESIGN_CONTRACT, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ProjectionDocument(ProjectionDocument.Kind.OPERATOR, resource.ref(), body,
                        fingerprint, ProjectionDocument.State.READY),
                new ApiResourceConnectionSnapshot("customer", 1, "sha256:" + "b".repeat(64)));
    }

    private static CommandLease lease() {
        CommandKey key = new CommandKey(SCOPE, "author", AuthoringEndpoint.API_RESOURCE_SAVE,
                "customer-profile", "fixture-key");
        return new CommandLease("command", 1, "attempt", key, "sha256:" + "a".repeat(64),
                Instant.parse("2030-01-01T00:00:00Z"), ExpectedRevision.create());
    }

    private static GeneratedDefaultFixture generated() {
        return new DefaultFixtureSetMaterializer().generate(resource(),
                ApiResourceSaveCommand.DefaultFixture.fromExamples("Default customer cases", List.of("happy"))
                        instanceof ApiResourceSaveCommand.DefaultFixture.FromExamples request ? request : null);
    }

    private static ApiResourceSpec resource() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id"));
        var value = JSON.createObjectNode().put("id", "one");
        ApiResourceCommand command = new ApiResourceCommand("Customer profile", null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(),
                List.of(new ApiResourceCommand.Example("happy", value, value)));
        return new ApiResourceDecisions(JSON).next(Optional.empty(), "customer-profile", "customer",
                command, ExpectedRevision.create());
    }
}
