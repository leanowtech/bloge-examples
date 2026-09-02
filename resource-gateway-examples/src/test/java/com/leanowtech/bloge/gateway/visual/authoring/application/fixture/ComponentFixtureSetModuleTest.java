package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ComponentFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.InMemoryStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComponentFixtureSetModuleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "test");
    private static final FixtureSubjectRef.OperatorVersion OPERATOR = new FixtureSubjectRef.OperatorVersion(
            "risk-library", 3, "risk.score", "sha256:" + "1".repeat(64));
    private static final FixtureSubjectRef.BuiltinFunctionVersion FUNCTION =
            new FixtureSubjectRef.BuiltinFunctionVersion(
                    "bloge", 1, "lookup", "sha256:" + "2".repeat(64),
                    "sha256:" + "3".repeat(64));

    @Test
    void savesAndUpdatesExactOperatorFixtureWithoutChangingSubjectAuthority() {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ComponentFixtureSetModule module = module(store);

        var created = module.save(SCOPE, "author", "risk-cases", FixtureSetPrecondition.create(),
                "create", command(OPERATOR, "low", 410));
        var updated = module.save(SCOPE, "author", "risk-cases",
                FixtureSetPrecondition.match(created.strongEtag()), "update",
                command(OPERATOR, "high", 780));

        assertThat(created.view().revision()).isEqualTo(1);
        assertThat(updated.view().revision()).isEqualTo(2);
        assertThat(updated.view().subject()).isEqualTo(OPERATOR);
        assertThat(store.listSummariesBySubject(SCOPE, OPERATOR))
                .extracting(value -> value.fixtureSetId()).containsExactly("risk-cases");
    }

    @Test
    void savesBuiltInFunctionFixtureAndReplaysIdempotently() {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ComponentFixtureSetModule module = module(store);
        FixtureSetCommand command = command(FUNCTION, "customer", 200);

        var first = module.save(SCOPE, "author", "lookup-cases",
                FixtureSetPrecondition.create(), "same", command);
        var replay = module.save(SCOPE, "author", "lookup-cases",
                FixtureSetPrecondition.create(), "same", command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.strongEtag()).isEqualTo(first.strongEtag());
        assertThat(replay.view().subject()).isEqualTo(FUNCTION);
    }

    @Test
    void rejectsDriftUnknownSubjectAndNodeOrApplyCaseControls() {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ComponentFixtureSetModule module = module(store);
        FixtureSubjectRef.OperatorVersion drift = new FixtureSubjectRef.OperatorVersion(
                "risk-library", 3, "risk.score", "sha256:" + "9".repeat(64));
        assertThatThrownBy(() -> module.save(SCOPE, "author", "drift",
                FixtureSetPrecondition.create(), "drift", command(drift, "low", 410)))
                .isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting(value -> ((ApiFixtureSetAuthoringFailure) value).code())
                .isEqualTo(ApiFixtureSetAuthoringFailure.Code.NOT_FOUND);

        FixtureSetCommand invalid = new FixtureSetCommand(
                FixtureSetCommand.SCHEMA_VERSION, "invalid", OPERATOR, List.of(
                new FixtureSetCommand.Case("case", "case", input(410), List.of(
                        new FixtureSetCommand.Control(FixtureSetCommand.Target.node("internal"),
                                new FixtureSetCommand.Behavior.ApplyCase("other", 1, "case"), null)),
                        null)));
        assertThatThrownBy(() -> module.save(SCOPE, "author", "invalid",
                FixtureSetPrecondition.create(), "invalid", invalid))
                .isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting(value -> ((ApiFixtureSetAuthoringFailure) value).code())
                .isEqualTo(ApiFixtureSetAuthoringFailure.Code.VALIDATION);
        assertThat(store.findHead(SCOPE, "invalid")).isEmpty();
    }

    private static ComponentFixtureSetModule module(InMemoryStandaloneFixtureSetStore store) {
        ComponentSimulationAuthorityV2 components = (scope, subject) -> {
            ExactFixtureSubjectRefV2 expected = ExactFixtureSubjectRefV2.from(
                    subject instanceof ExactFixtureSubjectRefV2.OperatorVersion ? OPERATOR : FUNCTION);
            return expected.equals(subject)
                    ? Optional.of(new ComponentSimulationAuthorityV2.ComponentContract(
                    schema(), schema(), List.of())) : Optional.empty();
        };
        return new ComponentFixtureSetModule(
                components, store, new ComponentFixtureSetMaterializer());
    }

    private static FixtureSetCommand command(FixtureSubjectRef subject, String caseId, int score) {
        return new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION, "Component cases", subject,
                List.of(new FixtureSetCommand.Case(caseId, caseId, input(score), List.of(
                        new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                                FixtureSetCommand.Behavior.returned(
                                        FixtureSetCommand.Material.inline(output(score))), null)),
                        new FixtureSetCommand.Expect(output(score)))));
    }

    private static com.fasterxml.jackson.databind.JsonNode input(int value) {
        return JSON.createObjectNode().put("value", value);
    }

    private static com.fasterxml.jackson.databind.JsonNode output(int value) {
        return JSON.createObjectNode().put("value", value);
    }

    private static SchemaEnvelope schema() {
        return SchemaEnvelope.object(
                java.util.Map.of("value", java.util.Map.of("type", "integer")),
                java.util.List.of("value"));
    }
}
