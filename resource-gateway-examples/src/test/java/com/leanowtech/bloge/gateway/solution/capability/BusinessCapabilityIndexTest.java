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
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
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
