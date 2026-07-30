package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for user-provided operator library admin APIs.
 */
class OperatorLibraryAdminControllerTest {

    private InMemoryOperatorLibraryRegistry registry;
    private InMemoryGraphDraftRepository drafts;
    private InMemoryVisualGraphPublicationRepository publications;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new InMemoryOperatorLibraryRegistry();
        drafts = new InMemoryGraphDraftRepository();
        publications = new InMemoryVisualGraphPublicationRepository();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OperatorLibraryAdminController controller = new OperatorLibraryAdminController(
                registry,
                new OperatorLibraryValidator(),
                drafts,
                publications
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void validateLibraryReturnsDiagnosticsWithoutStoring() throws Exception {
        OperatorLibrary invalid = invalidArrayLibrary();

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"))
                .andExpect(jsonPath("$.profile.schemaVersion").value(OperatorLibraryProfile.SCHEMA_VERSION))
                .andExpect(jsonPath("$.profile.operatorCount").value(1))
                .andExpect(jsonPath("$.profile.catalogRepairOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.facets.runtimeReadinessStates['catalog-repair-required']")
                        .value(1))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessState")
                        .value("catalog-repair-required"))
                .andExpect(jsonPath("$.importReadiness.schemaVersion")
                        .value(OperatorLibraryImportReadiness.SCHEMA_VERSION))
                .andExpect(jsonPath("$.importReadiness.state").value("catalog-repair-required"))
                .andExpect(jsonPath("$.importReadiness.level").value("error"))
                .andExpect(jsonPath("$.importReadiness.importableNow").value(false))
                .andExpect(jsonPath("$.importReadiness.importableAfterReview").value(false))
                .andExpect(jsonPath("$.importReadiness.blockingCodes[0]")
                        .value("visual.schema.arrayItemsMissing"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateLibraryReturnsServerDerivedProfileWithoutStoring() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty())
                .andExpect(jsonPath("$.profile.schemaVersion").value(OperatorLibraryProfile.SCHEMA_VERSION))
                .andExpect(jsonPath("$.profile.librarySchemaVersion").value("bloge.visualOperatorLibrary.v1"))
                .andExpect(jsonPath("$.profile.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.profile.operatorCount").value(1))
                .andExpect(jsonPath("$.profile.inputPortCount").value(1))
                .andExpect(jsonPath("$.profile.outputPortCount").value(1))
                .andExpect(jsonPath("$.profile.inputFieldCount").value(2))
                .andExpect(jsonPath("$.profile.outputFieldCount").value(2))
                .andExpect(jsonPath("$.profile.requiredInputCount").value(2))
                .andExpect(jsonPath("$.profile.designOnlyOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.runtimeExecutableOperatorCount").value(0))
                .andExpect(jsonPath("$.profile.facets.runtimeReadinessStates['design-only']").value(1))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessState").value("design-only"))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessTitle")
                        .value("Design-only operator"))
                .andExpect(jsonPath("$.profile.operators[0].inputFields[0].path").value("score"))
                .andExpect(jsonPath("$.profile.operators[0].inputFields[0].required").value(true))
                .andExpect(jsonPath("$.profile.operators[0].outputFields[0].path").value("eligible"))
                .andExpect(jsonPath("$.importReadiness.schemaVersion")
                        .value(OperatorLibraryImportReadiness.SCHEMA_VERSION))
                .andExpect(jsonPath("$.importReadiness.state").value("design-only-importable"))
                .andExpect(jsonPath("$.importReadiness.level").value("info"))
                .andExpect(jsonPath("$.importReadiness.valid").value(true))
                .andExpect(jsonPath("$.importReadiness.importableNow").value(true))
                .andExpect(jsonPath("$.importReadiness.designOnlyOperatorCount").value(1))
                .andExpect(jsonPath("$.importReadiness.message")
                        .value("Schema-only library is ready for design-time authoring and DESIGN publications."))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirementCount").value(1))
                .andExpect(jsonPath("$.importReadiness.bindingKindCounts['executable-lowering']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffLaneCounts['operator-platform']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffKindCounts['operator-implementation']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffTargetCounts['risk:eligibility']").value(1))
                .andExpect(jsonPath("$.importReadiness.sourceKindCounts['user-library']").value(1))
                .andExpect(jsonPath("$.importReadiness.operatorLibraryIdCounts['risk-policy-design']").value(1))
                .andExpect(jsonPath("$.importReadiness.loweringModeCounts['design']").value(1))
                .andExpect(jsonPath("$.importReadiness.readinessStateCounts['design-only']").value(1))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirementKeys[0]")
                        .value("RUNTIME_BINDING|operator-library|risk-policy-design|risk:eligibility|executable-lowering|risk:eligibility|"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].groupKey")
                        .value("RUNTIME_BINDING_GROUP|operator-library|risk-policy-design|operator-platform|operator-implementation|risk:eligibility|executable-lowering"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].operatorLibraryId")
                        .value("risk-policy-design"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffLane")
                        .value("operator-platform"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffKind")
                        .value("operator-implementation"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffTarget")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].bindingKind")
                        .value("executable-lowering"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].requirementCount")
                        .value(1))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].operatorRefs[0]")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].requirementKeys[0]")
                        .value("RUNTIME_BINDING|operator-library|risk-policy-design|risk:eligibility|executable-lowering|risk:eligibility|"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].requirementKey")
                        .value("RUNTIME_BINDING|operator-library|risk-policy-design|risk:eligibility|executable-lowering|risk:eligibility|"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].operatorRef")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].operatorLibraryId")
                        .value("risk-policy-design"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].label")
                        .value("Eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].bindingKind")
                        .value("executable-lowering"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].bindingTarget")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffLane")
                        .value("operator-platform"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffKind")
                        .value("operator-implementation"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffTarget")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].recommendedAction")
                        .value("Bind a native/resource/subgraph lowering before using this operator in EXECUTABLE graphs."));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void importReadinessGroupsRuntimeBindingHandoffBatches() {
        OperatorLibrary library = VisualCatalogTestSupport.externalBoundaryLibrary();
        OperatorLibraryProfile profile = OperatorLibraryProfile.from(library);

        OperatorLibraryImportReadiness readiness = OperatorLibraryImportReadiness.from(
                true,
                List.of(),
                OperatorLibraryImpactReview.empty(),
                profile,
                library);

        assertThat(readiness.runtimeBindingRequirementCount()).isEqualTo(3);
        assertThat(readiness.runtimeBindingHandoffGroups()).hasSize(3);
        assertThat(readiness.runtimeBindingHandoffGroups())
                .extracting(OperatorLibraryImportReadiness.RuntimeBindingHandoffGroup::groupKey)
                .containsExactly(
                        "RUNTIME_BINDING_GROUP|operator-library|external-boundaries|event-runtime|event-subscription|order.submitted|event-source-runtime",
                        "RUNTIME_BINDING_GROUP|operator-library|external-boundaries|messaging-runtime|message-consumer|risk.commands|message-runtime",
                        "RUNTIME_BINDING_GROUP|operator-library|external-boundaries|ingress-runtime|webhook-ingress|POST /webhooks/credit-decision|webhook-ingress-runtime");
        OperatorLibraryImportReadiness.RuntimeBindingHandoffGroup eventGroup =
                readiness.runtimeBindingHandoffGroups().getFirst();
        assertThat(eventGroup.requirementCount()).isEqualTo(1);
        assertThat(eventGroup.operatorRefs()).containsExactly("event:orderSubmitted");
        assertThat(eventGroup.requirementKeys()).containsExactly(
                "RUNTIME_BINDING|operator-library|external-boundaries|event:orderSubmitted|event-source-runtime|order.submitted|");
        assertThat(eventGroup.recommendedAction())
                .isEqualTo("Bind event subscription for this event type before EXECUTABLE graph publication.");
    }

    @Test
    void validateLibraryTextAcceptsJsonAndYamlWithoutStoring() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        String json = objectMapper.writeValueAsString(library);
        String yaml = new YAMLMapper().writeValueAsString(library);

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.profile.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.profile.facets.runtimeReadinessStates['design-only']").value(1));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(yaml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.profile.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.profile.facets.runtimeReadinessStates['design-only']").value(1));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validatesAndImportsFunctionOnlyLibrary() throws Exception {
        OperatorLibrary library = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "integer")
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.profile.operatorCount").value(0))
                .andExpect(jsonPath("$.diagnostics.length()").value(0));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("risk-functions"))
                .andExpect(jsonPath("$.operators.length()").value(0))
                .andExpect(jsonPath("$.builtInFunctions[0].name").value("risk.normalize"));

        assertThat(registry.find("risk-functions")).contains(library);
    }

    @Test
    void rejectsCallableContractThatConflictsWithDefaultCatalog() throws Exception {
        OperatorLibrary library = functionLibrary(
                "custom-coalesce",
                function("coalesce", "custom", "string")
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.functionCallableConflict"))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/builtInFunctions/0/name"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.callableName").value("coalesce"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.existingOwner").value("builtin"));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.functionCallableConflict"));

        assertThat(registry.find("custom-coalesce")).isEmpty();
    }

    @Test
    void capabilityCatalogProjectionRejectsDuplicateCallableAcrossNamespaces() throws Exception {
        String capabilityCatalog = """
                schemaVersion: bloge.capabilityCatalog.v1
                catalogId: duplicate-functions
                displayName: Duplicate functions
                blogeVersion: 1.0.0
                functions:
                  - name: risk.normalize
                    namespace: risk
                    signatures:
                      - label: risk.normalize(value)
                        parameters:
                          - name: value
                            type: integer
                        returns:
                          type: integer
                  - name: risk.normalize
                    namespace: shared
                    signatures:
                      - label: risk.normalize(value)
                        parameters:
                          - name: value
                            type: integer
                        returns:
                          type: string
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries/from-capability-catalog-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(capabilityCatalog))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.capabilityCatalog.function.duplicate"))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.library.builtInFunctions.length()").value(2));
    }

    @Test
    void enterpriseDocumentationExampleRemainsValidAndImportableWithFunctions() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "examples",
                "enterprise-knowledge-governance-operator-library.yaml"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(yaml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.profile.libraryId").value("enterprise-knowledge-governance"))
                .andExpect(jsonPath("$.profile.operatorCount").value(9))
                .andExpect(jsonPath("$.profile.facets.loweringModes.length()").value(9))
                .andExpect(jsonPath("$.importReadiness.valid").value(true))
                .andExpect(jsonPath("$.importReadiness.errorCount").value(0))
                .andExpect(jsonPath("$.importReadiness.requiresAckWarnings").value(true))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirementCount").isNumber());

        mockMvc.perform(post("/admin/visual-operator-libraries/import-text")
                        .param("ackWarnings", "true")
                        .param("actor", "docs-example")
                        .param("changeSource", "documentation")
                        .param("changeSummary", "validate complete example")
                        .param("reason", "runtime bindings reviewed")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("enterprise-knowledge-governance"))
                .andExpect(jsonPath("$.operators.length()").value(9))
                .andExpect(jsonPath("$.builtInFunctions.length()").value(6))
                .andExpect(jsonPath("$.builtInFunctions[0].name").value("knowledge.normalizeText"));

        assertThat(registry.find("enterprise-knowledge-governance")).isPresent();
    }

    @Test
    void importLibraryTextStoresYamlThroughGovernedRegistryPath() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        String yaml = new YAMLMapper().writeValueAsString(library);

        mockMvc.perform(post("/admin/visual-operator-libraries/import-text")
                        .param("actor", "visual-author")
                        .param("changeSource", "gateway-browser")
                        .param("changeSummary", "Imported YAML operator library.")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("risk-policy-design"));

        assertThat(registry.find("risk-policy-design")).isPresent();
        OperatorLibraryRevision revision = registry.revisions("risk-policy-design").getFirst();
        assertThat(revision.action()).isEqualTo(OperatorLibraryRevision.ACTION_CREATE);
        assertThat(revision.revisionMetadata().actor()).isEqualTo("visual-author");
        assertThat(revision.revisionMetadata().changeSource()).isEqualTo("gateway-browser");
        assertThat(revision.revisionMetadata().changeSummary()).isEqualTo("Imported YAML operator library.");
    }

    @Test
    void fromCapabilityCatalogProjectsVisualLibraryDraftWithoutStoring() throws Exception {
        String capabilityCatalog = """
                schemaVersion: bloge.capabilityCatalog.v1
                catalogId: risk-capabilities
                displayName: Risk Capabilities
                blogeVersion: 1.2.3
                generatedAt: 2026-07-07T09:00:00Z
                operators:
                  - operatorRef: risk:eligibility
                    operatorVersion: 1.0.0
                    display:
                      name: Eligibility
                      description: Checks applicant facts.
                      tags: [risk, decision]
                    implementation:
                      kind: java-operator
                      className: com.acme.RiskEligibilityOperator
                      inputType: com.acme.Applicant
                      outputType: com.acme.Decision
                    ports:
                      inputs:
                        - name: applicant
                          required: true
                          schema:
                            format: json-schema
                            version: 2020-12
                            schema:
                              type: object
                              properties:
                                score:
                                  type: integer
                              required: [score]
                              additionalProperties: false
                      outputs:
                        - name: decision
                          schema:
                            format: json-schema
                            version: 2020-12
                            schema:
                              type: object
                              properties:
                                eligible:
                                  type: boolean
                              required: [eligible]
                              additionalProperties: false
                    capabilities:
                      idempotency: IDEMPOTENT
                      sideEffectType: READ_ONLY
                      deterministic: true
                functions:
                  - name: normalizeScore
                    namespace: risk
                    displayName: Normalize score
                    description: Normalizes bureau scores.
                    category: risk
                    signatures:
                      - label: normalizeScore(score)
                        parameters:
                          - name: score
                            type: Integer
                        returns:
                          type: Boolean
                    examples:
                      - risk.normalizeScore(inputs.score)
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries/from-capability-catalog-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(capabilityCatalog))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(CapabilityCatalogVisualAdapterResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.importReadiness.state").value("design-only-importable"))
                .andExpect(jsonPath("$.library.schemaVersion").value("bloge.visualOperatorLibrary.v1"))
                .andExpect(jsonPath("$.library.libraryId").value("risk-capabilities"))
                .andExpect(jsonPath("$.library.builtInFunctions[0].name").value("normalizeScore"))
                .andExpect(jsonPath("$.library.builtInFunctions[0].signatures[0].parameters[0].type")
                        .value("integer"))
                .andExpect(jsonPath("$.library.builtInFunctions[0].signatures[0].returns.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.library.operators[0].operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.library.operators[0].source.kind").value("user-library"))
                .andExpect(jsonPath("$.library.operators[0].lowering.mode").value("design"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.bindingTarget")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.capabilityCatalog.className")
                        .value("com.acme.RiskEligibilityOperator"))
                .andExpect(jsonPath("$.library.operators[0].capabilities.effect").value("READ_EXTERNAL"))
                .andExpect(jsonPath("$.library.operators[0].capabilities.idempotency").value("IDEMPOTENT"))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("FULL"))
                .andExpect(jsonPath("$.projectionReview.sourceOperatorCount").value(1))
                .andExpect(jsonPath("$.projectionReview.projectedFunctionCount").value(1));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiProjectsExternalBoundaryOperatorsWithoutStoring() throws Exception {
        String asyncApi = """
                asyncapi: '2.6.0'
                info:
                  title: Risk Events
                  version: 1.2.3
                  contact:
                    name: risk-platform
                channels:
                  /webhooks/credit-decision:
                    subscribe:
                      operationId: creditDecisionWebhook
                      x-bloge-source-kind: webhook
                      bindings:
                        http:
                          method: post
                      message:
                        name: CreditDecision
                        payload:
                          type: object
                          properties:
                            applicationId:
                              type: string
                            decision:
                              type: string
                          required:
                            - applicationId
                            - decision
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        payload:
                          $ref: '#/components/schemas/RiskCommand'
                components:
                  schemas:
                    RiskCommand:
                      type: object
                      properties:
                        commandId:
                          type: string
                        score:
                          type: integer
                      required:
                        - commandId
                """;
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                Map.of(),
                asyncApi
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(AsyncApiOperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.library.libraryId").value("risk-events-operators"))
                .andExpect(jsonPath("$.library.displayName").value("Risk Events operators"))
                .andExpect(jsonPath("$.library.version").value("1.2.3"))
                .andExpect(jsonPath("$.library.owner").value("risk-platform"))
                .andExpect(jsonPath("$.library.operators.length()").value(2))
                .andExpect(jsonPath("$.library.operators[0].source.kind").value("webhook"))
                .andExpect(jsonPath("$.library.operators[0].source.method").value("POST"))
                .andExpect(jsonPath("$.library.operators[0].source.urlTemplate")
                        .value("/webhooks/credit-decision"))
                .andExpect(jsonPath("$.library.operators[0].ports.outputs[0].name")
                        .value("request"))
                .andExpect(jsonPath("$.library.operators[0].lowering.mode").value("webhook"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.method")
                        .value("POST"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.path")
                        .value("/webhooks/credit-decision"))
                .andExpect(jsonPath("$.library.operators[1].source.kind").value("message-handler"))
                .andExpect(jsonPath("$.library.operators[1].ports.inputs[0].name")
                        .value("message"))
                .andExpect(jsonPath("$.library.operators[1].ports.outputs[0].name")
                        .value("ack"))
                .andExpect(jsonPath("$.library.operators[1].lowering.mode").value("message-handler"))
                .andExpect(jsonPath("$.library.operators[1].lowering.parameters.channel")
                        .value("risk.commands"))
                .andExpect(jsonPath("$.selectionApplied").value(false))
                .andExpect(jsonPath("$.availableOperations.length()").value(2))
                .andExpect(jsonPath("$.selectedOperations.length()").value(2))
                .andExpect(jsonPath("$.omittedOperationCount").value(0))
                .andExpect(jsonPath("$.projectionReview.schemaVersion")
                        .value(AsyncApiProjectionReview.SCHEMA_VERSION))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("FULL"))
                .andExpect(jsonPath("$.projectionReview.availableOperationCount").value(2))
                .andExpect(jsonPath("$.projectionReview.selectedOperationCount").value(2))
                .andExpect(jsonPath("$.projectionReview.omittedOperationCount").value(0))
                .andExpect(jsonPath("$.projectionReview.unmatchedSelectionCount").value(0))
                .andExpect(jsonPath("$.projectionReview.availableProjectionLevelCounts.READY").value(2))
                .andExpect(jsonPath("$.projectionReview.selectedSourceKindCounts.webhook").value(1))
                .andExpect(jsonPath("$.projectionReview.selectedSourceKindCounts['message-handler']").value(1))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.diagnostics").isEmpty())
                .andExpect(jsonPath("$.validation.profile.libraryId")
                        .value("risk-events-operators"))
                .andExpect(jsonPath("$.validation.profile.runtimeBlockedOperatorCount").value(2))
                .andExpect(jsonPath("$.validation.profile.facets.runtimeReadinessStates['runtime-blocked']")
                        .value(2));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiDiscoversProjectionCandidatesBeforeImport() throws Exception {
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                Map.of(),
                asyncApiProjectionFixture()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.length()").value(2))
                .andExpect(jsonPath("$.operations[0].operationId").value("creditDecisionWebhook"))
                .andExpect(jsonPath("$.operations[0].channelName").value("/webhooks/credit-decision"))
                .andExpect(jsonPath("$.operations[0].action").value("subscribe"))
                .andExpect(jsonPath("$.operations[0].messageName").value("CreditDecision"))
                .andExpect(jsonPath("$.operations[0].sourceKind").value("webhook"))
                .andExpect(jsonPath("$.operations[0].payloadType").value("object"))
                .andExpect(jsonPath("$.operations[0].projectionLevel").value("READY"))
                .andExpect(jsonPath("$.operations[1].operationId").value("sendRiskCommand"))
                .andExpect(jsonPath("$.operations[1].channelName").value("risk.commands"))
                .andExpect(jsonPath("$.operations[1].action").value("publish"))
                .andExpect(jsonPath("$.operations[1].sourceKind").value("message-handler"))
                .andExpect(jsonPath("$.validation.valid").value(true));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiProjectsMessageHeadersAsSchemaAwarePorts() throws Exception {
        String asyncApi = """
                asyncapi: '2.6.0'
                info:
                  title: Risk Commands
                  version: 1.2.3
                channels:
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        headers:
                          $ref: '#/components/schemas/RiskHeaders'
                        payload:
                          type: object
                          properties:
                            commandId:
                              type: string
                          required:
                            - commandId
                components:
                  schemas:
                    RiskHeaders:
                      type: object
                      properties:
                        tenantId:
                          type: string
                        traceId:
                          type: string
                      required:
                        - tenantId
                """;
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                Map.of(),
                asyncApi
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.length()").value(1))
                .andExpect(jsonPath("$.operations[0].operationId").value("sendRiskCommand"))
                .andExpect(jsonPath("$.operations[0].hasHeaders").value(true))
                .andExpect(jsonPath("$.operations[0].headersType").value("object"))
                .andExpect(jsonPath("$.operations[0].projectionLevel").value("READY"))
                .andExpect(jsonPath("$.validation.valid").value(true));

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library.libraryId").value("risk-commands-operators"))
                .andExpect(jsonPath("$.library.operators.length()").value(1))
                .andExpect(jsonPath("$.library.operators[0].source.kind").value("message-handler"))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs.length()").value(2))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[0].name").value("message"))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[1].name").value("headers"))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[1].required").value(true))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[1].schema.schema.type")
                        .value("object"))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[1].schema.schema.properties.tenantId.type")
                        .value("string"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.channel")
                        .value("risk.commands"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.asyncApi.operationId")
                        .value("sendRiskCommand"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.asyncApi.messageName")
                        .value("RiskCommand"))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.asyncApi.hasHeaders")
                        .value(true))
                .andExpect(jsonPath("$.library.operators[0].lowering.parameters.asyncApi.headersType")
                        .value("object"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.diagnostics").isEmpty());

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiRejectsUnresolvedSchemaReferencesBeforeProjection() throws Exception {
        String asyncApi = """
                asyncapi: '2.6.0'
                info:
                  title: Risk Commands
                  version: 1.2.3
                channels:
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        headers:
                          $ref: 'https://schemas.example.test/RiskHeaders.json'
                        payload:
                          $ref: '#/components/schemas/MissingCommand'
                components:
                  schemas:
                    PresentButUnused:
                      type: object
                """;
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                Map.of(),
                asyncApi
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.length()").value(1))
                .andExpect(jsonPath("$.operations[0].operationId").value("sendRiskCommand"))
                .andExpect(jsonPath("$.operations[0].hasPayload").value(true))
                .andExpect(jsonPath("$.operations[0].payloadType").value("opaque"))
                .andExpect(jsonPath("$.operations[0].hasHeaders").value(true))
                .andExpect(jsonPath("$.operations[0].headersType").value("opaque"))
                .andExpect(jsonPath("$.operations[0].projectionLevel").value("BLOCKED"))
                .andExpect(jsonPath("$.operations[0].projectionMessage")
                        .value(containsString("MissingCommand")))
                .andExpect(jsonPath("$.validation.valid").value(true));

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library").doesNotExist())
                .andExpect(jsonPath("$.availableOperations.length()").value(1))
                .andExpect(jsonPath("$.availableOperations[0].projectionLevel").value("BLOCKED"))
                .andExpect(jsonPath("$.selectedOperations.length()").value(1))
                .andExpect(jsonPath("$.projectionReview.availableProjectionLevelCounts.BLOCKED").value(1))
                .andExpect(jsonPath("$.projectionReview.selectedProjectionLevelCounts.BLOCKED").value(1))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics.length()").value(2))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.asyncapi.schemaRefUnresolved"))
                .andExpect(jsonPath("$.validation.diagnostics[0].message")
                        .value(containsString("#/components/schemas/MissingCommand")))
                .andExpect(jsonPath("$.validation.diagnostics[0].target")
                        .value("/asyncApi/channels/risk.commands/publish/message/payload/$ref"))
                .andExpect(jsonPath("$.validation.diagnostics[1].code")
                        .value("visual.library.asyncapi.schemaRefUnresolved"))
                .andExpect(jsonPath("$.validation.diagnostics[1].message")
                        .value(containsString("https://schemas.example.test/RiskHeaders.json")))
                .andExpect(jsonPath("$.validation.diagnostics[1].target")
                        .value("/asyncApi/channels/risk.commands/publish/message/headers/$ref"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiProjectsSelectedOperationSubsetWithoutStoring() throws Exception {
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                "sendRiskCommand",
                "risk.commands",
                "publish",
                "RiskCommand",
                Map.of(),
                asyncApiProjectionFixture()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library.libraryId").value("risk-events-operators"))
                .andExpect(jsonPath("$.library.operators.length()").value(1))
                .andExpect(jsonPath("$.library.operators[0].operatorRef").value("asyncapi:RiskCommand"))
                .andExpect(jsonPath("$.library.operators[0].source.kind").value("message-handler"))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[0].name").value("message"))
                .andExpect(jsonPath("$.library.operators[0].ports.outputs[0].name").value("ack"))
                .andExpect(jsonPath("$.selectionApplied").value(true))
                .andExpect(jsonPath("$.availableOperations.length()").value(2))
                .andExpect(jsonPath("$.selectedOperations.length()").value(1))
                .andExpect(jsonPath("$.selectedOperations[0].operationId").value("sendRiskCommand"))
                .andExpect(jsonPath("$.omittedOperationCount").value(1))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.projectionReview.selectionApplied").value(true))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].target").value("/operationId"))
                .andExpect(jsonPath("$.projectionReview.omittedOperations.length()").value(1))
                .andExpect(jsonPath("$.projectionReview.omittedOperations[0].reason").value("not-selected"))
                .andExpect(jsonPath("$.projectionReview.omittedOperations[0].operation.operationId")
                        .value("creditDecisionWebhook"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.profile.operatorCount").value(1))
                .andExpect(jsonPath("$.validation.profile.runtimeBlockedOperatorCount").value(1));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiProjectsSelectedOperationBatchWithoutStoring() throws Exception {
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(
                        new AsyncApiOperationSelection("creditDecisionWebhook",
                                "/webhooks/credit-decision", "subscribe", "CreditDecision"),
                        new AsyncApiOperationSelection("sendRiskCommand",
                                "risk.commands", "publish", "RiskCommand")
                ),
                Map.of(),
                asyncApiBatchProjectionFixture()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library.libraryId").value("risk-events-operators"))
                .andExpect(jsonPath("$.library.operators.length()").value(2))
                .andExpect(jsonPath("$.library.operators[0].operatorRef").value("asyncapi:CreditDecision"))
                .andExpect(jsonPath("$.library.operators[1].operatorRef").value("asyncapi:RiskCommand"))
                .andExpect(jsonPath("$.library.operators[?(@.operatorRef == 'asyncapi:RiskAudit')].operatorRef")
                        .isEmpty())
                .andExpect(jsonPath("$.selectionApplied").value(true))
                .andExpect(jsonPath("$.availableOperations.length()").value(3))
                .andExpect(jsonPath("$.selectedOperations.length()").value(2))
                .andExpect(jsonPath("$.selectedOperations[0].operationId").value("creditDecisionWebhook"))
                .andExpect(jsonPath("$.selectedOperations[1].operationId").value("sendRiskCommand"))
                .andExpect(jsonPath("$.omittedOperationCount").value(1))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.projectionReview.availableOperationCount").value(3))
                .andExpect(jsonPath("$.projectionReview.selectedOperationCount").value(2))
                .andExpect(jsonPath("$.projectionReview.omittedOperationCount").value(1))
                .andExpect(jsonPath("$.projectionReview.unmatchedSelectionCount").value(0))
                .andExpect(jsonPath("$.projectionReview.selectionMatches.length()").value(2))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].target").value("/selections/0"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[1].target").value("/selections/1"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[1].status").value("MATCHED"))
                .andExpect(jsonPath("$.projectionReview.omittedOperations.length()").value(1))
                .andExpect(jsonPath("$.projectionReview.omittedOperations[0].operation.operationId")
                        .value("riskAuditEvent"))
                .andExpect(jsonPath("$.projectionReview.availableProjectionLevelCounts.READY").value(3))
                .andExpect(jsonPath("$.projectionReview.selectedProjectionLevelCounts.READY").value(2))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.profile.operatorCount").value(2))
                .andExpect(jsonPath("$.validation.profile.runtimeBlockedOperatorCount").value(2));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiRejectsPartiallyMissingBatchSelectionWithoutSilentOmission() throws Exception {
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(
                        new AsyncApiOperationSelection("creditDecisionWebhook",
                                "/webhooks/credit-decision", "subscribe", "CreditDecision"),
                        new AsyncApiOperationSelection("missingOperation",
                                "risk.commands", "publish", "RiskCommand")
                ),
                Map.of(),
                asyncApiBatchProjectionFixture()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library").doesNotExist())
                .andExpect(jsonPath("$.selectionApplied").value(true))
                .andExpect(jsonPath("$.availableOperations.length()").value(3))
                .andExpect(jsonPath("$.selectedOperations.length()").value(1))
                .andExpect(jsonPath("$.selectedOperations[0].operationId").value("creditDecisionWebhook"))
                .andExpect(jsonPath("$.omittedOperationCount").value(2))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.projectionReview.unmatchedSelectionCount").value(1))
                .andExpect(jsonPath("$.projectionReview.selectionMatches.length()").value(2))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[1].status").value("NO_MATCH"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[1].target").value("/selections/1"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[1].matchedOperationCount").value(0))
                .andExpect(jsonPath("$.projectionReview.omittedOperations.length()").value(2))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.asyncapi.selectionMissing"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/selections/1"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiRejectsMissingSelectedOperation() throws Exception {
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                "missingOperation",
                "",
                "",
                "",
                Map.of(),
                asyncApiProjectionFixture()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library").doesNotExist())
                .andExpect(jsonPath("$.selectionApplied").value(true))
                .andExpect(jsonPath("$.availableOperations.length()").value(2))
                .andExpect(jsonPath("$.selectedOperations").isEmpty())
                .andExpect(jsonPath("$.omittedOperationCount").value(2))
                .andExpect(jsonPath("$.projectionReview.coverageStatus").value("NO_MATCH"))
                .andExpect(jsonPath("$.projectionReview.unmatchedSelectionCount").value(1))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].status").value("NO_MATCH"))
                .andExpect(jsonPath("$.projectionReview.selectionMatches[0].target").value("/operationId"))
                .andExpect(jsonPath("$.projectionReview.omittedOperations.length()").value(2))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.asyncapi.selectionMissing"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/operationId"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void fromAsyncApiRejectsUnsupportedSourceKind() throws Exception {
        String asyncApi = """
                asyncapi: '2.6.0'
                info:
                  title: Risk Events
                  version: 1.2.3
                channels:
                  risk.events:
                    subscribe:
                      x-bloge-source-kind: kafka-magic
                      message:
                        name: RiskEvent
                        payload:
                          type: object
                """;
        AsyncApiOperatorLibraryImportRequest request = new AsyncApiOperatorLibraryImportRequest(
                "",
                "",
                "",
                "",
                "",
                Map.of(),
                asyncApi
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/from-asyncapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value(AsyncApiOperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.library").doesNotExist())
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.asyncapi.sourceKindUnsupported"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target")
                        .value("/asyncApi/channels/risk.events/subscribe/x-bloge-source-kind"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void exportLibraryReturnsPortableBundleWithRevisionEvidenceAndValidationProfile() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("actor", "alice")
                        .param("changeSource", "catalog-admin")
                        .param("changeSummary", "Imported design-only risk policy schema.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy-design/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryExportBundle.SCHEMA_VERSION))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceVersion").value("1.0.0"))
                .andExpect(jsonPath("$.sourceStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.sourceRevision").value(1))
                .andExpect(jsonPath("$.exportedAt").exists())
                .andExpect(jsonPath("$.bundleFingerprint").exists())
                .andExpect(jsonPath("$.library.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.latestRevision.action").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.latestRevision.revisionMetadata.actor").value("alice"))
                .andExpect(jsonPath("$.latestRevision.revisionMetadata.changeSource").value("catalog-admin"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.impact.schemaVersion")
                        .value(OperatorLibraryImpactReview.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.profile.schemaVersion")
                        .value(OperatorLibraryProfile.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.profile.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.validation.profile.designOnlyOperatorCount").value(1))
                .andExpect(jsonPath("$.validation.profile.facets.runtimeReadinessStates['design-only']")
                        .value(1))
                .andReturn();

        OperatorLibraryExportBundle bundle = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                OperatorLibraryExportBundle.class);
        assertThat(bundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(bundle.bundleFingerprint()).hasSize(71);
        assertThat(bundle.bundleFingerprintVerified()).isTrue();
        OperatorLibraryExportBundle sameMaterialDifferentExportTime = new OperatorLibraryExportBundle(
                bundle.schemaVersion(),
                bundle.sourceLibraryId(),
                bundle.sourceVersion(),
                bundle.sourceStatus(),
                bundle.sourceRevision(),
                Instant.EPOCH,
                "",
                bundle.library(),
                bundle.latestRevision(),
                bundle.validation());
        assertThat(sameMaterialDifferentExportTime.bundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
    }

    @Test
    void functionOnlyLibraryRoundTripsThroughExportAndBundleImport() throws Exception {
        OperatorLibrary library = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "integer")
        );
        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated());

        MvcResult exported = mockMvc.perform(get("/admin/visual-operator-libraries/risk-functions/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library.operators.length()").value(0))
                .andExpect(jsonPath("$.library.builtInFunctions[0].name").value("risk.normalize"))
                .andExpect(jsonPath("$.latestRevision.library.builtInFunctions[0].name")
                        .value("risk.normalize"))
                .andReturn();
        OperatorLibraryExportBundle bundle = objectMapper.readValue(
                exported.getResponse().getContentAsString(),
                OperatorLibraryExportBundle.class);
        assertThat(bundle.bundleFingerprintVerified()).isTrue();

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-functions"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.library.builtInFunctions[0].name").value("risk.normalize"))
                .andExpect(jsonPath("$.latestRevision.library.operators.length()").value(0));

        assertThat(registry.find("risk-functions"))
                .map(OperatorLibrary::builtInFunctions)
                .hasValueSatisfying(functions -> assertThat(functions)
                        .extracting(OperatorLibrary.BuiltInFunction::name)
                        .containsExactly("risk.normalize"));
    }

    @Test
    void exportBuiltinLibraryUsesPortableBundleWithoutRuntimeOwnershipSelfConflict() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");

        MvcResult result = mockMvc.perform(get("/admin/visual-operator-libraries/builtin/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryExportBundle.SCHEMA_VERSION))
                .andExpect(jsonPath("$.sourceLibraryId").value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.sourceRevision").value(0))
                .andExpect(jsonPath("$.bundleFingerprint").exists())
                .andExpect(jsonPath("$.library.libraryId").value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.library.operators[0].operatorRef").value("runtimeScorePolicy"))
                .andExpect(jsonPath("$.latestRevision").doesNotExist())
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.profile.libraryId")
                        .value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.validation.profile.governanceReviewOperatorCount").value(1))
                .andExpect(jsonPath("$.validation.profile.facets.runtimeReadinessStates['governance-review']")
                        .value(1))
                .andExpect(jsonPath("$.library.operators[0].source.kind").value("user-library"))
                .andExpect(jsonPath("$.library.operators[0].source.libraryId")
                        .value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.library.operators[0].ports.inputs[0].name").value("inputs"))
                .andExpect(jsonPath("$.library.operators[0].lowering.mode").value("native"))
                .andExpect(jsonPath("$.library.operators[0].lowering.operatorRef").value("runtimeScorePolicy"))
                .andReturn();

        OperatorLibraryExportBundle bundle = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                OperatorLibraryExportBundle.class);
        assertThat(bundle.bundleFingerprintVerified()).isTrue();
        assertThat(bundle.validation().diagnostics())
                .noneMatch(diagnostic -> "visual.library.operatorRefRuntimeOwned".equals(diagnostic.code()));

        InMemoryOperatorLibraryRegistry targetRegistry = new InMemoryOperatorLibraryRegistry();
        OperatorLibraryAdminController targetController = new OperatorLibraryAdminController(
                targetRegistry,
                new OperatorLibraryValidator(),
                new InMemoryGraphDraftRepository(),
                new InMemoryVisualGraphPublicationRepository()
        );
        MockMvc targetMvc = MockMvcBuilders.standaloneSetup(targetController).build();
        String bundleJson = objectMapper.writeValueAsString(bundle);

        targetMvc.perform(post("/admin/visual-operator-libraries/validate-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bundleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceLibraryId").value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.validation.valid").value(true));

        targetMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .param("actor", "stage-sync")
                        .param("changeSource", "builtin-export")
                        .param("changeSummary", "Imported virtual builtin operator registry.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bundleJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(true))
                .andExpect(jsonPath("$.importedLibraryId")
                        .value(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID))
                .andExpect(jsonPath("$.latestRevision.action").value(OperatorLibraryRevision.ACTION_CREATE));
        assertThat(targetRegistry.find(BuiltinOperatorLibraryExporter.BUILTIN_LIBRARY_ID)).isPresent();
    }

    @Test
    void exportLibraryReturnsNotFoundForMissingCurrentLibrary() throws Exception {
        mockMvc.perform(get("/admin/visual-operator-libraries/missing/export"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validateBundlePreviewsTargetCreateWithoutStoring() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleSchemaVersion")
                        .value(OperatorLibraryExportBundle.SCHEMA_VERSION))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceRevision").value(7))
                .andExpect(jsonPath("$.importedLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.library.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.targetDiff.schemaVersion").value(OperatorLibraryDiff.SCHEMA_VERSION))
                .andExpect(jsonPath("$.targetDiff.baseRevision").value(0))
                .andExpect(jsonPath("$.targetDiff.targetRevision").value(7))
                .andExpect(jsonPath("$.targetDiff.baseAction").value("SNAPSHOT"))
                .andExpect(jsonPath("$.targetDiff.targetAction").value("SNAPSHOT"))
                .andExpect(jsonPath("$.targetDiff.changed").value(true))
                .andExpect(jsonPath("$.targetDiff.addedOperatorCount").value(1))
                .andExpect(jsonPath("$.targetDiff.operatorChanges[0].changeKind").value("ADDED"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.profile.schemaVersion")
                        .value(OperatorLibraryProfile.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.profile.designOnlyOperatorCount").value(1));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateBundlePreviewsTargetReplacementWithoutAddingRevision() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        registry.upsert(library, OperatorLibraryRevision.RevisionMetadata.of(
                "seed", "test", "Seed existing library.", ""));
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                8,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceRevision").value(8))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_REPLACE))
                .andExpect(jsonPath("$.targetDiff.schemaVersion").value(OperatorLibraryDiff.SCHEMA_VERSION))
                .andExpect(jsonPath("$.targetDiff.baseRevision").value(1))
                .andExpect(jsonPath("$.targetDiff.targetRevision").value(8))
                .andExpect(jsonPath("$.targetDiff.baseAction").value("SNAPSHOT"))
                .andExpect(jsonPath("$.targetDiff.targetAction").value("SNAPSHOT"))
                .andExpect(jsonPath("$.targetDiff.changed").value(false))
                .andExpect(jsonPath("$.targetDiff.addedOperatorCount").value(0))
                .andExpect(jsonPath("$.targetDiff.removedOperatorCount").value(0))
                .andExpect(jsonPath("$.targetDiff.changedOperatorCount").value(0))
                .andExpect(jsonPath("$.validation.valid").value(true));

        assertThat(registry.find("risk-policy-design")).contains(library);
        assertThat(registry.revisions("risk-policy-design")).hasSize(1);
    }

    @Test
    void importBundleStoresLibraryWithTargetValidationAndRevisionEvidence() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .param("actor", "stage-sync")
                        .param("changeSource", "catalog-bundle")
                        .param("changeSummary", "Imported portable risk policy bundle.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(true))
                .andExpect(jsonPath("$.sourceBundleSchemaVersion")
                        .value(OperatorLibraryExportBundle.SCHEMA_VERSION))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceVersion").value("1.0.0"))
                .andExpect(jsonPath("$.sourceStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.sourceRevision").value(7))
                .andExpect(jsonPath("$.importedAt").exists())
                .andExpect(jsonPath("$.importedLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.library.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.latestRevision.action").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.targetDiff.schemaVersion").value(OperatorLibraryDiff.SCHEMA_VERSION))
                .andExpect(jsonPath("$.targetDiff.baseRevision").value(0))
                .andExpect(jsonPath("$.targetDiff.targetRevision").value(7))
                .andExpect(jsonPath("$.targetDiff.changed").value(true))
                .andExpect(jsonPath("$.targetDiff.addedOperatorCount").value(1))
                .andExpect(jsonPath("$.latestRevision.revisionMetadata.actor").value("stage-sync"))
                .andExpect(jsonPath("$.latestRevision.revisionMetadata.changeSource")
                        .value("catalog-bundle"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.profile.schemaVersion")
                        .value(OperatorLibraryProfile.SCHEMA_VERSION))
                .andExpect(jsonPath("$.validation.profile.libraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.validation.profile.designOnlyOperatorCount").value(1))
                .andExpect(jsonPath("$.validation.profile.facets.runtimeReadinessStates['design-only']")
                        .value(1));

        assertThat(registry.find("risk-policy-design")).contains(library);
        assertThat(registry.revisions("risk-policy-design").getFirst().revisionMetadata().changeSummary())
                .isEqualTo("Imported portable risk policy bundle.");
    }

    @Test
    void importBundleReturnsStructuredPersistenceFailureWithoutStoring() throws Exception {
        useFailingRegistry(FailingMutation.UPSERT);
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .param("actor", "stage-sync")
                        .param("changeSource", "catalog-bundle")
                        .param("changeSummary", "Imported portable risk policy bundle.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.importBundlePersistenceFailed"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/library"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.libraryId")
                        .value("risk-policy-design"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.exception")
                        .value("IllegalStateException"));

        assertThat(registry.find("risk-policy-design")).isEmpty();
    }

    @Test
    void importBundleRequiresGovernanceEvidenceWhenWarningsAreAcknowledged() throws Exception {
        OperatorLibrary library = libraryWithCapabilities("bundle-risk",
                new OperatorDefinition.Capabilities("WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, true));
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .param("ackWarnings", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.governanceEvidenceMissing"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/actor"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.requiredFor[0]")
                        .value("ackWarnings"))
                .andExpect(jsonPath("$.validation.diagnostics[1].code")
                        .value("visual.library.governanceEvidenceMissing"))
                .andExpect(jsonPath("$.validation.diagnostics[1].target").value("/reason"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void importBundleRejectsUnsupportedExportBundleSchemaVersion() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "bloge.visualOperatorLibraryExport.v2",
                "",
                "",
                "",
                9,
                null,
                library,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleSchemaVersion")
                        .value("bloge.visualOperatorLibraryExport.v2"))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceRevision").value(9))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryImportResult.ACTION_REJECTED))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.bundle.schemaVersionUnsupported"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/schemaVersion"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.actual")
                        .value("bloge.visualOperatorLibraryExport.v2"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.expected")
                        .value(OperatorLibraryExportBundle.SCHEMA_VERSION));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void importBundleRejectsMismatchedBundleFingerprintBeforeStorage() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle base = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );
        OperatorLibraryExportBundle forged = new OperatorLibraryExportBundle(
                base.schemaVersion(),
                base.sourceLibraryId(),
                base.sourceVersion(),
                base.sourceStatus(),
                base.sourceRevision(),
                base.exportedAt(),
                "sha256:forged",
                base.library(),
                base.latestRevision(),
                base.validation()
        );

        assertThat(forged.bundleFingerprintVerified()).isFalse();
        assertThat(forged.computedBundleFingerprint()).isEqualTo(base.bundleFingerprint());

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forged)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value("sha256:forged"))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryImportResult.ACTION_REJECTED))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.bundle.fingerprintMismatch"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/bundleFingerprint"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.actual").value("sha256:forged"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.expected")
                        .value(base.bundleFingerprint()));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateBundleRejectsMismatchedBundleFingerprintBeforeTargetValidation() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryExportBundle base = new OperatorLibraryExportBundle(
                "",
                "",
                "",
                "",
                7,
                null,
                library,
                null,
                null
        );
        OperatorLibraryExportBundle forged = new OperatorLibraryExportBundle(
                base.schemaVersion(),
                base.sourceLibraryId(),
                base.sourceVersion(),
                base.sourceStatus(),
                base.sourceRevision(),
                base.exportedAt(),
                "sha256:forged",
                base.library(),
                base.latestRevision(),
                base.validation()
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forged)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value("sha256:forged"))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryImportResult.ACTION_REJECTED))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.bundle.fingerprintMismatch"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/bundleFingerprint"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.actual").value("sha256:forged"))
                .andExpect(jsonPath("$.validation.diagnostics[0].metadata.expected")
                        .value(base.bundleFingerprint()));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void importBundleRejectsMissingLibrarySnapshotWithStructuredResult() throws Exception {
        OperatorLibraryExportBundle bundle = new OperatorLibraryExportBundle(
                "",
                "risk-policy-design",
                "1.0.0",
                "ACTIVE",
                3,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/import-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bundle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryImportResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.sourceBundleFingerprint").value(bundle.bundleFingerprint()))
                .andExpect(jsonPath("$.sourceLibraryId").value("risk-policy-design"))
                .andExpect(jsonPath("$.sourceRevision").value(3))
                .andExpect(jsonPath("$.mutationAction").value(OperatorLibraryImportResult.ACTION_REJECTED))
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code")
                        .value("visual.library.bundle.snapshotMissing"))
                .andExpect(jsonPath("$.validation.diagnostics[0].target").value("/library"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateLibraryTextRejectsMalformedSourceWithStructuredDiagnostic() throws Exception {
        mockMvc.perform(post("/admin/visual-operator-libraries/validate-text")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("libraryId: ["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.source.malformed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/sourceText"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsInvalidLibrary() throws Exception {
        OperatorLibrary invalid = invalidArrayLibrary();

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"))
                .andExpect(jsonPath("$.profile.catalogRepairOperatorCount").value(1));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsNullOperatorEntryWithStructuredDiagnostic() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "null-operator",
                  "operators": [null]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.missing"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsNullPortEntryWithStructuredDiagnostic() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "null-port",
                  "operators": [{
                    "operatorRef": "risk:nullPort",
                    "ports": {
                      "inputs": [null],
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.port.missing"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/ports/inputs/0"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsafeLibraryId() throws Exception {
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk policy/2026",
                "Invalid library id",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.id.invalid"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/libraryId"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsafeLibraryAndOperatorVersions() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                "1.x",
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Invalid versions",
                "2026 release",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.version.invalid"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/version"))
                .andExpect(jsonPath("$.diagnostics[1].code").value("visual.operator.version.invalid"))
                .andExpect(jsonPath("$.diagnostics[1].target").value("/operators/0/operatorVersion"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsSystemManagedSourceKind() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                new OperatorDefinition.Source("visual-publication", "", "", "", true),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Reserved source kind",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.source.kind.reserved"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/source/kind"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsCanonicalizedSystemManagedSourceKind() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "reserved-source-kind-variant",
                  "operators": [{
                    "operatorRef": "risk:javaSource",
                    "source": { "kind": " Java-Operator " },
                    "ports": {
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.source.kind.reserved"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/source/kind"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsupportedImportedSourceKind() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "unsupported-source-kind",
                  "operators": [{
                    "operatorRef": "risk:partnerSource",
                    "source": { "kind": "partner-catalog" },
                    "ports": {
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.source.kind.unsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/source/kind"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUserSuppliedOperatorDiagnostics() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                List.of(VisualDiagnostic.warning("visual.operator.fake", "Forged warning.", "/operatorRef"))
        );
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Forged diagnostics",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.diagnostics.managed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/diagnostics"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsSystemManagedOperatorRefPrefix() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                "publication:pub-eligibility",
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Reserved operator ref",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.ref.reserved"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsHiddenPublicationExecutorLowering() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering("native", "visualPublication", Map.of()),
                base.diagnostics()
        );
        OperatorLibrary invalid = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Hidden executor lowering",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.lowering.operatorRef.reserved"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/lowering/operatorRef"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsSystemManagedExecutableLoweringTarget() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "reserved-lowering-target",
                  "operators": [{
                    "operatorRef": "risk:reservedLoweringTarget",
                    "lowering": { "mode": "native", "operatorRef": "resource:loan-applicant-service.getProfile" },
                    "ports": {
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.lowering.operatorRef.reserved"))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/operators/0/lowering/operatorRef"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsDuplicateOperatorRefsWithStructuredDiagnostics() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "duplicate-risk",
                  "displayName": "Duplicate risk",
                  "operators": [
                    {
                      "operatorRef": "risk:eligibility",
                      "ports": {
                        "outputs": [{
                          "name": "output",
                          "schema": { "schema": { "type": "object" } },
                          "required": true
                        }]
                      }
                    },
                    {
                      "operatorRef": "risk:eligibility",
                      "ports": {
                        "outputs": [{
                          "name": "output",
                          "schema": { "schema": { "type": "object" } },
                          "required": true
                        }]
                      }
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.ref.duplicate"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/1/operatorRef"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateRejectsUnsupportedLibrarySchemaVersion() throws Exception {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v2",
                "future-risk",
                "Future risk",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.schemaVersion.unsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/schemaVersion"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsupportedOperatorSchemaVersion() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition futureOperator = new OperatorDefinition(
                "bloge.visualOperator.v2",
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "future-risk",
                "Future risk",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(futureOperator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.schemaVersion.unsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/schemaVersion"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsupportedOperatorCapabilities() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("NETWORK_MAGIC", "MAYBE", false, false, false),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-capabilities",
                "Bad capabilities",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.capability.effectUnsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/capabilities/effect"))
                .andExpect(jsonPath("$.diagnostics[1].code")
                        .value("visual.operator.capability.idempotencyUnsupported"))
                .andExpect(jsonPath("$.diagnostics[1].target").value("/operators/0/capabilities/idempotency"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsPolicyScopesThatMixWildcardAndConcreteValues() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                new OperatorDefinition.Policy(List.of("*", "demo-tenant"), List.of("local"), List.of("browser")),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-policy-scope",
                "Bad policy scope",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.policy.scopeWildcardMixed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/policy/tenants"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createStoresCanonicalizedLoweringMode() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering(" Transform ", base.lowering().operatorRef(),
                        base.lowering().parameters()),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "canonical-lowering",
                "Canonical lowering",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].lowering.mode").value("transform"));

        assertThat(registry.find("canonical-lowering"))
                .map(stored -> stored.operators().getFirst().lowering().mode())
                .contains("transform");
    }

    @Test
    void createStoresValidLibrary() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("risk-policy"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:eligibility"));
    }

    @Test
    void createReturnsStructuredPersistenceFailureWithoutStoring() throws Exception {
        useFailingRegistry(FailingMutation.UPSERT);
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.importPersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/library"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction")
                        .value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$.diagnostics[0].metadata.exception").value("IllegalStateException"));

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    @Test
    void updateReturnsStructuredPersistenceFailureAndKeepsCurrentLibrary() throws Exception {
        useFailingRegistry(FailingMutation.UPSERT);
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        ((FailingOperatorLibraryRegistry) registry).seed(original);
        OperatorLibrary replacement = libraryWithVersion(original, "1.1.0");

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.importPersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction")
                        .value(OperatorLibraryRevision.ACTION_REPLACE));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void revisionEndpointsReturnLibraryRegistryHistoryAfterDelete() throws Exception {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replaced = libraryWithVersion(created, "1.1.0");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replaced)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemaVersion")
                        .value(OperatorLibraryRevision.SCHEMA_VERSION))
                .andExpect(jsonPath("$[0].revision").value(3))
                .andExpect(jsonPath("$[0].action").value(OperatorLibraryRevision.ACTION_DELETE))
                .andExpect(jsonPath("$[0].library.version").value("1.1.0"))
                .andExpect(jsonPath("$[1].revision").value(2))
                .andExpect(jsonPath("$[1].action").value(OperatorLibraryRevision.ACTION_REPLACE))
                .andExpect(jsonPath("$[2].revision").value(1))
                .andExpect(jsonPath("$[2].action").value(OperatorLibraryRevision.ACTION_CREATE));
        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.library.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.library.version").value("1.1.0"));
        mockMvc.perform(get("/admin/visual-operator-libraries/missing/revisions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void revisionDiffEndpointReturnsMachineReadableSchemaChangeReview() throws Exception {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replaced = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "2.0.0");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replaced)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions/1/diff/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(OperatorLibraryDiff.SCHEMA_VERSION))
                .andExpect(jsonPath("$.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.baseRevision").value(1))
                .andExpect(jsonPath("$.targetRevision").value(2))
                .andExpect(jsonPath("$.baseVersion").value("1.0.0"))
                .andExpect(jsonPath("$.targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.changedOperatorCount").value(1))
                .andExpect(jsonPath("$.libraryChanges[0].field").value("revisionAction"))
                .andExpect(jsonPath("$.libraryChanges[1].field").value("version"))
                .andExpect(jsonPath("$.operatorChanges[0].operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.operatorChanges[0].changeKind").value("CHANGED"))
                .andExpect(jsonPath("$.operatorChanges[0].risk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.operatorChanges[0].schemaChanges[0].surface").value("input"))
                .andExpect(jsonPath("$.operatorChanges[0].schemaChanges[0].portName").value("inputs"))
                .andExpect(jsonPath("$.operatorChanges[0].schemaChanges[0].compatibility").value("breaking"))
                .andExpect(jsonPath("$.operatorChanges[0].schemaChanges[0].path").value("score"))
                .andExpect(jsonPath("$.operatorChanges[0].schemaChanges[0].message")
                        .value(org.hamcrest.Matchers.containsString("type")))
                .andExpect(jsonPath("$.operatorChanges[0].summary")
                        .value(org.hamcrest.Matchers.containsString("input port 'inputs' schema changed")));
        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions/1/diff/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void functionOnlyRevisionDiffAndRestorePreserveCallableContract() throws Exception {
        OperatorLibrary original = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "integer")
        );
        OperatorLibrary replacement = functionLibrary(
                "risk-functions",
                "2.0.0",
                function("risk.normalize", "risk", "string")
        );
        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/admin/visual-operator-libraries/risk-functions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-functions/revisions/1/diff/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.changeRisk")
                        .value(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA))
                .andExpect(jsonPath("$.libraryChanges[2].field")
                        .value("builtInFunctions/risk.normalize"))
                .andExpect(jsonPath("$.libraryChanges[2].summary").value(
                        "callable function 'risk.normalize' contract changed"));

        mockMvc.perform(post("/admin/visual-operator-libraries/risk-functions/revisions/1/restore")
                        .param("allowVersionRegression", "true")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Restore the reviewed function contract."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtInFunctions[0].signatures[0].returns.type")
                        .value("integer"));
        assertThat(registry.revisions("risk-functions"))
                .extracting(OperatorLibraryRevision::action)
                .containsExactly(
                        OperatorLibraryRevision.ACTION_RESTORE,
                        OperatorLibraryRevision.ACTION_REPLACE,
                        OperatorLibraryRevision.ACTION_CREATE
                );
    }

    @Test
    void functionContractBreakingChangeParticipatesInSemverGovernance() throws Exception {
        OperatorLibrary original = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "integer")
        );
        OperatorLibrary incompatibleMinor = functionLibrary(
                "risk-functions",
                "1.1.0",
                function("risk.normalize", "risk", "string")
        );
        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incompatibleMinor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.version.breakingRequiresMajor"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.callableNames[0]")
                        .value("risk.normalize"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk")
                        .value(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA));
    }

    @Test
    void restoreRevisionWritesNewLatestAuditSnapshotAfterDelete() throws Exception {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replaced = libraryWithVersion(created, "1.1.0");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replaced)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));
        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revision").value(4))
                .andExpect(jsonPath("$[0].action").value(OperatorLibraryRevision.ACTION_RESTORE))
                .andExpect(jsonPath("$[0].restoredFromRevision").value(1))
                .andExpect(jsonPath("$[0].library.version").value("1.0.0"))
                .andExpect(jsonPath("$[1].action").value(OperatorLibraryRevision.ACTION_DELETE))
                .andExpect(jsonPath("$[2].action").value(OperatorLibraryRevision.ACTION_REPLACE))
                .andExpect(jsonPath("$[3].action").value(OperatorLibraryRevision.ACTION_CREATE));
    }

    @Test
    void restoreRevisionReturnsStructuredPersistenceFailure() throws Exception {
        useFailingRegistry(FailingMutation.RESTORE);
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        ((FailingOperatorLibraryRegistry) registry).seed(library);

        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.restorePersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/library"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction")
                        .value(OperatorLibraryRevision.ACTION_RESTORE))
                .andExpect(jsonPath("$.diagnostics[0].metadata.exception").value("IllegalStateException"));

        assertThat(registry.find("risk-policy")).contains(library);
        assertThat(registry.revisions("risk-policy")).hasSize(1);
    }

    @Test
    void revisionEndpointsExposeControlPlaneAuditMetadata() throws Exception {
        OperatorLibrary created = VisualCatalogTestSupport.eligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("actor", "alice")
                        .param("changeSource", "catalog-admin-ui")
                        .param("changeSummary", "Imported initial risk policy schema.")
                        .param("reason", "initial onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy")
                        .param("actor", "bob")
                        .param("changeSource", "catalog-admin-ui")
                        .param("changeSummary", "Deleted risk policy during cleanup.")
                        .param("reason", "tenant cleanup"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore")
                        .param("actor", "carol")
                        .param("changeSource", "catalog-admin-ui")
                        .param("changeSummary", "Restored risk policy after accidental delete.")
                        .param("reason", "rollback"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value(OperatorLibraryRevision.ACTION_RESTORE))
                .andExpect(jsonPath("$[0].revisionMetadata.actor").value("carol"))
                .andExpect(jsonPath("$[0].revisionMetadata.changeSource").value("catalog-admin-ui"))
                .andExpect(jsonPath("$[0].revisionMetadata.changeSummary")
                        .value("Restored risk policy after accidental delete."))
                .andExpect(jsonPath("$[0].revisionMetadata.reason").value("rollback"))
                .andExpect(jsonPath("$[1].action").value(OperatorLibraryRevision.ACTION_DELETE))
                .andExpect(jsonPath("$[1].revisionMetadata.actor").value("bob"))
                .andExpect(jsonPath("$[1].revisionMetadata.reason").value("tenant cleanup"))
                .andExpect(jsonPath("$[2].action").value(OperatorLibraryRevision.ACTION_CREATE))
                .andExpect(jsonPath("$[2].revisionMetadata.actor").value("alice"))
                .andExpect(jsonPath("$[2].revisionMetadata.changeSummary")
                        .value("Imported initial risk policy schema."));
    }

    @Test
    void restoreRevisionWarningGatesExplicitVersionRegression() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "2.0.0");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.version.regressed"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.previousVersion").value("2.0.0"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.replacementVersion").value("1.0.0"));
        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore")
                        .param("allowVersionRegression", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.restore.versionRegressionAllowed"));
        mockMvc.perform(post("/admin/visual-operator-libraries/risk-policy/revisions/1/restore")
                        .param("allowVersionRegression", "true")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved controlled rollback after reviewing affected drafts."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value(OperatorLibraryRevision.ACTION_RESTORE))
                .andExpect(jsonPath("$[0].restoredFromRevision").value(1));
        assertThat(registry.find("risk-policy"))
                .map(OperatorLibrary::version)
                .contains("1.0.0");
    }

    @Test
    void createRejectsOperatorRefAlreadyOwnedByAnotherLibrary() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorRefOwned"))
                .andExpect(jsonPath("$.diagnostics[0].message").value("operatorRef 'risk:eligibility' already provided by library 'risk-policy'"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));
    }

    @Test
    void validateReportsOperatorRefAlreadyProvidedByRuntimeJavaInventory() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithOperatorRef("runtime-collision", "runtimeScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.operatorRefRuntimeOwned"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value("operatorRef 'runtimeScorePolicy' is already provided by the runtime Java operator inventory."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));

        assertThat(registry.find("runtime-collision")).isEmpty();
    }

    @Test
    void createRejectsOperatorRefAlreadyProvidedByRuntimeJavaInventory() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithOperatorRef("runtime-collision", "runtimeScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.operatorRefRuntimeOwned"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));

        assertThat(registry.find("runtime-collision")).isEmpty();
    }

    @Test
    void validateWarnsWhenNativeLoweringExecutableIsNotVisibleInRuntimeInventory() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithNativeLowering("native-missing", "risk:visualScorePolicy",
                "missingScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.lowering.operatorRefUnresolved"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value("Native operator 'risk:visualScorePolicy' lowers to executable operatorRef 'missingScorePolicy', but that executable is not visible in the runtime Java operator inventory; acknowledge this warning only when an external executor will provide it."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/lowering/operatorRef"))
                .andExpect(jsonPath("$.impact.warningCount").value(1))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:visualScorePolicy"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.operator.lowering.operatorRefUnresolved"))
                .andExpect(jsonPath("$.impact.codeCounts[0].level").value("WARNING"))
                .andExpect(jsonPath("$.profile.runtimeBlockedOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.facets.runtimeReadinessStates['runtime-blocked']")
                        .value(1))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessState")
                        .value("runtime-blocked"))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessTitle")
                        .value("Runtime binding unresolved"))
                .andExpect(jsonPath("$.importReadiness.state").value("runtime-binding-required"))
                .andExpect(jsonPath("$.importReadiness.level").value("warning"))
                .andExpect(jsonPath("$.importReadiness.importableNow").value(false))
                .andExpect(jsonPath("$.importReadiness.importableAfterReview").value(true))
                .andExpect(jsonPath("$.importReadiness.requiresAckWarnings").value(true))
                .andExpect(jsonPath("$.importReadiness.requiresGovernanceEvidence").value(true))
                .andExpect(jsonPath("$.importReadiness.warningCodes[0]")
                        .value("visual.operator.lowering.operatorRefUnresolved"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirementCount").value(1))
                .andExpect(jsonPath("$.importReadiness.bindingKindCounts['runtime-adapter']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffLaneCounts['runtime-platform']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffKindCounts['runtime-adapter']").value(1))
                .andExpect(jsonPath("$.importReadiness.handoffTargetCounts['missingScorePolicy']").value(1))
                .andExpect(jsonPath("$.importReadiness.sourceKindCounts['user-library']").value(1))
                .andExpect(jsonPath("$.importReadiness.operatorLibraryIdCounts['native-missing']").value(1))
                .andExpect(jsonPath("$.importReadiness.loweringModeCounts['native']").value(1))
                .andExpect(jsonPath("$.importReadiness.readinessStateCounts['runtime-blocked']").value(1))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirementKeys[0]")
                        .value("RUNTIME_BINDING|operator-library|native-missing|risk:visualScorePolicy|runtime-adapter|missingScorePolicy|"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].groupKey")
                        .value("RUNTIME_BINDING_GROUP|operator-library|native-missing|runtime-platform|runtime-adapter|missingScorePolicy|runtime-adapter"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffLane")
                        .value("runtime-platform"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffKind")
                        .value("runtime-adapter"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].handoffTarget")
                        .value("missingScorePolicy"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].bindingKind")
                        .value("runtime-adapter"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingHandoffGroups[0].operatorRefs[0]")
                        .value("risk:visualScorePolicy"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].requirementKey")
                        .value("RUNTIME_BINDING|operator-library|native-missing|risk:visualScorePolicy|runtime-adapter|missingScorePolicy|"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].operatorRef")
                        .value("risk:visualScorePolicy"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].operatorLibraryId")
                        .value("native-missing"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].bindingKind")
                        .value("runtime-adapter"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].bindingTarget")
                        .value("missingScorePolicy"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffLane")
                        .value("runtime-platform"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffKind")
                        .value("runtime-adapter"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].handoffTarget")
                        .value("missingScorePolicy"))
                .andExpect(jsonPath("$.importReadiness.runtimeBindingRequirements[0].summary")
                        .value("Native lowering points at an executable operatorRef that is not visible in the current runtime inventory."));

        assertThat(registry.find("native-missing")).isEmpty();
    }

    @Test
    void createRequiresWarningAcknowledgementForUnresolvedNativeLoweringExecutable() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithNativeLowering("native-missing", "risk:visualScorePolicy",
                "missingScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.lowering.operatorRefUnresolved"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:visualScorePolicy"))
                .andExpect(jsonPath("$.profile.runtimeBlockedOperatorCount").value(1));

        assertThat(registry.find("native-missing")).isEmpty();
    }

    @Test
    void createStoresNativeLoweringExecutableWhenWarningsAcknowledged() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithNativeLowering("native-missing", "risk:visualScorePolicy",
                "missingScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "External runtime binding is reviewed for this operator.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("native-missing"));

        assertThat(registry.find("native-missing")).contains(library);
    }

    @Test
    void createRequiresGovernanceEvidenceWhenWarningsAreAcknowledged() throws Exception {
        OperatorLibrary library = libraryWithCapabilities("governance-risk",
                new OperatorDefinition.Capabilities("WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, true));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("ackWarnings", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.governanceEvidenceMissing"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/actor"))
                .andExpect(jsonPath("$.diagnostics[1].code")
                        .value("visual.library.governanceEvidenceMissing"))
                .andExpect(jsonPath("$.diagnostics[1].target").value("/reason"))
                .andExpect(jsonPath("$.importReadiness.state").value("governance-evidence-required"))
                .andExpect(jsonPath("$.importReadiness.requiresGovernanceEvidence").value(true))
                .andExpect(jsonPath("$.importReadiness.blockingCodes[0]")
                        .value("visual.library.governanceEvidenceMissing"));

        assertThat(registry.find("governance-risk")).isEmpty();
    }

    @Test
    void createStoresNativeLoweringExecutableWhenRuntimeInventoryOwnsExecutableRef() throws Exception {
        useRuntimeJavaOperator("runtimeScorePolicy");
        OperatorLibrary library = libraryWithNativeLowering("native-runtime", "risk:visualScorePolicy",
                "runtimeScorePolicy");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("native-runtime"));

        assertThat(registry.find("native-runtime")).contains(library);
    }

    @Test
    void createRequiresWarningAcknowledgementForRuntimeCapabilityWarnings() throws Exception {
        OperatorLibrary library = libraryWithCapabilities("capability-risk",
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", true, true, false));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.capability.streamingRequiresRuntime"))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/operators/0/capabilities/streaming"))
                .andExpect(jsonPath("$.diagnostics[1].code")
                        .value("visual.operator.capability.durableRequiresRuntime"))
                .andExpect(jsonPath("$.diagnostics[1].target")
                        .value("/operators/0/capabilities/durable"))
                .andExpect(jsonPath("$.impact.warningCount").value(2))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.profile.streamingOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.durableOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.runtimeBlockedOperatorCount").value(1))
                .andExpect(jsonPath("$.profile.operators[0].runtimeReadinessState")
                        .value("runtime-blocked"));

        assertThat(registry.find("capability-risk")).isEmpty();
    }

    @Test
    void createStoresCapabilityWarningLibraryWhenWarningsAcknowledged() throws Exception {
        OperatorLibrary library = libraryWithCapabilities("governance-risk",
                new OperatorDefinition.Capabilities("WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, true));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "External side-effect governance review completed.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("governance-risk"));

        assertThat(registry.find("governance-risk")).contains(library);
    }

    @Test
    void validateReportsOperatorRefAlreadyOwnedByAnotherLibrary() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
        registry.upsert(original);

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorRefOwned"))
                .andExpect(jsonPath("$.diagnostics[0].message").value("operatorRef 'risk:eligibility' already provided by library 'risk-policy'"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));

        assertThat(registry.find("risk-policy-copy")).isEmpty();
    }

    @Test
    void createStoresOperatorPolicyForCatalogGate() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer",
                new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("browser")));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].policy.tenants[0]").value("demo-tenant"))
                .andExpect(jsonPath("$.operators[0].policy.namespaces[0]").value("local"))
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));
    }

    @Test
    void createAcceptsLegacyPoliciesAlias() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "legacy-policy",
                  "operators": [{
                    "operatorRef": "risk:legacyPolicy",
                    "policies": {
                      "allowedTenants": ["demo-tenant"],
                      "allowedNamespaces": ["local"],
                      "allowedEnvironments": ["browser"],
                      "requiredPermissions": ["legacy.permission"]
                    },
                    "ports": {
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].source.kind").value("user-library"))
                .andExpect(jsonPath("$.operators[0].policy.tenants[0]").value("demo-tenant"))
                .andExpect(jsonPath("$.operators[0].policy.namespaces[0]").value("local"))
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));
    }

    @Test
    void deleteRejectsLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteForceBypassesStoredDraftReferenceGuard() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved deleting library despite draft references."))
                .andExpect(status().isNoContent());

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    @Test
    void deleteReturnsStructuredPersistenceFailureAndKeepsCurrentLibrary() throws Exception {
        useFailingRegistry(FailingMutation.DELETE);
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        ((FailingOperatorLibraryRegistry) registry).seed(library);

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.deletePersistenceFailed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/libraryId"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.mutationAction")
                        .value(OperatorLibraryRevision.ACTION_DELETE))
                .andExpect(jsonPath("$.diagnostics[0].metadata.exception").value("IllegalStateException"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteForceRequiresGovernanceEvidenceBeforeMutation() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.governanceEvidenceMissing"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/actor"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.requiredFor[0]").value("force"))
                .andExpect(jsonPath("$.diagnostics[1].target").value("/reason"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteRejectsLibraryReferencedByPublishedArtifactWithoutForce() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        publications.create(publicationUsingOperator("risk:eligibility", "published-fingerprint"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].level").value("ERROR"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.publicationInUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' cannot be deleted without force=true because publication 'publication-1' node 'eligibility' was authored with operatorRef 'risk:eligibility'. Existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/publication-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteForceBypassesPublishedArtifactReferenceGuard() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        publications.create(publicationUsingOperator("risk:eligibility", "published-fingerprint"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved deleting library despite frozen publication references."))
                .andExpect(status().isNoContent());

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    @Test
    void deleteSkipsNullOperatorsInStoredLibraryImpactAnalysis() throws Exception {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.Arrays.asList(null, VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteStillProtectsDraftReferencesWhenStoredOperatorHasNullPorts() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition malformed = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(base.ports().inputs(),
                        java.util.Arrays.asList(null, base.ports().outputs().getFirst())),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(malformed)
        );
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void updateRejectsRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' cannot be replaced without force=true because draft 'draft-1@1' node 'eligibility' still uses operatorRef 'risk:eligibility'."));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateRejectsDisablingLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary disabled = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' cannot be replaced without force=true because draft 'draft-1@1' node 'eligibility' still uses operatorRef 'risk:eligibility'."));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void createRejectsReimportRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateReportsReimportRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.impact.schemaVersion").value(OperatorLibraryImpactReview.SCHEMA_VERSION))
                .andExpect(jsonPath("$.impact.errorCount").value(1))
                .andExpect(jsonPath("$.impact.draftIds[0]").value("draft-1"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.draftTargets[0].draftId").value("draft-1"))
                .andExpect(jsonPath("$.impact.draftTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.codeCounts[0].code").value("visual.library.inUse"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateReportsDisablingLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary disabled = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenDeprecatingLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary deprecated = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DEPRECATED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.lifecycle.deprecated"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' is being deprecated; draft 'draft-1@1' node 'eligibility' still uses operatorRef 'risk:eligibility'. Review migration before production promotion."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryId").value("risk-policy"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.libraryStatus").value("DEPRECATED"))
                .andExpect(jsonPath("$.impact.warningCount").value(1))
                .andExpect(jsonPath("$.impact.draftIds[0]").value("draft-1"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.library.lifecycle.deprecated"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateRequiresWarningAcknowledgementBeforeDeprecatingUsedLibrary() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary deprecated = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DEPRECATED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.lifecycle.deprecated"));

        assertThat(registry.find("risk-policy")).contains(original);

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved deprecation after reviewing affected drafts.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));

        assertThat(registry.find("risk-policy")).contains(deprecated);
    }

    @Test
    void validateForceBypassesStoredDraftReferenceImpact() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly("2.0.0");
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty())
                .andExpect(jsonPath("$.impact.diagnosticCount").value(0))
                .andExpect(jsonPath("$.impact.operatorRefs").isEmpty());

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingUsedOperatorRefWithDifferentFingerprint() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        String originalFingerprint = original.operators().get(0).fingerprint();
        String replacementFingerprint = replacement.operators().get(0).fingerprint();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility", originalFingerprint));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("saved fingerprint '" + originalFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("'" + replacementFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "changed surface: change risk: BREAKING_SCHEMA; input port 'inputs' schema changed")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeCategories[0]").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].surface").value("input"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].portName").value("inputs"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].compatibility").value("breaking"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].path").value("score"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].message")
                        .value(org.hamcrest.Matchers.containsString("type")))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.impact.warningCount").value(2))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].count").value(2))
                .andExpect(jsonPath("$.impact.draftIds[0]").value("draft-1"))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.draftTargets[0].draftId").value("draft-1"))
                .andExpect(jsonPath("$.impact.draftTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.codeCounts[0].level").value("WARNING"));

        assertThat(replacementFingerprint).isNotEqualTo(originalFingerprint);
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateClassifiesCompatibleSchemaDriftWhenReplacingUsedOperatorRef() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = eligibilityLibraryWithOptionalRegionInput();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility", original.operators().getFirst().fingerprint()));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "changed surface: change risk: COMPATIBLE_SCHEMA; input port 'inputs' schema changed")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("COMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeCategories[0]").value("COMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].surface").value("input"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].portName").value("inputs"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].compatibility").value("compatible"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].path").value(""))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("COMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].count").value(1));

        assertThat(replacement.operators().getFirst().fingerprint())
                .isNotEqualTo(original.operators().getFirst().fingerprint());
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenBreakingReplacementDoesNotAdvanceMajorVersionWithoutStoredUsage() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "1.1.0");
        registry.upsert(original);

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.version.breakingRequiresMajor"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/version"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.previousVersion").value("1.0.0"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.replacementVersion").value("1.1.0"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].surface").value("input"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].compatibility").value("breaking"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.schemaChanges[0].path").value("score"))
                .andExpect(jsonPath("$.impact.warningCount").value(1))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateRejectsVersionRegressionForChangedReplacement() throws Exception {
        OperatorLibrary original = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("integer"), "2.0.0");
        OperatorLibrary replacement = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "1.9.0");
        registry.upsert(original);

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].level").value("ERROR"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.version.regressed"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.previousVersion").value("2.0.0"))
                .andExpect(jsonPath("$.diagnostics[0].metadata.replacementVersion").value("1.9.0"))
                .andExpect(jsonPath("$.impact.errorCount").value(1))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateAllowsBreakingReplacementWhenMajorVersionAdvances() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithVersion(
                VisualCatalogTestSupport.eligibilityLibrary("string"), "2.0.0");
        registry.upsert(original);

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty())
                .andExpect(jsonPath("$.impact.diagnosticCount").value(0));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingUsedOperatorRefWithoutSavedFingerprint() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        registry.upsert(original);
        drafts.save(draftUsingOperatorWithoutFingerprint("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintSnapshotMissing"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value("Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by draft 'draft-1@1' node 'eligibility', but the draft has no saved operator fingerprint; review and resave the draft before execution."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(replacement.operators().get(0).fingerprint()).isNotEqualTo(original.operators().get(0).fingerprint());
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateRequiresWarningAcknowledgementBeforeStoringFingerprintDrift() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility", original.operators().getFirst().fingerprint()));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintDrift"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void createReimportStoresFingerprintDriftWhenWarningsAcknowledged() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility", original.operators().getFirst().fingerprint()));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .param("ackWarnings", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved fingerprint drift after impact review.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:eligibility"));

        assertThat(registry.find("risk-policy")).contains(replacement);
    }

    @Test
    void validateWarnsWhenReplacingPublishedOperatorRefWithDifferentFingerprint() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        String originalFingerprint = original.operators().getFirst().fingerprint();
        String replacementFingerprint = replacement.operators().getFirst().fingerprint();
        registry.upsert(original);
        publications.create(publicationUsingOperator("risk:eligibility", originalFingerprint));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.publicationOperatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("publication 'publication-1'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString(
                                "changed surface: change risk: BREAKING_SCHEMA; input port 'inputs' schema changed")))
                .andExpect(jsonPath("$.diagnostics[0].metadata.changeRisk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("frozen DSL")))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/publication-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("publication-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("publication-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.changeRiskCounts[0].risk").value("BREAKING_SCHEMA"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.library.publicationOperatorFingerprintDrift"));

        assertThat(replacementFingerprint).isNotEqualTo(originalFingerprint);
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenDeprecatingLibraryReferencedByPublishedArtifact() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary deprecated = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DEPRECATED);
        registry.upsert(original);
        publications.create(publicationUsingOperator("risk:eligibility", original.operators().getFirst().fingerprint()));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deprecated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.publicationLifecycleDeprecated"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' is being deprecated while publication 'publication-1' node 'eligibility' was authored with operatorRef 'risk:eligibility'. Existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/publication-1/nodes/0/operatorRef"))
                .andExpect(jsonPath("$.impact.publicationIds[0]").value("publication-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].publicationId").value("publication-1"))
                .andExpect(jsonPath("$.impact.publicationTargets[0].nodeIndex").value(0))
                .andExpect(jsonPath("$.impact.operatorRefs[0]").value("risk:eligibility"))
                .andExpect(jsonPath("$.impact.codeCounts[0].code")
                        .value("visual.library.publicationLifecycleDeprecated"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingLibraryRemovesOperatorRefUsedByPublication() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        publications.create(publicationUsingOperator("risk:eligibility", "published-fingerprint"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.publicationOperatorRemoved"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value("Operator library 'risk-policy' removes operatorRef 'risk:eligibility' used by publication 'publication-1' node 'eligibility'; existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."))
                .andExpect(jsonPath("$.diagnostics[0].target")
                        .value("/publications/publication-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateForceBypassesRemovedOperatorRefGuard() throws Exception {
        OperatorLibrary replacement = libraryWithScoreFactsOnly("2.0.0");
        registry.upsert(VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer"));
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved removing referenced operator after migration review.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:scoreFacts"));

        assertThat(registry.find("risk-policy")).contains(replacement);
    }

    @Test
    void updateForceBypassesDisabledLibraryReferenceGuard() throws Exception {
        OperatorLibrary disabled = libraryWithVersion(
                eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED), "2.0.0");
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .param("actor", "catalog-reviewer")
                        .param("reason", "Approved disabling referenced library after migration review.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(registry.find("risk-policy")).contains(disabled);
    }

    private static OperatorLibrary invalidArrayLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArray",
                "1.0.0",
                new OperatorDefinition.Display("Bad array", "Missing array item schema.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port(
                                "output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "items", Map.of("type", "array")
                                        )
                                )),
                                true,
                                "Output."
                        ))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:badArray", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-array",
                "Bad array",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static GraphDraft draftUsingOperator(String operatorRef) {
        return draftUsingOperator(operatorRef, "fingerprint");
    }

    private static GraphDraft draftUsingOperatorWithoutFingerprint(String operatorRef) {
        return draftUsingOperator(operatorRef, Map.of());
    }

    private static GraphDraft draftUsingOperator(String operatorRef, String fingerprint) {
        return draftUsingOperator(operatorRef, Map.of("eligibility", fingerprint));
    }

    private static VisualGraphPublication publicationUsingOperator(String operatorRef, String fingerprint) {
        GraphDraft draft = draftUsingOperator(operatorRef, Map.of("eligibility", fingerprint));
        return new VisualGraphPublication(
                "bloge.visualGraphPublication.v1",
                "publication-1",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(),
                Map.of("eligibility", fingerprint),
                Map.of(),
                "graph libraryImpact {}",
                null,
                null
        );
    }

    private static OperatorLibrary eligibilityLibraryWithStatus(String status) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                status,
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
    }

    private static GraphDraft draftUsingOperator(String operatorRef, Map<String, String> fingerprints) {
        return new GraphDraft(
                "bloge.visualGraphDraft.v1",
                "draft-1",
                0,
                "libraryImpact",
                "demo-tenant",
                "local",
                "browser",
                "DRAFT",
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        operatorRef,
                        "Eligibility",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(0, 0)
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                fingerprints
        );
    }

    private static OperatorLibrary libraryWithScoreFactsOnly() {
        return libraryWithScoreFactsOnly("1.0.0");
    }

    private static OperatorLibrary libraryWithScoreFactsOnly(String version) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                version,
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.scoreFactsOperator())
        );
    }

    private static OperatorLibrary libraryWithVersion(OperatorLibrary library, String version) {
        return new OperatorLibrary(
                library.schemaVersion(),
                library.libraryId(),
                library.displayName(),
                version,
                library.owner(),
                library.status(),
                library.operators()
        );
    }

    private static OperatorLibrary eligibilityLibraryWithOptionalRegionInput() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        Map<String, Object> properties = new java.util.LinkedHashMap<>(
                base.ports().inputs().getFirst().schema().schema());
        @SuppressWarnings("unchecked")
        Map<String, Object> inputProperties = new java.util.LinkedHashMap<>(
                (Map<String, Object>) properties.get("properties"));
        inputProperties.put("region", Map.of("type", "string"));
        properties.put("properties", inputProperties);
        OperatorDefinition.Port input = new OperatorDefinition.Port(
                base.ports().inputs().getFirst().name(),
                new SchemaEnvelope("json-schema", "2020-12", properties),
                base.ports().inputs().getFirst().required(),
                base.ports().inputs().getFirst().description()
        );
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(List.of(input), base.ports().outputs()),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.1.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private void useRuntimeJavaOperator(String operatorRef) {
        DefaultOperatorRegistry runtimeOperators = new DefaultOperatorRegistry();
        runtimeOperators.register(operatorRef, new EchoRuntimeOperator());
        OperatorLibraryAdminController controller = new OperatorLibraryAdminController(
                registry,
                new OperatorLibraryValidator(),
                drafts,
                publications,
                JavaOperatorInventoryProjector.forRegistry(runtimeOperators)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void useFailingRegistry(FailingMutation mutation) {
        registry = new FailingOperatorLibraryRegistry(mutation);
        OperatorLibraryAdminController controller = new OperatorLibraryAdminController(
                registry,
                new OperatorLibraryValidator(),
                drafts,
                publications
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static OperatorLibrary libraryWithOperatorRef(String libraryId, String operatorRef) {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                operatorRef,
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                "Runtime collision",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary libraryWithNativeLowering(String libraryId,
                                                             String operatorRef,
                                                             String executableOperatorRef) {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                operatorRef,
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering("native", executableOperatorRef, Map.of()),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                "Native lowering",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary libraryWithCapabilities(String libraryId,
                                                           OperatorDefinition.Capabilities capabilities) {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                capabilities,
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                "Capability risk",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary functionLibrary(String libraryId,
                                                    OperatorLibrary.BuiltInFunction function) {
        return functionLibrary(libraryId, "1.0.0", function);
    }

    private static OperatorLibrary functionLibrary(String libraryId,
                                                    String version,
                                                    OperatorLibrary.BuiltInFunction function) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                version,
                "risk-team",
                "ACTIVE",
                List.of(function),
                List.of()
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String namespace,
                                                            String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                namespace,
                name,
                "",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter("value", "any", null, false, false, "")),
                        new OperatorLibrary.ReturnValue(returnType, null, "")
                )),
                List.of()
        );
    }

    private static String asyncApiProjectionFixture() {
        return """
                asyncapi: '2.6.0'
                info:
                  title: Risk Events
                  version: 1.2.3
                  contact:
                    name: risk-platform
                channels:
                  /webhooks/credit-decision:
                    subscribe:
                      operationId: creditDecisionWebhook
                      x-bloge-source-kind: webhook
                      bindings:
                        http:
                          method: post
                      message:
                        name: CreditDecision
                        payload:
                          type: object
                          properties:
                            applicationId:
                              type: string
                            decision:
                              type: string
                          required:
                            - applicationId
                            - decision
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        payload:
                          $ref: '#/components/schemas/RiskCommand'
                components:
                  schemas:
                    RiskCommand:
                      type: object
                      properties:
                        commandId:
                          type: string
                        score:
                          type: integer
                      required:
                        - commandId
                """;
    }

    private static String asyncApiBatchProjectionFixture() {
        return """
                asyncapi: '2.6.0'
                info:
                  title: Risk Events
                  version: 1.2.3
                channels:
                  /webhooks/credit-decision:
                    subscribe:
                      operationId: creditDecisionWebhook
                      x-bloge-source-kind: webhook
                      bindings:
                        http:
                          method: post
                      message:
                        name: CreditDecision
                        payload:
                          type: object
                          properties:
                            applicationId:
                              type: string
                            decision:
                              type: string
                  risk.commands:
                    publish:
                      operationId: sendRiskCommand
                      message:
                        name: RiskCommand
                        payload:
                          type: object
                          properties:
                            commandId:
                              type: string
                  risk.audit:
                    subscribe:
                      operationId: riskAuditEvent
                      message:
                        name: RiskAudit
                        payload:
                          type: object
                          properties:
                            auditId:
                              type: string
                """;
    }

    private enum FailingMutation {
        UPSERT,
        RESTORE,
        DELETE
    }

    private static final class FailingOperatorLibraryRegistry extends InMemoryOperatorLibraryRegistry {
        private final FailingMutation mutation;

        private FailingOperatorLibraryRegistry(FailingMutation mutation) {
            this.mutation = mutation;
        }

        private void seed(OperatorLibrary library) {
            super.upsert(library, OperatorLibraryRevision.RevisionMetadata.empty());
        }

        @Override
        public synchronized OperatorLibrary upsert(OperatorLibrary library,
                                                   OperatorLibraryRevision.RevisionMetadata metadata) {
            if (mutation == FailingMutation.UPSERT) {
                throw new IllegalStateException("Injected operator library upsert failure");
            }
            return super.upsert(library, metadata);
        }

        @Override
        public synchronized OperatorLibrary restore(OperatorLibraryRevision revision,
                                                    OperatorLibraryRevision.RevisionMetadata metadata) {
            if (mutation == FailingMutation.RESTORE) {
                throw new IllegalStateException("Injected operator library restore failure");
            }
            return super.restore(revision, metadata);
        }

        @Override
        public synchronized void delete(String libraryId, OperatorLibraryRevision.RevisionMetadata metadata) {
            if (mutation == FailingMutation.DELETE) {
                throw new IllegalStateException("Injected operator library delete failure");
            }
            super.delete(libraryId, metadata);
        }
    }

    private static final class EchoRuntimeOperator implements Operator<Object, Object> {
        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.IDEMPOTENT;
        }

        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return input;
        }
    }
}
