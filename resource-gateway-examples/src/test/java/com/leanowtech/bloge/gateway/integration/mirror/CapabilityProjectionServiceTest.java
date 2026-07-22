package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityProjectionServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityProjectionService projection = new CapabilityProjectionService(mapper);

    @Test
    void projectsSafeResourceAsSealedReadOnlyExecutableCapability() {
        CapabilitySnapshot snapshot = projection.projectResource(resource("customers.get", "GET", null),
                objectSchema("customerId"), objectSchema("customer"), context(1));

        assertThat(snapshot.capabilityId()).isEqualTo("resource:customers.get");
        assertThat(snapshot.kind()).isEqualTo(CapabilitySnapshot.Kind.EXTERNAL);
        assertThat(snapshot.contract().effect().mode()).isEqualTo(EffectContract.Mode.READ_ONLY);
        assertThat(snapshot.contract().effect().readSet()).containsExactly("resource:customers.get");
        assertThat(snapshot.contract().idempotency().mode())
                .isEqualTo(CapabilityContract.IdempotencyMode.IDEMPOTENT);
        assertThat(snapshot.runtime().ready()).isTrue();
        assertThat(snapshot.contract().slo().timeout()).isEqualTo(Duration.ofSeconds(3));
        CapabilitySnapshotIntegrity.verify(mapper, snapshot);
    }

    @Test
    void keepsUnmanagedExternalWriteCriticalAndRuntimeBlocked() {
        CapabilitySnapshot snapshot = projection.projectResource(resource("orders.create", "POST", null),
                objectSchema("order"), objectSchema("receipt"), context(1));

        assertThat(snapshot.contract().effect().mode())
                .isEqualTo(EffectContract.Mode.EXTERNAL_MUTATION);
        assertThat(snapshot.contract().effect().writeSet()).containsExactly("resource:orders.create");
        assertThat(snapshot.contract().effect().riskLevel()).isEqualTo(EffectContract.RiskLevel.CRITICAL);
        assertThat(snapshot.contract().effect().requiresApproval()).isTrue();
        assertThat(snapshot.contract().idempotency().mode())
                .isEqualTo(CapabilityContract.IdempotencyMode.NON_IDEMPOTENT);
        assertThat(snapshot.runtime().ready()).isFalse();
        assertThat(snapshot.runtime().limitations()).singleElement()
                .asString().contains("managed-write contract");
    }

    @Test
    void projectsManagedExternalWriteAsKeyedExecutableCapability() {
        ResourceDescriptor.ExternalWriteContract writeContract = managedWriteContract();

        CapabilitySnapshot snapshot = projection.projectResource(
                resource("orders.create", "POST", writeContract), objectSchema("order"),
                objectSchema("receipt"), context(1));

        assertThat(snapshot.contract().effect().riskLevel()).isEqualTo(EffectContract.RiskLevel.HIGH);
        assertThat(snapshot.contract().idempotency()).isEqualTo(
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.KEYED, "idempotencyKey", true));
        assertThat(snapshot.runtime().ready()).isTrue();
    }

    @Test
    void rejectsPureInternalOperatorButPreservesContradictoryExternalPureAsUnknown() {
        OperatorDefinition pure = operator("normalizeCustomer", new OperatorDefinition.Source(
                "built-in", "", "", "", false), OperatorDefinition.Capabilities.pure(), Map.of());

        assertThatThrownBy(() -> projection.projectOperator(pure, context(1)))
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.NOT_CAPABILITY_BOUNDARY"));

        OperatorDefinition contradictory = operator("remoteCustomer", new OperatorDefinition.Source(
                "remote-worker", "", "", "", false), OperatorDefinition.Capabilities.pure(), Map.of());
        CapabilitySnapshot snapshot = projection.projectOperator(contradictory, context(1));

        assertThat(snapshot.contract().effect().mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
        assertThat(snapshot.contract().effect().riskLevel()).isEqualTo(EffectContract.RiskLevel.CRITICAL);
        assertThat(snapshot.runtime().ready()).isFalse();
    }

    @Test
    void blocksRuntimeReadinessWhenExecutableOperatorEffectIsUnknown() {
        OperatorDefinition operator = operator("opaqueBoundary", OperatorDefinition.Source.builtIn("built-in"),
                new OperatorDefinition.Capabilities("vendor-side-effect", "UNKNOWN",
                        false, false, false), Map.of());

        assertThat(operator.runtimeReadiness().executable()).isTrue();
        CapabilitySnapshot snapshot = projection.projectOperator(operator, context(1));

        assertThat(snapshot.contract().effect().mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
        assertThat(snapshot.runtime().ready()).isFalse();
        assertThat(snapshot.runtime().limitations()).containsExactly("effect contract is unresolved");
    }

    @Test
    void failsClosedWhenGraphCombinesVirtualAndExternalMutationWorlds() {
        EffectContract virtualMutation = new EffectContract("", EffectContract.Mode.VIRTUAL_MUTATION,
                List.of(), List.of("entity:refund"), List.of(), null, false,
                EffectContract.RiskLevel.MEDIUM, EffectContract.Derivation.DECLARED, List.of());
        EffectContract externalMutation = new EffectContract("", EffectContract.Mode.EXTERNAL_MUTATION,
                List.of(), List.of("resource:payments.refund"), List.of(), null, true,
                EffectContract.RiskLevel.HIGH, EffectContract.Derivation.DECLARED, List.of());

        EffectContract aggregate = CapabilityEffectAnalyzer.aggregate(List.of(
                new CapabilityEffectAnalyzer.ScopedEffect("virtual", "", virtualMutation),
                new CapabilityEffectAnalyzer.ScopedEffect("external", "", externalMutation)));

        assertThat(aggregate.mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
        assertThat(aggregate.riskLevel()).isEqualTo(EffectContract.RiskLevel.CRITICAL);
        assertThat(aggregate.unresolvedReasons()).containsExactly(
                "graph combines external and virtual mutations without an aggregate effect model");
        assertThat(aggregate.writeSet()).containsExactly("entity:refund", "resource:payments.refund");
    }

    @Test
    void aggregatesConditionalReadAndWriteEffectsIntoExactGraphDependencies() {
        OperatorDefinition read = resourceOperator("loadCustomer", "customers.get", "READ_EXTERNAL", false);
        OperatorDefinition write = resourceOperator("createOrder", "orders.create", "WRITE_EXTERNAL", true);
        OperatorDefinition pure = operator("normalize", OperatorDefinition.Source.builtIn("built-in"),
                OperatorDefinition.Capabilities.pure(), Map.of("mode", "strict"));
        GraphDraft draft = graphDraft(Map.of("normalize", pure, "load", read, "write", write),
                List.of(new GraphDraft.DraftEdge("approved", "route",
                        new GraphDraft.Endpoint("load", "", ""),
                        new GraphDraft.Endpoint("write", "", ""), "customer.approved == true")),
                Map.of());
        CapabilitySnapshot customer = projection.projectResource(resource("customers.get", "GET", null),
                objectSchema("customerId"), objectSchema("customer"), context(1));
        CapabilitySnapshot order = projection.projectResource(
                resource("orders.create", "POST", managedWriteContract()), objectSchema("order"),
                objectSchema("receipt"), context(1));

        CapabilitySnapshot graph = projection.projectGraph(draft, context(2), List.of(order, customer));

        assertThat(graph.kind()).isEqualTo(CapabilitySnapshot.Kind.COMPOSED);
        assertThat(graph.dependencies()).extracting(CapabilitySnapshot.Dependency::nodeId)
                .containsExactly("load", "write");
        assertThat(graph.dependencies().getFirst().required()).isTrue();
        assertThat(graph.dependencies().get(1).required()).isFalse();
        assertThat(graph.dependencies().get(1).conditions()).containsExactly("customer.approved == true");
        assertThat(graph.contract().effect().mode()).isEqualTo(EffectContract.Mode.MIXED);
        assertThat(graph.contract().effect().conditionalEffects()).singleElement()
                .satisfies(effect -> {
                    assertThat(effect.condition()).isEqualTo("customer.approved == true");
                    assertThat(effect.writeSet()).containsExactly("resource:orders.create");
                });
        assertThat(graph.runtime().ready()).isTrue();
        CapabilitySnapshotIntegrity.verify(mapper, graph);
    }

    @Test
    void resolvesGenericHttpResourceNodeFromItsConstantResourceIdBinding() {
        OperatorDefinition httpResource = operator("httpResource",
                OperatorDefinition.Source.builtIn("bloge-operator"),
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", false, false, true), Map.of());
        GraphDraft draft = graphDraft(Map.of("load", httpResource), List.of(), Map.of(),
                Map.of("load", Map.of("resourceId", GraphDraft.Binding.constant("customers.get"))));
        CapabilitySnapshot customer = projection.projectResource(resource("customers.get", "GET", null),
                objectSchema("customerId"), objectSchema("customer"), context(1));

        CapabilitySnapshot graph = projection.projectGraph(draft, context(2), List.of(customer));

        assertThat(graph.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.nodeId()).isEqualTo("load");
            assertThat(dependency.capabilityRef().id()).isEqualTo("resource:customers.get");
            assertThat(dependency.capabilityRef().fingerprint()).isEqualTo(customer.fingerprint());
        });
        assertThat(graph.contract().effect().mode()).isEqualTo(EffectContract.Mode.READ_ONLY);
    }

    @Test
    void keepsDynamicHttpResourceNodeAsConservativeGenericOperatorCapability() {
        OperatorDefinition httpResource = operator("httpResource",
                OperatorDefinition.Source.builtIn("bloge-operator"),
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", false, false, true), Map.of());
        GraphDraft draft = graphDraft(Map.of("dispatch", httpResource), List.of(), Map.of(),
                Map.of("dispatch", Map.of("resourceId", GraphDraft.Binding.contextPath("resourceId"))));
        CapabilitySnapshot genericHttp = projection.projectOperator(httpResource, context(1));

        CapabilitySnapshot graph = projection.projectGraph(draft, context(2), List.of(genericHttp));

        assertThat(graph.dependencies()).singleElement().satisfies(dependency ->
                assertThat(dependency.capabilityRef().id()).isEqualTo("operator:httpResource"));
        assertThat(graph.contract().effect().mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
        assertThat(graph.runtime().ready()).isFalse();
    }

    @Test
    void failsClosedWhenExternalChildIsMissingOrUnsealed() {
        OperatorDefinition read = resourceOperator("loadCustomer", "customers.get", "READ_EXTERNAL", false);
        GraphDraft draft = graphDraft(Map.of("load", read), List.of(), Map.of());

        assertThatThrownBy(() -> projection.projectGraph(draft, context(2), List.of()))
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.CHILD_CAPABILITY_MISSING"));

        CapabilitySnapshot sealed = projection.projectResource(resource("customers.get", "GET", null),
                objectSchema("customerId"), objectSchema("customer"), context(1));
        CapabilitySnapshot unsealed = sealed.withFingerprint("");
        assertThatThrownBy(() -> projection.projectGraph(draft, context(2), List.of(unsealed)))
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.CHILD_SNAPSHOT_INVALID"));
    }

    @Test
    void canonicalResourceFingerprintIgnoresMapInsertionOrder() {
        LinkedHashMap<String, String> firstHeaders = new LinkedHashMap<>();
        firstHeaders.put("X-B", "two");
        firstHeaders.put("X-A", "one");
        LinkedHashMap<String, String> secondHeaders = new LinkedHashMap<>();
        secondHeaders.put("X-A", "one");
        secondHeaders.put("X-B", "two");

        CapabilitySnapshot first = projection.projectResource(
                resource("customers.get", "GET", null, firstHeaders),
                objectSchema("customerId"), objectSchema("customer"), context(1));
        CapabilitySnapshot second = projection.projectResource(
                resource("customers.get", "GET", null, secondHeaders),
                objectSchema("customerId"), objectSchema("customer"), context(1));

        assertThat(first.source().sourceFingerprint()).isEqualTo(second.source().sourceFingerprint());
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    void pureNodeChangeMovesGraphFingerprintWithoutExpandingDependencyClosure() {
        OperatorDefinition read = resourceOperator("loadCustomer", "customers.get", "READ_EXTERNAL", false);
        OperatorDefinition pureV1 = operator("normalize", OperatorDefinition.Source.builtIn("built-in"),
                OperatorDefinition.Capabilities.pure(), Map.of("version", 1));
        OperatorDefinition pureV2 = operator("normalize", OperatorDefinition.Source.builtIn("built-in"),
                OperatorDefinition.Capabilities.pure(), Map.of("version", 2));
        GraphDraft firstDraft = graphDraft(Map.of("load", read, "normalize", pureV1), List.of(),
                Map.of("load", new GraphDraft.NodeFixture(Map.of("ignored", 1))));
        GraphDraft secondDraft = graphDraft(Map.of("load", read, "normalize", pureV2), List.of(),
                Map.of("load", new GraphDraft.NodeFixture(Map.of("ignored", 2))));
        CapabilitySnapshot customer = projection.projectResource(resource("customers.get", "GET", null),
                objectSchema("customerId"), objectSchema("customer"), context(1));

        CapabilitySnapshot first = projection.projectGraph(firstDraft, context(2), List.of(customer));
        CapabilitySnapshot second = projection.projectGraph(secondDraft, context(2), List.of(customer));

        assertThat(first.dependencies()).isEqualTo(second.dependencies());
        assertThat(first.dependencies()).extracting(CapabilitySnapshot.Dependency::nodeId)
                .containsExactly("load");
        assertThat(first.source().sourceFingerprint()).isNotEqualTo(second.source().sourceFingerprint());
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    private static ResourceDescriptor resource(String id,
                                               String method,
                                               ResourceDescriptor.ExternalWriteContract writeContract) {
        return resource(id, method, writeContract, Map.of("Accept", "application/json"));
    }

    private static ResourceDescriptor resource(String id,
                                               String method,
                                               ResourceDescriptor.ExternalWriteContract writeContract,
                                               Map<String, String> headers) {
        return new ResourceDescriptor(id, "https://api.example.test/" + id, method, headers, null,
                Duration.ofSeconds(3), ParameterMapping.empty(), new ResponseProtocol.HttpStatus(),
                "data", writeContract);
    }

    private static ResourceDescriptor.ExternalWriteContract managedWriteContract() {
        return new ResourceDescriptor.ExternalWriteContract(
                ResourceDescriptor.ExternalWriteContract.SCHEMA_VERSION,
                "idempotencyKey", "Idempotency-Key", "lookupRef", "orders.status",
                "X-Commit-Receipt", "X-Transaction-Id", "orders", "", "", false);
    }

    private static OperatorDefinition resourceOperator(String operatorRef,
                                                       String resourceId,
                                                       String effect,
                                                       boolean managedWrite) {
        OperatorDefinition.SideEffectProtocol sideEffect = managedWrite
                ? OperatorDefinition.SideEffectProtocol.journaled(
                "orders.status", "ctx.params.idempotencyKey", "ctx.params.lookupRef", "ctx.headers.receipt")
                : null;
        return operator(operatorRef, new OperatorDefinition.Source(
                        "resource-descriptor", resourceId, effect.equals("READ_EXTERNAL") ? "GET" : "POST",
                        "https://api.example.test/" + resourceId, true),
                new OperatorDefinition.Capabilities(effect,
                        managedWrite ? "IDEMPOTENT" : "UNKNOWN", false, false, false, sideEffect), Map.of());
    }

    private static OperatorDefinition operator(String operatorRef,
                                               OperatorDefinition.Source source,
                                               OperatorDefinition.Capabilities capabilities,
                                               Map<String, Object> loweringParameters) {
        OperatorDefinition.Ports ports = new OperatorDefinition.Ports(
                List.of(new OperatorDefinition.Port("input", objectSchema("id"), true, "")),
                List.of(new OperatorDefinition.Port("output", objectSchema("value"), true, "")));
        return new OperatorDefinition("", operatorRef, "1.0.0", "",
                new OperatorDefinition.Display(operatorRef, "", List.of()), source, ports,
                SchemaEnvelope.opaque(), capabilities, OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", operatorRef, loweringParameters), List.of());
    }

    private static GraphDraft graphDraft(Map<String, OperatorDefinition> operators,
                                         List<GraphDraft.DraftEdge> edges,
                                         Map<String, GraphDraft.NodeFixture> fixtures) {
        return graphDraft(operators, edges, fixtures, Map.of());
    }

    private static GraphDraft graphDraft(Map<String, OperatorDefinition> operators,
                                         List<GraphDraft.DraftEdge> edges,
                                         Map<String, GraphDraft.NodeFixture> fixtures,
                                         Map<String, Map<String, GraphDraft.Binding>> inputs) {
        List<GraphDraft.DraftNode> nodes = operators.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new GraphDraft.DraftNode(entry.getKey(), entry.getValue().operatorRef(),
                        entry.getKey(), inputs.getOrDefault(entry.getKey(), Map.of()), Map.of(),
                        new GraphDraft.Position(0, 0)))
                .toList();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        operators.forEach((nodeId, operator) -> fingerprints.put(nodeId, operator.fingerprint()));
        return new GraphDraft("", "draft-order-flow", 7, "orderFlow", "tenant-a", "support",
                "test", "DRAFT", objectSchema("request"), objectSchema("result"), nodes, edges,
                Map.of("zoom", 1), fixtures, new GraphDraft.OutputSelection(nodes.getLast().id(), ""),
                fingerprints, operators, GraphDraft.RevisionMetadata.empty());
    }

    private static SchemaEnvelope objectSchema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "object")), List.of(property));
    }

    private static CapabilityProjectionContext context(long revision) {
        return new CapabilityProjectionContext(revision, "tenant-a", "org-a", "support-platform",
                "test", "sg", "MIRROR_REHEARSAL",
                new CapabilitySnapshot.Ownership("owner-a", "support-platform", "pager-a"),
                CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("sg", "us"), true,
                "", null, Instant.parse("2027-07-22T00:00:00Z"), CREATED_AT);
    }
}
