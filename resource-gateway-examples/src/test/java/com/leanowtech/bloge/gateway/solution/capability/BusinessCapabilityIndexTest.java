package com.leanowtech.bloge.gateway.solution.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies stable, scoped and payload-free capability discovery across sessions. */
class BusinessCapabilityIndexTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listsAndGetsBusinessContractsWithoutImplementationBindings() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveFeature(states, scope(identity("project-a")), "feature:cancel.party", 0);
        saveFeature(states, scope(identity("project-b")), "feature:other", 0);
        BusinessCapabilityIndex index = index(states);

        JsonNode list = mapper.valueToTree(index.list(mapper.valueToTree(Map.of(
                "entityKinds", List.of("FEATURE"), "limit", 10)), identity("project-a")));
        assertThat(list.path("entities")).hasSize(1);
        assertThat(list.at("/entities/0/assetRef").asText()).isEqualTo("feature:cancel.party");
        assertThat(list.toString()).doesNotContain("evaluationRef", "componentRef", "secret", "urlTemplate");

        JsonNode detail = mapper.valueToTree(index.get(mapper.valueToTree(Map.of(
                "assetRef", "feature:cancel.party")), identity("project-a")));
        assertThat(detail.at("/businessContract/evaluationKind").asText()).isEqualTo("API");
        assertThat(detail.toString()).doesNotContain("resource:private", "evaluationRef", "componentRef");
        assertThat(detail.at("/card/source/implementationVisible").asBoolean()).isFalse();
    }

    @Test
    void invalidatesAContinuationCursorWhenAnySourceRevisionChanges() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveFeature(states, scope(identity("project-a")), "feature:a", 0);
        saveFeature(states, scope(identity("project-a")), "feature:b", 0);
        BusinessCapabilityIndex index = index(states);
        JsonNode args = mapper.valueToTree(Map.of("entityKinds", List.of("FEATURE"), "limit", 1));
        JsonNode first = mapper.valueToTree(index.list(args, identity("project-a")));
        assertThat(first.path("nextCursor").asText()).isNotBlank();

        saveFeature(states, scope(identity("project-a")), "feature:c", 0);
        ObjectNode continuation = (ObjectNode) args.deepCopy();
        continuation.put("cursor", first.path("nextCursor").asText());

        assertThatThrownBy(() -> index.list(continuation, identity("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_CONTEXT_STALE"));
    }

    @Test
    void naturalLanguageRecallRemainsIncompleteUntilSemanticMatchingRuns() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveFeature(states, scope(identity("project-a")), "feature:cancel.party", 0);

        JsonNode result = mapper.valueToTree(index(states).search(mapper.valueToTree(Map.of(
                "query", Map.of("intent", "取消责任"), "assetKinds", List.of("FEATURE"), "limit", 10)),
                identity("project-a")));

        assertThat(result.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.at("/candidates/0/matchType").asText()).isEqualTo("PARTIAL");
        assertThat(result.at("/candidates/0/reuseAllowed").asBoolean()).isFalse();
        assertThat(result.at("/clarification/required").asBoolean()).isTrue();
    }

    @Test
    void ranksAllMatchesBeforeApplyingTheResponseLimit() throws Exception {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveFeature(states, scope(identity("project-a")), "feature:a-partial", 0);
        saveSemanticFeature(states, "feature:z-exact", contract());

        JsonNode result = mapper.valueToTree(index(states).search(mapper.valueToTree(Map.of(
                "query", contract(), "assetKinds", List.of("FEATURE"), "limit", 1)),
                identity("project-a")));

        assertThat(result.path("status").asText()).isEqualTo("EXACT");
        assertThat(result.at("/candidates/0/assetRef").asText()).isEqualTo("feature:z-exact");
        assertThat(result.at("/candidates/0/matchType").asText()).isEqualTo("EXACT");
    }

    @Test
    void reportsAmbiguityEvenWhenTheResponseLimitReturnsOneExactCandidate() throws Exception {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveSemanticFeature(states, "feature:a-exact", contract());
        saveSemanticFeature(states, "feature:b-exact", contract());

        JsonNode result = mapper.valueToTree(index(states).search(mapper.valueToTree(Map.of(
                "query", contract(), "assetKinds", List.of("FEATURE"), "limit", 1)),
                identity("project-a")));

        assertThat(result.path("status").asText()).isEqualTo("AMBIGUOUS");
        assertThat(result.path("candidates")).hasSize(1);
        assertThat(result.at("/clarification/required").asBoolean()).isTrue();
    }

    @Test
    void failsClosedWhenTheSourceWindowNeverStabilizes() {
        AgentTddStateRepository changing = mock(AgentTddStateRepository.class);
        AtomicLong revision = new AtomicLong();
        when(changing.list(any(), any())).thenAnswer(invocation -> {
            if (!SolutionEntityRegistry.FEATURE.equals(invocation.getArgument(1))) return List.of();
            long current = revision.incrementAndGet();
            return List.of(asset(scope(identity("project-a")), "feature:changing", current));
        });
        BusinessCapabilityIndex index = index(changing);

        assertThatThrownBy(() -> index.freeze(identity("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("CAPABILITY_INDEX_UNSTABLE");
                            assertThat(failure.retryable()).isTrue();
                        });
    }

    @Test
    void freezesFiveScopedSourcesWithDeterministicDedupeAndSort() {
        IntegrationRequestContext identity = identity("project-a");
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        saveFeature(states, scope(identity), "feature:cancel.party", 0);
        saveEntity(states, scope(identity), SolutionEntityRegistry.SOLUTION,
                "solution:cancel", "SOLUTION");
        saveFeature(states, scope(identity("project-b")), "feature:other-scope", 0);

        OperatorDefinition libraryOperator = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition runtimeOperator = VisualCatalogTestSupport.scoreFactsOperator();
        OperatorDefinition outOfScope = new OperatorDefinition(
                runtimeOperator.schemaVersion(), "risk:other-scope", runtimeOperator.operatorVersion(),
                runtimeOperator.display(), runtimeOperator.source(), runtimeOperator.ports(),
                runtimeOperator.configSchema(), runtimeOperator.capabilities(),
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("project-b"), List.of("test")),
                runtimeOperator.lowering(), runtimeOperator.diagnostics());
        OperatorLibrary library = new OperatorLibrary("", "risk-policy", "Risk", "1", "risk-team",
                "ACTIVE", List.of(libraryOperator, outOfScope));
        OperatorLibraryRegistry libraries = mock(OperatorLibraryRegistry.class);
        when(libraries.all()).thenReturn(List.of(library));
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        when(catalog.list(any())).thenReturn(List.of(runtimeOperator, libraryOperator, outOfScope));

        GraphDraft draft = draft("draft:cancel", "tenant-a", "project-a", "test");
        GraphDraft otherDraft = draft("draft:other", "tenant-a", "project-b", "test");
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        when(drafts.all()).thenReturn(List.of(otherDraft, draft));
        VisualGraphPublication publication = publication("publication:cancel", draft);
        VisualGraphPublication otherPublication = publication("publication:other", otherDraft);
        VisualGraphPublicationRepository publications = mock(VisualGraphPublicationRepository.class);
        when(publications.all()).thenReturn(List.of(otherPublication, publication));

        BusinessCapabilityIndex index = new BusinessCapabilityIndex(
                states, libraries, catalog, drafts, publications, mapper);
        BusinessCapabilityIndex.Snapshot first = index.freeze(identity);
        BusinessCapabilityIndex.Snapshot second = index.freeze(identity);

        assertThat(first.catalogRevisionVector().keySet()).containsExactlyInAnyOrder(
                "solutionEntities", "operatorLibraries", "runtimeCatalog", "graphDrafts", "publications");
        assertThat(first.capabilities()).extracting(BusinessCapabilityIndex.Card::assetRef)
                .containsExactly("feature:cancel.party", "risk:eligibility", "risk:scoreFacts",
                        "publication:cancel", "solution:cancel", "draft:cancel");
        BusinessCapabilityIndex.Card deduplicated = first.capabilities().stream()
                .filter(card -> card.assetRef().equals("risk:eligibility"))
                .findFirst().orElseThrow();
        assertThat(deduplicated.source().registry()).isEqualTo("OPERATOR_LIBRARY");
        assertThat(first.capabilities()).extracting(BusinessCapabilityIndex.Card::assetRef)
                .doesNotContain("feature:other-scope", "risk:other-scope", "draft:other", "publication:other");
        assertThat(second.snapshotFingerprint()).isEqualTo(first.snapshotFingerprint());
    }

    private BusinessCapabilityIndex index(AgentTddStateRepository states) {
        OperatorLibraryRegistry libraries = mock(OperatorLibraryRegistry.class);
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualGraphPublicationRepository publications = mock(VisualGraphPublicationRepository.class);
        when(libraries.all()).thenReturn(List.of());
        when(catalog.list(any())).thenReturn(List.of());
        when(drafts.all()).thenReturn(List.of());
        when(publications.all()).thenReturn(List.of());
        return new BusinessCapabilityIndex(states, libraries, catalog, drafts, publications, mapper);
    }

    private void saveFeature(InMemoryAgentTddStateRepository states, String scope, String ref, long ignored) {
        AgentTddStoredAsset value = asset(scope, ref, 1);
        states.save(scope, SolutionEntityRegistry.FEATURE, ref, value.data());
    }

    private void saveEntity(InMemoryAgentTddStateRepository states, String scope, String kind,
                            String ref, String entityKind) {
        ObjectNode contract = mapper.createObjectNode();
        contract.putObject("businessSemantics").put("businessName", ref);
        ObjectNode data = mapper.createObjectNode();
        data.put("entityKind", entityKind);
        data.set("contract", contract);
        data.put("contractFingerprint", "sha256:" + "b".repeat(64));
        data.put("speccing", false);
        states.save(scope, kind, ref, data);
    }

    private static GraphDraft draft(String draftId, String tenant, String project, String environment) {
        return new GraphDraft("", draftId, 1, draftId.replace(':', '_'), tenant, project, environment,
                "DRAFT", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of("assetKind", "TOOL"),
                GraphDraft.OutputSelection.empty());
    }

    private static VisualGraphPublication publication(String publicationId, GraphDraft draft) {
        return new VisualGraphPublication("", publicationId, draft.draftId(), draft.revision(),
                draft.graphName(), draft.tenantId(), draft.namespace(), draft.environment(), Instant.EPOCH,
                draft, List.of(), Map.of(), Map.of(), "",
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "", List.of()));
    }

    private void saveSemanticFeature(InMemoryAgentTddStateRepository states, String ref,
                                     JsonNode businessDefinition) {
        ObjectNode semantics = mapper.createObjectNode();
        semantics.put("businessName", "取消责任方");
        semantics.put("description", "判断订单取消责任");
        ObjectNode contract = mapper.createObjectNode();
        contract.set("businessSemantics", semantics);
        ObjectNode current = businessDefinition.deepCopy();
        current.put("lifecycle", "ACTIVE");
        contract.set("businessDefinition", current);
        contract.put("evaluationKind", "API");
        contract.putObject("output").put("type", "string");
        ObjectNode data = mapper.createObjectNode();
        data.put("entityKind", "FEATURE");
        data.set("contract", contract);
        data.put("contractFingerprint", "sha256:" + "a".repeat(64));
        data.put("speccing", false);
        states.save(scope(identity("project-a")), SolutionEntityRegistry.FEATURE, ref, data);
    }

    private JsonNode contract() throws Exception {
        return mapper.readTree("""
                {
                  "schemaVersion":"rg.businessFactSemanticContract.v1",
                  "semanticKey":"ride.cancel.party",
                  "intent":"判断取消责任",
                  "domain":"ride-cancellation",
                  "businessObject":"ride-order",
                  "requiredContext":[],
                  "resultDomain":{"type":"enum","values":["PASSENGER","DRIVER","UNKNOWN"]},
                  "asOf":"CANCELLATION_OCCURRED_AT",
                  "unknownPolicy":"REQUIRE_HUMAN_REVIEW",
                  "acquisitionOwner":"PLATFORM",
                  "authoritySource":"responsibility-center",
                  "freshness":{"mode":"AS_OF_EVENT"},
                  "effect":"READ"
                }
                """);
    }

    private AgentTddStoredAsset asset(String scope, String ref, long revision) {
        ObjectNode semantics = mapper.createObjectNode();
        semantics.put("businessName", "取消责任方");
        semantics.put("description", "判断订单取消责任");
        semantics.putArray("aliases").add("取消归责");
        ObjectNode contract = mapper.createObjectNode();
        contract.set("businessSemantics", semantics);
        contract.put("evaluationKind", "API");
        contract.put("evaluationRef", "resource:private");
        contract.put("componentRef", "secret-component");
        contract.putObject("output").put("type", "string");
        ObjectNode data = mapper.createObjectNode();
        data.put("entityKind", "FEATURE");
        data.set("contract", contract);
        data.put("contractFingerprint", "sha256:" + ref.replace(':', '-'));
        data.put("speccing", false);
        return new AgentTddStoredAsset(scope, SolutionEntityRegistry.FEATURE, ref, revision,
                "sha256:stored-" + revision, data, Instant.EPOCH);
    }

    private static IntegrationRequestContext identity(String project) {
        return new IntegrationRequestContext("tenant-a", "org-a", project, "test", "sg",
                "WORKLOAD", "agent-1", "", "AGENT_TDD_READ", "corr-1");
    }

    private static String scope(IntegrationRequestContext identity) {
        return String.join("|", identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
