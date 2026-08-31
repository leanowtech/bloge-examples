package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceSaveCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFixtureSetMaterializerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void selectedExamplesBecomeOrderedPrivateSubjectReturnCases() {
        GeneratedDefaultFixture generated = new DefaultFixtureSetMaterializer().generate(
                resource(), new ApiResourceSaveCommand.DefaultFixture.FromExamples(
                        "Default customer cases", List.of("missing", "happy")));

        assertThat(generated.view().fixtureSetId()).isEqualTo("customer-profile:r1");
        assertThat(generated.view().revision()).isEqualTo(1);
        assertThat(generated.view().status()).isEqualTo(FixtureSetView.Status.PRIVATE_DRAFT);
        assertThat(generated.view().statusRevision()).isEqualTo(1);
        assertThat(generated.view().subject()).isEqualTo(FixtureSubjectRef.apiResource(resource().ref()));
        assertThat(generated.view().cases()).extracting(FixtureSetCommand.Case::caseId)
                .containsExactly("missing", "happy");
        assertThat(generated.caseMappings()).containsExactly(
                new GeneratedDefaultFixture.CaseMapping("missing", "missing"),
                new GeneratedDefaultFixture.CaseMapping("happy", "happy"));

        FixtureSetCommand.Case first = generated.view().cases().getFirst();
        assertThat(first.input()).isEqualTo(JSON.createObjectNode().put("customerId", "missing"));
        assertThat(first.controls()).hasSize(1);
        assertThat(first.controls().getFirst().target()).isInstanceOf(FixtureSetCommand.Target.Subject.class);
        FixtureSetCommand.Behavior.Return returned = (FixtureSetCommand.Behavior.Return)
                first.controls().getFirst().behavior();
        assertThat(((FixtureSetCommand.Material.Inline) returned.material()).value())
                .isEqualTo(JSON.createObjectNode().put("found", false));
        assertThat(first.expect()).isNull();

        assertThat(generated.receipt().fixtureSetId()).isEqualTo(generated.view().fixtureSetId());
        assertThat(generated.receipt().fingerprint()).isEqualTo(generated.view().fingerprint());
        assertThat(generated.receipt().caseIds()).containsExactly("missing", "happy");
        assertThat(generated.summary().cases()).extracting(FixtureSetSummary.CaseSummary::caseId)
                .containsExactly("missing", "happy");
    }

    @Test
    void selectionMustBeNonEmptyUniqueAndReferenceKnownExamples() {
        DefaultFixtureSetMaterializer materializer = new DefaultFixtureSetMaterializer();

        assertThatThrownBy(() -> materializer.generate(resource(), request(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materializer.generate(resource(), request(List.of("happy", "happy"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materializer.generate(resource(), request(List.of("unknown"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedMaterialAndCollectionsAreDefensivelyCopiedAndFingerprintIsStable() {
        DefaultFixtureSetMaterializer materializer = new DefaultFixtureSetMaterializer();
        GeneratedDefaultFixture first = materializer.generate(resource(), request(List.of("happy")));
        GeneratedDefaultFixture second = materializer.generate(resource(), request(List.of("happy")));

        assertThat(first.view().fingerprint()).isEqualTo(second.view().fingerprint());
        assertThatThrownBy(() -> first.view().cases().add(first.view().cases().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        ObjectNode returned = (ObjectNode) ((FixtureSetCommand.Material.Inline)
                ((FixtureSetCommand.Behavior.Return) first.view().cases().getFirst()
                        .controls().getFirst().behavior()).material()).value();
        returned.put("found", false);
        assertThat(((FixtureSetCommand.Material.Inline)
                ((FixtureSetCommand.Behavior.Return) first.view().cases().getFirst()
                        .controls().getFirst().behavior()).material()).value().path("found").asBoolean()).isTrue();
        assertThat(first.toString()).doesNotContain("customer-1", "\"found\"");
        assertThat(first.view().toString()).doesNotContain("customer-1", "\"found\"");
        assertThat(new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION, first.view().displayName(),
                first.view().subject(), first.view().cases()).toString()).doesNotContain("customer-1", "\"found\"");
    }

    private static ApiResourceSaveCommand.DefaultFixture.FromExamples request(List<String> names) {
        return new ApiResourceSaveCommand.DefaultFixture.FromExamples("Default customer cases", names);
    }

    private static ApiResourceSpec resource() {
        return new ApiResourceDecisions(JSON).next(Optional.empty(), "customer-profile", "customer-api",
                command(), ExpectedRevision.create());
    }

    private static ApiResourceCommand command() {
        return new ApiResourceCommand("Customer profile", null,
                new ApiResourceCommand.Operation("GET", "/customers/{customerId}", List.of(
                        new ApiResourceCommand.Binding("$.customerId",
                                new ApiResourceCommand.Location("PATH", "customerId")))),
                new ApiResourceCommand.Contract(schema("customerId", "string"), schema("found", "boolean")),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(),
                List.of(
                        new ApiResourceCommand.Example("happy",
                                JSON.createObjectNode().put("customerId", "customer-1"),
                                JSON.createObjectNode().put("found", true)),
                        new ApiResourceCommand.Example("missing",
                                JSON.createObjectNode().put("customerId", "missing"),
                                JSON.createObjectNode().put("found", false))));
    }

    private static SchemaEnvelope schema(String property, String type) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(property, Map.of("type", type)),
                "required", List.of(property),
                "additionalProperties", false));
    }
}
