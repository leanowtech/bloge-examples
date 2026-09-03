package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visualadapter.ResourceRegistryVisualAdapter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the resource-declaration bridge from a sandbox descriptor into Tool composition. */
class AgentTddResourceDeclarationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void declaresAReadOnlyResourceAndMakesItsContractBindable() {
        MemoryResourceRegistry resources = new MemoryResourceRegistry();
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        AgentTddResourceDeclarationService declarations = new AgentTddResourceDeclarationService(
                resources, contracts, states, new AgentTddEgressHostPolicy("sandbox.example.test"), mapper);

        Map<String, Object> declared = declarations.declare(mapper.valueToTree(Map.of(
                "resourceId", "shipping-service.quote", "method", "GET",
                "urlTemplate", "https://sandbox.example.test/quotes/{orderId}",
                "payloadSchema", Map.of("type", "object", "properties", Map.of(
                        "fee", Map.of("type", "number")), "required", List.of("fee"),
                        "additionalProperties", false),
                "idempotencyKey", "declare-shipping-1")), identity());

        assertThat(declared).containsEntry("resourceId", "shipping-service.quote")
                .containsEntry("registered", true).containsEntry("host", "sandbox.example.test");
        assertThat(resources.resolve("shipping-service.quote").parameterMapping().pathExpressions())
                .containsEntry("orderId", "ctx.params.orderId");
        assertThat(contracts.findByResourceId("shipping-service.quote")).isPresent();

        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        var catalog = new DefaultVisualOperatorCatalog(new ResourceRegistryVisualAdapter(resources), contracts,
                new ResourceVirtualOperatorProjector(), libraries, null);
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        AgentTddMutationService mutations = new AgentTddMutationService(
                libraries, drafts, states,
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()), libraries, mapper),
                projection, mapper);
        mutations.upsertLibrary(mapper.valueToTree(Map.of(
                "libraryYaml", boundLibraryYaml(), "idempotencyKey", "library-shipping-1")), identity());

        Map<String, Object> composed = mutations.compose(mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "graph", Map.of("dsl", shippingDsl()),
                "libraryRefs", List.of("shipping"), "idempotencyKey", "compose-shipping-1")),
                "toolRef", "TOOL", identity());

        assertThat(composed).containsEntry("speccing", false).containsEntry("executable", true);
    }

    @Test
    void rejectsUnsafeTargetsWritesAndIdempotencyDriftBeforeRegistration() {
        MemoryResourceRegistry resources = new MemoryResourceRegistry();
        AgentTddResourceDeclarationService declarations = new AgentTddResourceDeclarationService(
                resources, new InMemoryResourceDesignContractRegistry(),
                new InMemoryAgentTddStateRepository(),
                new AgentTddEgressHostPolicy("sandbox.example.test"), mapper);

        assertThatThrownBy(() -> declarations.declare(request(
                "https://attacker.invalid/quotes", "GET", "declare-host"), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("EGRESS_NOT_ALLOWED"));
        assertThatThrownBy(() -> declarations.declare(request(
                "https://sandbox.example.test/quotes", "POST", "declare-write"), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("WRITE_EFFECT_NOT_ALLOWED"));

        declarations.declare(request(
                "https://sandbox.example.test/quotes", "GET", "same-key"), identity());
        assertThatThrownBy(() -> declarations.declare(mapper.valueToTree(Map.of(
                "resourceId", "other-service.quote", "method", "GET",
                "urlTemplate", "https://sandbox.example.test/other",
                "payloadSchema", schema(), "idempotencyKey", "same-key")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(resources.all()).hasSize(1);
    }

    @Test
    void reportsThatABoundResourceMustBeDeclaredBeforeComposition() {
        MemoryResourceRegistry resources = new MemoryResourceRegistry();
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        var catalog = new DefaultVisualOperatorCatalog(new ResourceRegistryVisualAdapter(resources), contracts,
                new ResourceVirtualOperatorProjector(), libraries, null);
        AgentTddMutationService mutations = new AgentTddMutationService(
                libraries, drafts, states,
                new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()), libraries, mapper),
                new DslImportService(catalog, new OperatorLibraryValidator()), mapper);
        mutations.upsertLibrary(mapper.valueToTree(Map.of(
                "libraryYaml", boundLibraryYaml(), "idempotencyKey", "library-shipping-missing")), identity());

        assertThatThrownBy(() -> mutations.compose(mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "graph", Map.of("dsl", shippingDsl()),
                "libraryRefs", List.of("shipping"), "idempotencyKey", "compose-shipping-missing")),
                "toolRef", "TOOL", identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("RESOURCE_NOT_REGISTERED"));
    }

    @Test
    void dispatchesResourceDeclarationThroughTheAgentMcpFacade() {
        MemoryResourceRegistry resources = new MemoryResourceRegistry();
        AgentTddResourceDeclarationService declarations = new AgentTddResourceDeclarationService(
                resources, new InMemoryResourceDesignContractRegistry(),
                new InMemoryAgentTddStateRepository(),
                new AgentTddEgressHostPolicy("sandbox.example.test"), mapper);
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                new InMemoryOperatorLibraryRegistry(), new InMemoryGraphDraftRepository(), mapper,
                null, null, null, null, null, null, declarations);

        var response = mapper.valueToTree(tools.invoke("rg.resource.declare", request(
                "https://sandbox.example.test/quotes", "GET", "mcp-resource-declare"), identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.at("/data/resourceId").asText()).isEqualTo("shipping-service.quote");
        assertThat(resources.contains("shipping-service.quote")).isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode request(String url, String method, String key) {
        return mapper.valueToTree(Map.of(
                "resourceId", "shipping-service.quote", "method", method,
                "urlTemplate", url, "payloadSchema", schema(), "idempotencyKey", key));
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of(
                "fee", Map.of("type", "number")), "required", List.of("fee"),
                "additionalProperties", false);
    }

    private static String boundLibraryYaml() {
        return """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: { id: shipping, name: Shipping, version: 0.1.0, owner: logistics }
                defaults: { operatorVersion: 0.1.0, namespace: shipping }
                types:
                  QuoteParams:
                    fields: { orderId: string }
                  QuotePayload:
                    fields: { fee: number }
                operators:
                  shipping:quote:
                    name: Quote
                    archetype: resource-read
                    requiresSecrets: false
                    input: { params: QuoteParams }
                    output: { payload: QuotePayload }
                    runtime: { bindingRef: "resource:shipping-service.quote" }
                """;
    }

    private static String shippingDsl() {
        return """
                graph shippingQuote {
                  input { orderId: String }
                  output { fee: Decimal }
                  node quote : "shipping:quote" { input { params = { orderId: ctx.orderId } } }
                  transform response { fee = quote.output.payload.fee }
                }
                """;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_AUTHORING", "corr-1");
    }

    private static final class MemoryResourceRegistry implements WritableResourceRegistry {
        private final Map<String, ResourceDescriptor> values = new LinkedHashMap<>();

        @Override
        public void register(ResourceDescriptor descriptor) {
            if (values.putIfAbsent(descriptor.resourceId(), descriptor) != null) {
                throw new IllegalArgumentException("duplicate");
            }
        }

        @Override
        public void update(ResourceDescriptor descriptor) {
            if (!values.containsKey(descriptor.resourceId())) throw new IllegalArgumentException("missing");
            values.put(descriptor.resourceId(), descriptor);
        }

        @Override
        public void deregister(String resourceId) {
            values.remove(resourceId);
        }

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            ResourceDescriptor descriptor = values.get(resourceId);
            if (descriptor == null) throw new IllegalArgumentException("missing");
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return values.containsKey(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.copyOf(values.values());
        }
    }
}
