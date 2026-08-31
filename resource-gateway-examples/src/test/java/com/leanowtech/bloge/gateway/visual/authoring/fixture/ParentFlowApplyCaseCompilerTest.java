package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParentFlowApplyCaseCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String PROFILE_FP = "sha256:" + "a".repeat(64);
    private static final String DECISION_FP = "sha256:" + "b".repeat(64);

    @Test
    void compilesExactSubjectReturnsThroughParentMappings() {
        ReusableFlowVersion parent = parent();
        StoredFixtureSet profile = fixture("profile-cases", FixtureSubjectRef.apiResource(
                new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec.ResourceRef(
                        "API_RESOURCE", "profile-api", 2, PROFILE_FP)),
                returned("high-score", JSON.createObjectNode().put("customerId", "stored-input"),
                        JSON.createObjectNode().put("score", 700),
                        FixtureSetCommand.Fidelity.PROTOCOL_DERIVED));
        StoredFixtureSet decision = fixture("decision-cases",
                new FixtureSubjectRef.FlowVersion("decision-flow", 3, DECISION_FP),
                returned("approved", JSON.createObjectNode().put("score", 0),
                        JSON.createObjectNode().put("eligible", true), null));
        ParentFlowApplyCaseCompiler compiler = new ParentFlowApplyCaseCompiler(
                catalog(), reader(Map.of("profile-cases", profile, "decision-cases", decision)));

        ParentFlowApplyCaseCompiler.CompiledCase compiled = compiler.compile(
                SCOPE, parent, parentCase());

        assertThat(compiled.subject()).isEqualTo(parent.subject());
        assertThat(compiled.output()).isEqualTo(JSON.createObjectNode().put("eligible", true));
        assertThat(compiled.nodes()).extracting(ParentFlowApplyCaseCompiler.CompiledNode::nodeId)
                .containsExactly("profile", "decision");
        assertThat(compiled.nodes().getFirst().fixtureSetId()).isEqualTo("profile-cases");
        assertThat(compiled.nodes().getFirst().fidelity())
                .isEqualTo(FixtureSetCommand.Fidelity.PROTOCOL_DERIVED);
        assertThat(compiled.nodes().getLast().apiResource()).isFalse();
        assertThat(compiled.toString()).doesNotContain("eligible", "live-input", "stored-input");
    }

    @Test
    void rejectsSubjectDriftNestedApplyCaseAndIncompleteCoverage() {
        ReusableFlowVersion parent = parent();
        StoredFixtureSet drifted = fixture("profile-cases",
                new FixtureSubjectRef.FlowVersion("decision-flow", 3, DECISION_FP),
                returned("high-score", JSON.createObjectNode(),
                        JSON.createObjectNode().put("score", 700), null));
        StoredFixtureSet nested = fixture("decision-cases",
                new FixtureSubjectRef.FlowVersion("decision-flow", 3, DECISION_FP),
                new FixtureSetCommand.Case("approved", "Approved", JSON.createObjectNode(),
                        List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                                new FixtureSetCommand.Behavior.ApplyCase(
                                        "other", 1, "case"), null)), null));

        assertThatThrownBy(() -> new ParentFlowApplyCaseCompiler(
                catalog(), reader(Map.of("profile-cases", drifted)))
                .compile(SCOPE, parent, withControls(parentCase(), List.of(
                        apply("profile", "profile-cases", "high-score"),
                        apply("decision", "profile-cases", "high-score")))))
                .isInstanceOf(ParentFlowApplyCaseFailure.class)
                .extracting(value -> ((ParentFlowApplyCaseFailure) value).code())
                .isEqualTo(ParentFlowApplyCaseFailure.Code.INTEGRITY);
        assertThatThrownBy(() -> new ParentFlowApplyCaseCompiler(
                catalog(), reader(Map.of("profile-cases", fixture("profile-cases",
                        FixtureSubjectRef.apiResource(new com.leanowtech.bloge.gateway.visual.authoring.resource
                                .ApiResourceSpec.ResourceRef("API_RESOURCE", "profile-api", 2, PROFILE_FP)),
                        returned("high-score", JSON.createObjectNode(),
                                JSON.createObjectNode().put("score", 700), null)),
                        "decision-cases", nested))).compile(SCOPE, parent, parentCase()))
                .isInstanceOf(ParentFlowApplyCaseFailure.class)
                .extracting(value -> ((ParentFlowApplyCaseFailure) value).code())
                .isEqualTo(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
        assertThatThrownBy(() -> new ParentFlowApplyCaseCompiler(
                catalog(), reader(Map.of())).compile(SCOPE, parent,
                withControls(parentCase(), List.of(apply("profile", "profile-cases", "high-score")))))
                .isInstanceOf(ParentFlowApplyCaseFailure.class)
                .extracting(value -> ((ParentFlowApplyCaseFailure) value).code())
                .isEqualTo(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);

        StoredFixtureSet exact = fixture("profile-cases", FixtureSubjectRef.apiResource(
                new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec.ResourceRef(
                        "API_RESOURCE", "profile-api", 2, PROFILE_FP)),
                returned("high-score", JSON.createObjectNode(),
                        JSON.createObjectNode().put("score", 700), null));
        StoredFixtureSet wrongScope = new StoredFixtureSet(
                new AuthoringScope("other", "project", "dev"), exact.generated());
        assertThatThrownBy(() -> new ParentFlowApplyCaseCompiler(
                catalog(), reader(Map.of("profile-cases", wrongScope)))
                .compile(SCOPE, parent, withControls(parentCase(), List.of(
                        apply("profile", "profile-cases", "high-score"),
                        apply("decision", "profile-cases", "high-score")))))
                .isInstanceOf(ParentFlowApplyCaseFailure.class)
                .extracting(value -> ((ParentFlowApplyCaseFailure) value).code())
                .isEqualTo(ParentFlowApplyCaseFailure.Code.INTEGRITY);
    }

    private static ReusableFlowVersion parent() {
        ReusableFlowCommand.Contract contract = new ReusableFlowCommand.Contract(
                object("customerId", "string"), object("eligible", "boolean"));
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(List.of(
                new ReusableFlowCommand.Node("profile", "Profile",
                        new ReusableFlowCommand.ComposableRef.ApiResource(
                                "profile-api", 2, PROFILE_FP),
                        List.of(new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                new ReusableFlowCommand.Node("decision", "Decision",
                        new ReusableFlowCommand.ComposableRef.FlowVersion(
                                "decision-flow", 3, DECISION_FP),
                        List.of(new ReusableFlowCommand.Input("$.score",
                                new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.score"))))),
                new ReusableFlowCommand.Output("decision", "$"));
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, "parent-flow", 1,
                "sha256:" + "c".repeat(64), new ReusableFlowVersion.Source("parent-draft", 4,
                "sha256:" + "d".repeat(64)), "parent", "Parent", ReusableFlowCommand.Kind.SOLUTION,
                "Parent solution", contract, graph, Instant.parse("2030-01-01T00:00:00Z"), "author",
                ReusableFlowVersion.Status.PUBLISHED);
    }

    private static FixtureSetCommand.Case parentCase() {
        return new MutableCase("customer", "Customer", JSON.createObjectNode().put("customerId", "live-input"),
                List.of(apply("profile", "profile-cases", "high-score"),
                        apply("decision", "decision-cases", "approved")),
                new FixtureSetCommand.Expect(JSON.createObjectNode().put("eligible", true))).value();
    }

    private static FixtureSetCommand.Control apply(String nodeId, String fixtureSetId, String caseId) {
        return new FixtureSetCommand.Control(FixtureSetCommand.Target.node(nodeId),
                new FixtureSetCommand.Behavior.ApplyCase(fixtureSetId, 1, caseId), null);
    }

    private static FixtureSetCommand.Case returned(String caseId, JsonNode input, JsonNode output,
                                                    FixtureSetCommand.Fidelity fidelity) {
        return new FixtureSetCommand.Case(caseId, caseId, input,
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output)), fidelity)),
                null);
    }

    private static StoredFixtureSet fixture(String id, FixtureSubjectRef subject,
                                            FixtureSetCommand.Case fixtureCase) {
        String displayName = id;
        String fingerprint = FixtureSetFingerprints.of(displayName, subject, List.of(fixtureCase));
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, id, 1, fingerprint, 1,
                displayName, subject, List.of(fixtureCase), FixtureSetView.Status.PRIVATE_DRAFT);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                id, 1, fingerprint, subject, List.of(fixtureCase.caseId()),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, id, 1,
                fingerprint, displayName, subject,
                List.of(new FixtureSetSummary.CaseSummary(fixtureCase.caseId(), fixtureCase.name())),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, receipt, summary,
                List.of(new GeneratedDefaultFixture.CaseMapping(fixtureCase.caseId(), fixtureCase.caseId())));
        return new StoredFixtureSet(SCOPE, generated);
    }

    private static FixtureSetAuthorityReader reader(Map<String, StoredFixtureSet> fixtures) {
        return new FixtureSetAuthorityReader() {
            @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String id) {
                return Optional.ofNullable(fixtures.get(id));
            }
            @Override public Optional<StoredFixtureSet> findRevision(AuthoringScope scope, String id, int revision) {
                return revision == 1 ? Optional.ofNullable(fixtures.get(id)) : Optional.empty();
            }
            @Override public List<FixtureSetSummary> listSummariesBySubject(
                    AuthoringScope scope, FixtureSubjectRef subject) { return List.of(); }
        };
    }

    private static ComposableCatalog catalog() {
        Map<ReusableFlowCommand.ComposableRef, ComposableDefinition> values = Map.of(
                new ReusableFlowCommand.ComposableRef.ApiResource("profile-api", 2, PROFILE_FP),
                new ComposableDefinition(new ReusableFlowCommand.ComposableRef.ApiResource(
                        "profile-api", 2, PROFILE_FP), object("customerId", "string"),
                        object("score", "integer")),
                new ReusableFlowCommand.ComposableRef.FlowVersion("decision-flow", 3, DECISION_FP),
                new ComposableDefinition(new ReusableFlowCommand.ComposableRef.FlowVersion(
                        "decision-flow", 3, DECISION_FP), object("score", "integer"),
                        object("eligible", "boolean")));
        return (scope, reference) -> Optional.ofNullable(values.get(reference));
    }

    private static SchemaEnvelope object(String property, String type) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", type)), List.of(property));
    }

    private record MutableCase(String caseId, String name, JsonNode input,
                               List<FixtureSetCommand.Control> controls, FixtureSetCommand.Expect expect) {
        FixtureSetCommand.Case value() { return new FixtureSetCommand.Case(caseId, name, input, controls, expect); }
    }

    private static FixtureSetCommand.Case withControls(FixtureSetCommand.Case source,
                                                        List<FixtureSetCommand.Control> controls) {
        return new FixtureSetCommand.Case(source.caseId(), source.name(), source.input(), controls, source.expect());
    }
}
