package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowFixturePlanCompilerV2Test {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "test");
    private static final String ROOT = "sha256:" + "a".repeat(64);
    private static final String CHILD = "sha256:" + "b".repeat(64);
    private static final String PROFILE = "sha256:" + "c".repeat(64);
    private static final String CREDIT = "sha256:" + "d".repeat(64);
    private static final String OPERATOR = "sha256:" + "1".repeat(64);

    @Test
    void compilesExactNestedNodePathsWithoutResolvingDynamicConditionsEarly() {
        ReusableFlowPublicationStore publications = publications();
        FixtureSetAuthorityReader fixtureAuthority = mock(FixtureSetAuthorityReader.class);
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications,
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(fixtureAuthority));
        SimulationCommandV2.ExactFixtureSetRef fixtureRef = fixture(
                fixtureAuthority, "credit-fixtures", "credit", CREDIT);
        SimulationCommandV2 command = command(List.of(new SimulationCommandV2.FixtureBinding(
                new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk", "credit")),
                new SimulationCommandV2.FixtureSelection.AutoMatch(fixtureRef))));

        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE, command);

        assertThat(plan.nodes().keySet()).containsExactlyInAnyOrder(
                List.of("profile"), List.of("risk"), List.of("risk", "credit"));
        assertThat(plan.nodes().get(List.of("risk", "credit")).subject())
                .isEqualTo(new ExactFixtureSubjectRefV2.ApiResource("credit", 1, CREDIT));
        assertThat(plan.bindings()).containsOnlyKeys(List.of("risk", "credit"));
        assertThat(plan.bindings().get(List.of("risk", "credit")).fixedSelection()).isNull();
    }

    @Test
    void evaluatesAutoMatchAgainstEachMappedInvocationInput() {
        ReusableFlowPublicationStore publications = publications();
        FixtureSetAuthorityReader fixtureAuthority = mock(FixtureSetAuthorityReader.class);
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications,
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(fixtureAuthority));
        SimulationCommandV2.ExactFixtureSetRef fixtureRef = fixture(
                fixtureAuthority, "credit-fixtures", "credit", CREDIT);
        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE, command(List.of(
                new SimulationCommandV2.FixtureBinding(
                        new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk", "credit")),
                        new SimulationCommandV2.FixtureSelection.AutoMatch(fixtureRef)))));
        ResolvedFlowSimulationPlanV2.Node node = plan.nodes().get(List.of("risk", "credit"));
        ResolvedFlowSimulationPlanV2.Binding binding = plan.bindings().get(List.of("risk", "credit"));

        ResolvedFixturePlan.Selection selected = compiler.resolveInvocation(
                SCOPE, node, binding, JSON.createObjectNode().put("score", 420));

        assertThat(selected.caseId()).isEqualTo("low-score");
        assertThat(selected.matchedBy()).isEqualTo(ResolvedFixturePlan.MatchedBy.AUTO_MATCH);
        assertThat(selected.target()).isEqualTo(
                new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk", "credit")));
    }

    @Test
    void compilesCaseControlsIntoReusableFixedNodeBindings() {
        FixtureSetAuthorityReader fixtureAuthority = mock(FixtureSetAuthorityReader.class);
        SimulationCommandV2.ExactFixtureSetRef fixture = parentFixture(fixtureAuthority);
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications(),
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(fixtureAuthority));
        SimulationCommandV2 command = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, ROOT),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode().put("customerId", "c-1")),
                new SimulationCommandV2.FixturePlan.CaseControls(
                        fixture, "root-plan", SimulationCommandV2.Unmatched.BLOCK),
                SimulationCommandV2.ExecutionPolicy.denyAll());

        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE, command);

        assertThat(plan.bindings()).containsOnlyKeys(List.of("risk"));
        assertThat(plan.bindings().get(List.of("risk")).selection()).isNull();
        assertThat(plan.bindings().get(List.of("risk")).fixedSelection().caseId())
                .isEqualTo("root-plan");
    }

    @Test
    void rejectsAncestorAndDescendantBindingsBeforeMaterialAccess() {
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications(),
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(mock(FixtureSetAuthorityReader.class)));
        SimulationCommandV2.ExactFixtureSetRef fixture =
                new SimulationCommandV2.ExactFixtureSetRef("fixture", 1, "sha256:" + "f".repeat(64));

        assertThatThrownBy(() -> compiler.compile(SCOPE, command(List.of(
                binding(List.of("risk"), fixture), binding(List.of("risk", "credit"), fixture)))))
                .isInstanceOf(FixturePlanFailure.class)
                .extracting(value -> ((FixturePlanFailure) value).code())
                .isEqualTo(FixturePlanFailure.Code.TARGET_OVERLAP);
    }

    @Test
    void rejectsUnknownNestedPathsAndFingerprintDrift() {
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications(),
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(mock(FixtureSetAuthorityReader.class)));
        SimulationCommandV2.ExactFixtureSetRef fixture =
                new SimulationCommandV2.ExactFixtureSetRef("fixture", 1, "sha256:" + "f".repeat(64));
        assertThatThrownBy(() -> compiler.compile(SCOPE,
                command(List.of(binding(List.of("risk", "missing"), fixture)))))
                .isInstanceOf(FixturePlanFailure.class)
                .extracting(value -> ((FixturePlanFailure) value).code())
                .isEqualTo(FixturePlanFailure.Code.FIXTURE_SUBJECT_MISMATCH);

        SimulationCommandV2 drifted = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, "sha256:" + "0".repeat(64)),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode()),
                new SimulationCommandV2.FixturePlan.None(), SimulationCommandV2.ExecutionPolicy.denyAll());
        assertThatThrownBy(() -> compiler.compile(SCOPE, drifted))
                .isInstanceOf(FixturePlanFailure.class)
                .extracting(value -> ((FixturePlanFailure) value).code())
                .isEqualTo(FixturePlanFailure.Code.FIXTURE_STALE);
    }

    @Test
    void compilesExactOperatorNodeContractAndFixtureBinding() {
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        ReusableFlowCommand.ComposableRef.OperatorVersion operator =
                new ReusableFlowCommand.ComposableRef.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        ReusableFlowVersion root = flow("root", ROOT, List.of(node("risk", operator,
                List.of(new ReusableFlowCommand.Input("$.score",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.score"))))), "risk");
        when(publications.findVersion(SCOPE, "root", 1)).thenReturn(Optional.of(root));
        ComponentSimulationAuthorityV2 components = mock(ComponentSimulationAuthorityV2.class);
        ExactFixtureSubjectRefV2.OperatorVersion subject =
                new ExactFixtureSubjectRefV2.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        when(components.resolve(SCOPE, subject)).thenReturn(Optional.of(
                new ComponentSimulationAuthorityV2.ComponentContract(schema(), schema(), List.of())));
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications,
                mock(ReusableFlowDraftStore.class),
                new FixturePlanCompiler(mock(FixtureSetAuthorityReader.class)), components);
        SimulationCommandV2.ExactFixtureSetRef fixture =
                new SimulationCommandV2.ExactFixtureSetRef(
                        "risk-operator-fixtures", 2, "sha256:" + "2".repeat(64));

        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE,
                command(List.of(binding(List.of("risk"), fixture))));

        assertThat(plan.nodes()).containsOnlyKeys(List.of("risk"));
        assertThat(plan.nodes().get(List.of("risk"))).satisfies(value -> {
            assertThat(value.subject()).isEqualTo(subject);
            assertThat(value.contract()).isEqualTo(
                    new ReusableFlowCommand.Contract(schema(), schema()));
            assertThat(value.childGraph()).isNull();
        });
        assertThat(plan.bindings()).containsOnlyKeys(List.of("risk"));
        verify(components).resolve(SCOPE, subject);
    }

    @Test
    void bindsTwoSameNameFunctionsToDifferentCompilerOwnedCallSites() {
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        ReusableFlowCommand.ComposableRef.OperatorVersion operator =
                new ReusableFlowCommand.ComposableRef.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        when(publications.findVersion(SCOPE, "root", 1)).thenReturn(Optional.of(flow(
                "root", ROOT, List.of(node("risk", operator, List.of())), "risk")));
        ExactFixtureSubjectRefV2.OperatorVersion operatorSubject =
                new ExactFixtureSubjectRefV2.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        ExactFixtureSubjectRefV2.BuiltinFunctionVersion function =
                new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                        "bloge", 1, "lookup", "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64));
        ComponentSimulationAuthorityV2 components = mock(ComponentSimulationAuthorityV2.class);
        when(components.resolve(SCOPE, operatorSubject)).thenReturn(Optional.of(
                new ComponentSimulationAuthorityV2.ComponentContract(schema(), schema(), List.of(
                        new ComponentSimulationAuthorityV2.CallSite(
                                "lookup:customer", function, schema(), schema(),
                                "sha256:" + "4".repeat(64)),
                        new ComponentSimulationAuthorityV2.CallSite(
                                "lookup:referrer", function, schema(), schema(),
                                "sha256:" + "5".repeat(64))))));
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications,
                mock(ReusableFlowDraftStore.class),
                new FixturePlanCompiler(mock(FixtureSetAuthorityReader.class)), components);
        SimulationCommandV2.ExactFixtureSetRef left = new SimulationCommandV2.ExactFixtureSetRef(
                "lookup-customer", 1, "sha256:" + "6".repeat(64));
        SimulationCommandV2.ExactFixtureSetRef right = new SimulationCommandV2.ExactFixtureSetRef(
                "lookup-referrer", 1, "sha256:" + "7".repeat(64));
        SimulationCommandV2.FixtureTarget.CallSite leftTarget =
                new SimulationCommandV2.FixtureTarget.CallSite(
                        List.of("risk"), "lookup:customer");
        SimulationCommandV2.FixtureTarget.CallSite rightTarget =
                new SimulationCommandV2.FixtureTarget.CallSite(
                        List.of("risk"), "lookup:referrer");
        SimulationCommandV2 command = new SimulationCommandV2(
                SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, ROOT),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode()),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(
                                new SimulationCommandV2.FixtureBinding(leftTarget,
                                        new SimulationCommandV2.FixtureSelection.ExactCase(left, "customer")),
                                new SimulationCommandV2.FixtureBinding(rightTarget,
                                        new SimulationCommandV2.FixtureSelection.ExactCase(right, "referrer")))),
                SimulationCommandV2.ExecutionPolicy.denyAll());

        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE, command);

        assertThat(plan.callSiteBindings()).containsOnlyKeys(leftTarget, rightTarget);
        assertThat(plan.bindings()).isEmpty();
        assertThat(plan.nodes().get(List.of("risk")).callSites())
                .extracting(ComponentSimulationAuthorityV2.CallSite::callSiteId)
                .containsExactly("lookup:customer", "lookup:referrer");
    }

    @Test
    void resolvesCallSiteFixtureAgainstTheCallableRatherThanOwningOperator() {
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        ReusableFlowCommand.ComposableRef.OperatorVersion operator =
                new ReusableFlowCommand.ComposableRef.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        when(publications.findVersion(SCOPE, "root", 1)).thenReturn(Optional.of(flow(
                "root", ROOT, List.of(node("risk", operator, List.of())), "risk")));
        ExactFixtureSubjectRefV2.OperatorVersion operatorSubject =
                new ExactFixtureSubjectRefV2.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        ExactFixtureSubjectRefV2.BuiltinFunctionVersion function =
                new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                        "bloge", 1, "lookup", "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64));
        ComponentSimulationAuthorityV2.CallSite site =
                new ComponentSimulationAuthorityV2.CallSite(
                        "lookup:customer", function, schema(), schema(),
                        "sha256:" + "4".repeat(64));
        ComponentSimulationAuthorityV2 components = mock(ComponentSimulationAuthorityV2.class);
        when(components.resolve(SCOPE, operatorSubject)).thenReturn(Optional.of(
                new ComponentSimulationAuthorityV2.ComponentContract(
                        schema(), schema(), List.of(site))));
        FixtureSetAuthorityReader fixtures = mock(FixtureSetAuthorityReader.class);
        SimulationCommandV2.ExactFixtureSetRef fixture = functionFixture(fixtures, function);
        FlowFixturePlanCompilerV2 compiler = new FlowFixturePlanCompilerV2(publications,
                mock(ReusableFlowDraftStore.class), new FixturePlanCompiler(fixtures), components);
        SimulationCommandV2.FixtureTarget.CallSite target =
                new SimulationCommandV2.FixtureTarget.CallSite(
                        List.of("risk"), site.callSiteId());
        SimulationCommandV2 command = new SimulationCommandV2(
                SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, ROOT),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode()),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(new SimulationCommandV2.FixtureBinding(target,
                                new SimulationCommandV2.FixtureSelection.AutoMatch(fixture)))),
                SimulationCommandV2.ExecutionPolicy.denyAll());
        ResolvedFlowSimulationPlanV2 plan = compiler.compile(SCOPE, command);
        ResolvedFlowSimulationPlanV2.Node node = plan.nodes().get(List.of("risk"));

        ResolvedFixturePlan.Selection selected = compiler.resolveInvocation(
                SCOPE, node, plan.callSiteBindings().get(target),
                JSON.createObjectNode().put("id", "c-1"));

        assertThat(selected.caseId()).isEqualTo("customer");
        assertThat(selected.target()).isEqualTo(target);
    }

    private static SimulationCommandV2 command(List<SimulationCommandV2.FixtureBinding> bindings) {
        return new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, ROOT),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode().put("customerId", "c-1")),
                new SimulationCommandV2.FixturePlan.Bindings(
                        SimulationCommandV2.Unmatched.BLOCK, bindings),
                SimulationCommandV2.ExecutionPolicy.denyAll());
    }

    private static SimulationCommandV2.FixtureBinding binding(
            List<String> path, SimulationCommandV2.ExactFixtureSetRef fixture) {
        return new SimulationCommandV2.FixtureBinding(
                new SimulationCommandV2.FixtureTarget.NodePath(path),
                new SimulationCommandV2.FixtureSelection.ExactCase(fixture, "low-score"));
    }

    static ReusableFlowPublicationStore publications() {
        ReusableFlowPublicationStore store = mock(ReusableFlowPublicationStore.class);
        ReusableFlowVersion child = flow("child", CHILD, List.of(
                node("credit", new ReusableFlowCommand.ComposableRef.ApiResource("credit", 1, CREDIT),
                        List.of(new ReusableFlowCommand.Input("$.score",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.score"))))), "credit");
        ReusableFlowVersion root = flow("root", ROOT, List.of(
                node("profile", new ReusableFlowCommand.ComposableRef.ApiResource("profile", 1, PROFILE),
                        List.of(new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                node("risk", new ReusableFlowCommand.ComposableRef.FlowVersion("child", 1, CHILD),
                        List.of(new ReusableFlowCommand.Input("$.score",
                                new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.score"))))), "risk");
        when(store.findVersion(SCOPE, "root", 1)).thenReturn(Optional.of(root));
        when(store.findVersion(SCOPE, "child", 1)).thenReturn(Optional.of(child));
        return store;
    }

    private static ReusableFlowVersion flow(String id, String fingerprint,
                                            List<ReusableFlowCommand.Node> nodes, String outputNode) {
        ReusableFlowCommand.Contract contract = new ReusableFlowCommand.Contract(schema(), schema());
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, id, 1, fingerprint,
                new ReusableFlowVersion.Source(id + "-draft", 1, "sha256:" + "e".repeat(64)),
                id, id, ReusableFlowCommand.Kind.TOOL, id, contract,
                new ReusableFlowCommand.Graph(nodes, new ReusableFlowCommand.Output(outputNode, "$")),
                Instant.parse("2030-01-01T00:00:00Z"), "author", ReusableFlowVersion.Status.PUBLISHED);
    }

    private static ReusableFlowCommand.Node node(String id, ReusableFlowCommand.ComposableRef use,
                                                 List<ReusableFlowCommand.Input> inputs) {
        return new ReusableFlowCommand.Node(id, id, use, inputs);
    }

    static SimulationCommandV2.ExactFixtureSetRef fixture(
            FixtureSetAuthorityReader authority, String fixtureSetId, String resourceId,
            String resourceFingerprint) {
        FixtureSubjectRef.ApiResource subject = new FixtureSubjectRef.ApiResource(
                resourceId, 1, resourceFingerprint);
        FixtureSetCommand.Condition condition = new FixtureSetCommand.Condition("low-score",
                List.of(new FixtureSetCommand.Predicate.NumberRange("$.score", null,
                        java.math.BigDecimal.valueOf(500))));
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "low-score", "Low score", JSON.createObjectNode().put("score", 420), condition,
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                JSON.createObjectNode().put("risk", "high"))),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL)), null);
        String fingerprint = FixtureSetFingerprints.of(fixtureSetId, subject, List.of(fixtureCase));
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, fixtureSetId, 1,
                fingerprint, 1, fixtureSetId, subject, List.of(fixtureCase),
                FixtureSetView.Status.PRIVATE_DRAFT);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                fixtureSetId, 1, fingerprint, subject, List.of("low-score"),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                fixtureSetId, 1, fingerprint, fixtureSetId, subject,
                List.of(new FixtureSetSummary.CaseSummary("low-score", "Low score")),
                FixtureSetView.Status.PRIVATE_DRAFT, 1);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view, receipt, summary,
                List.of(new GeneratedDefaultFixture.CaseMapping("low-score", "low-score")));
        when(authority.findRevision(SCOPE, fixtureSetId, 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        return new SimulationCommandV2.ExactFixtureSetRef(fixtureSetId, 1, fingerprint);
    }

    private static SimulationCommandV2.ExactFixtureSetRef parentFixture(
            FixtureSetAuthorityReader authority) {
        FixtureSubjectRef.FlowVersion subject = new FixtureSubjectRef.FlowVersion("root", 1, ROOT);
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "root-plan", "Root plan", JSON.createObjectNode().put("customerId", "c-1"),
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.node("risk"),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                JSON.createObjectNode().put("risk", "low"))),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL)), null);
        String fingerprint = FixtureSetFingerprints.of("Root plan", subject, List.of(fixtureCase));
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, "root-plan", 1,
                fingerprint, 1, "Root plan", subject, List.of(fixtureCase),
                FixtureSetView.Status.PRIVATE_DRAFT);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view,
                new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION, "root-plan", 1,
                        fingerprint, subject, List.of("root-plan"),
                        FixtureSetView.Status.PRIVATE_DRAFT, 1),
                new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, "root-plan", 1,
                        fingerprint, "Root plan", subject,
                        List.of(new FixtureSetSummary.CaseSummary("root-plan", "Root plan")),
                        FixtureSetView.Status.PRIVATE_DRAFT, 1),
                List.of(new GeneratedDefaultFixture.CaseMapping("root-plan", "root-plan")));
        when(authority.findRevision(SCOPE, "root-plan", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        return new SimulationCommandV2.ExactFixtureSetRef("root-plan", 1, fingerprint);
    }

    private static SimulationCommandV2.ExactFixtureSetRef functionFixture(
            FixtureSetAuthorityReader authority,
            ExactFixtureSubjectRefV2.BuiltinFunctionVersion exactSubject) {
        FixtureSubjectRef subject = exactSubject.toLegacyAuthority();
        FixtureSetCommand.Condition condition = new FixtureSetCommand.Condition(
                "customer", List.of(new FixtureSetCommand.Predicate.Eq(
                "$.id", JSON.getNodeFactory().textNode("c-1"))));
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "customer", "Customer", JSON.createObjectNode().put("id", "c-1"), condition,
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                JSON.createObjectNode().put("value", "customer"))),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL)), null);
        String fingerprint = FixtureSetFingerprints.of(
                "Lookup", subject, List.of(fixtureCase));
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION,
                "lookup-fixtures", 1, fingerprint, 1, "Lookup", subject,
                List.of(fixtureCase), FixtureSetView.Status.PRIVATE_DRAFT);
        GeneratedDefaultFixture generated = new GeneratedDefaultFixture(view,
                new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                        "lookup-fixtures", 1, fingerprint, subject, List.of("customer"),
                        FixtureSetView.Status.PRIVATE_DRAFT, 1),
                new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                        "lookup-fixtures", 1, fingerprint, "Lookup", subject,
                        List.of(new FixtureSetSummary.CaseSummary("customer", "Customer")),
                        FixtureSetView.Status.PRIVATE_DRAFT, 1),
                List.of(new GeneratedDefaultFixture.CaseMapping("customer", "customer")));
        when(authority.findRevision(SCOPE, "lookup-fixtures", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        return new SimulationCommandV2.ExactFixtureSetRef(
                "lookup-fixtures", 1, fingerprint);
    }

    private static SchemaEnvelope schema() {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object", "additionalProperties", true));
    }
}
