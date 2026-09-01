package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStandaloneFixtureSetShareTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void derivesPendingRevisionAndReplaysWithoutRepeatingProtectedMaterialWrite() {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore(
                new java.util.ArrayDeque<>(List.of("private-etag", "review-1", "pending-etag"))::remove);
        GeneratedDefaultFixture source = source();
        StandaloneFixtureSetSaveResult saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save", fingerprint('1'), source));
        FixtureShareCommand command = command(source);
        StandaloneFixtureSetShareIntent intent = new StandaloneFixtureSetShareIntent(
                SCOPE, "author", "cases", saved.strongEtag(), "share", fingerprint('2'), command);
        AtomicInteger writes = new AtomicInteger();

        StandaloneFixtureSetShareResult shared = store.share(intent,
                (stored, revision, statusRevision, reviewRequestId) -> {
                    writes.incrementAndGet();
                    return pending(stored.generated(), revision, statusRevision, reviewRequestId);
                });
        StandaloneFixtureSetShareResult replay = store.share(intent,
                (stored, revision, statusRevision, reviewRequestId) -> {
                    writes.incrementAndGet();
                    throw new AssertionError("replay must not derive protected material again");
                });

        assertThat(shared.replayed()).isFalse();
        assertThat(shared.view().status()).isEqualTo(FixtureSetView.Status.SHARING_PENDING);
        assertThat(shared.view().revision()).isEqualTo(2);
        assertThat(shared.view().statusRevision()).isEqualTo(2);
        assertThat(shared.receipt().reviewRequestId()).isEqualTo("review-1");
        assertThat(replay).isEqualTo(new StandaloneFixtureSetShareResult(
                shared.view(), shared.receipt(), shared.strongEtag(), true));
        assertThat(writes).hasValue(1);
        assertThat(store.findRevision(SCOPE, "cases", 1)).get()
                .extracting(value -> value.generated().view().status())
                .isEqualTo(FixtureSetView.Status.PRIVATE_DRAFT);
        assertThat(store.findHead(SCOPE, "cases")).get()
                .extracting(value -> value.generated().view())
                .isEqualTo(shared.view());
    }

    @Test
    void sourceClosureAndIdempotencyDriftFailClosedBeforeDerivation() {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore(
                new java.util.ArrayDeque<>(List.of("private-etag", "review-1", "pending-etag"))::remove);
        GeneratedDefaultFixture source = source();
        StandaloneFixtureSetSaveResult saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save", fingerprint('1'), source));
        AtomicInteger writes = new AtomicInteger();
        FixtureSetShareDeriver deriver = (stored, revision, statusRevision, reviewRequestId) -> {
            writes.incrementAndGet();
            return pending(stored.generated(), revision, statusRevision, reviewRequestId);
        };

        FixtureShareCommand wrongSource = new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", 1, fingerprint('9'), 1), policy());
        assertCode(() -> store.share(new StandaloneFixtureSetShareIntent(
                        SCOPE, "author", "cases", saved.strongEtag(), "wrong", fingerprint('2'), wrongSource),
                deriver), StandaloneFixtureSetStoreException.Code.CAS_MISMATCH);
        assertThat(writes).hasValue(0);

        FixtureShareCommand exact = command(source);
        StandaloneFixtureSetShareIntent intent = new StandaloneFixtureSetShareIntent(
                SCOPE, "author", "cases", saved.strongEtag(), "share", fingerprint('2'), exact);
        store.share(intent, deriver);
        assertCode(() -> store.share(new StandaloneFixtureSetShareIntent(
                        SCOPE, "author", "cases", saved.strongEtag(), "share", fingerprint('3'), exact),
                deriver), StandaloneFixtureSetStoreException.Code.CONFLICT);
        assertThat(writes).hasValue(1);
    }

    private static GeneratedDefaultFixture source() {
        ReusableFlowVersion version = version();
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "approved", "Approved", object("customerId", "c-1"),
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                object("eligible", true))), FixtureSetCommand.Fidelity.OUTPUT_LEVEL)),
                new FixtureSetCommand.Expect(object("eligible", true)));
        FixtureSetCommand command = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Cases", version.subject(), List.of(fixtureCase));
        return new WholeFlowFixtureMaterializer().generate("cases", version, command);
    }

    private static FixtureShareCommand command(GeneratedDefaultFixture source) {
        FixtureSetView view = source.view();
        return new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source(view.fixtureSetId(), view.revision(),
                        view.fingerprint(), view.statusRevision()), policy());
    }

    private static FixtureShareCommand.Policy policy() {
        return new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                new FixtureShareCommand.Redaction("default-v1", List.of("/email")));
    }

    private static FixtureShareMaterialization pending(
            GeneratedDefaultFixture source, int revision, int statusRevision, String reviewRequestId) {
        List<FixtureSetCommand.Case> cases = source.view().cases().stream().map(fixtureCase -> {
            FixtureSetCommand.Control control = fixtureCase.controls().getFirst();
            FixtureSetCommand.Control protectedControl = new FixtureSetCommand.Control(control.target(),
                    FixtureSetCommand.Behavior.returned(new FixtureSetCommand.Material.FixtureAsset(
                            "asset-approved", 2, fingerprint('a'))), control.fidelity());
            return new FixtureSetCommand.Case(fixtureCase.caseId(), fixtureCase.name(), fixtureCase.input(),
                    List.of(protectedControl), fixtureCase.expect());
        }).toList();
        String fingerprint = FixtureSetFingerprints.of(
                source.view().displayName(), source.view().subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "cases", revision,
                fingerprint, statusRevision, source.view().displayName(), source.view().subject(), cases,
                FixtureSetView.Status.SHARING_PENDING);
        FixtureSetSaveReceipt saveReceipt = new FixtureSetSaveReceipt(
                FixtureSetSaveReceipt.SCHEMA_VERSION, "cases", revision, fingerprint,
                view.subject(), List.of("approved"), view.status(), statusRevision);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                "cases", revision, fingerprint, view.displayName(), view.subject(),
                List.of(new FixtureSetSummary.CaseSummary("approved", "Approved")),
                view.status(), statusRevision);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, saveReceipt, summary,
                List.of(new GeneratedDefaultFixture.CaseMapping("approved", "approved")));
        FixtureShareReceipt receipt = new FixtureShareReceipt(FixtureShareReceipt.SCHEMA_VERSION,
                "cases", source.view().revision(), revision, fingerprint,
                FixtureSetView.Status.SHARING_PENDING, statusRevision, reviewRequestId);
        return new FixtureShareMaterialization(generated, receipt);
    }

    private static ReusableFlowVersion version() {
        SchemaEnvelope input = SchemaEnvelope.object(Map.of("customerId", Map.of("type", "string")),
                List.of("customerId"));
        SchemaEnvelope output = SchemaEnvelope.object(Map.of("eligible", Map.of("type", "boolean")),
                List.of("eligible"));
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(List.of(),
                new ReusableFlowCommand.Output("result", "$"));
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, "eligibility", 1,
                fingerprint('b'), new ReusableFlowVersion.Source("draft", 1, fingerprint('c')),
                "flow", "Eligibility", ReusableFlowCommand.Kind.TOOL, "Checks eligibility",
                new ReusableFlowCommand.Contract(input, output), graph,
                Instant.parse("2026-09-01T00:00:00Z"), "author", ReusableFlowVersion.Status.PUBLISHED);
    }

    private static JsonNode object(String key, Object value) {
        return JsonNodeFactory.instance.objectNode().putPOJO(key, value);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   StandaloneFixtureSetStoreException.Code code) {
        assertThatThrownBy(call).isInstanceOf(StandaloneFixtureSetStoreException.class)
                .extracting("code").isEqualTo(code);
    }
}
