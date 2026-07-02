package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the visual operator catalog.
 */
class DefaultVisualOperatorCatalogTest {

    @Test
    void projectsResourceDescriptorAsSchemaAwareVirtualOperator() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();

        OperatorDefinition operator = catalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)
                .orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("resource-descriptor");
        assertThat(operator.lowering().operatorRef()).isEqualTo("httpResource");
        assertThat(operator.ports().inputs().getFirst().schema().required()).containsExactly("applicantId");
        assertThat(operator.ports().outputs().getFirst().schema().properties()).containsKeys("score", "segment");
    }

    @Test
    void catalogOperatorsExposeSchemasAcceptedBySharedVisualSchemaGate() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (OperatorDefinition operator : catalog.list(OperatorCatalogQuery.all())) {
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                    operator.configSchema(),
                    "/operators/" + operator.operatorRef() + "/configSchema"));
            for (OperatorDefinition.Port port : operator.ports().inputs()) {
                diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                        port.schema(),
                        "/operators/" + operator.operatorRef() + "/ports/inputs/" + port.name() + "/schema"));
            }
            for (OperatorDefinition.Port port : operator.ports().outputs()) {
                diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                        port.schema(),
                        "/operators/" + operator.operatorRef() + "/ports/outputs/" + port.name() + "/schema"));
            }
        }

        assertThat(diagnostics)
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
    }

    @Test
    void includesUserProvidedOperatorLibraryDefinitions() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));

        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("user-library");
        assertThat(operator.lowering().mode()).isEqualTo("transform");
        assertThat(operator.ports().inputs().getFirst().schema().required())
                .containsExactly("score", "amount");
    }

    @Test
    void derivesRuntimeReadinessAsServerManagedOperatorMetadata() {
        OperatorDefinition executable = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition designOnly = VisualCatalogTestSupport.designOnlyEligibilityOperator("integer");
        OperatorDefinition forged = new OperatorDefinition(
                designOnly.schemaVersion(),
                designOnly.operatorRef(),
                designOnly.operatorVersion(),
                designOnly.fingerprint(),
                designOnly.display(),
                designOnly.source(),
                designOnly.ports(),
                designOnly.configSchema(),
                designOnly.capabilities(),
                designOnly.policy(),
                designOnly.lowering(),
                designOnly.diagnostics(),
                new OperatorDefinition.RuntimeReadiness(
                        "RUNTIME_EXECUTABLE",
                        "success",
                        true,
                        List.of("EXECUTABLE"),
                        "Forged executable",
                        "User supplied readiness should not be trusted.",
                        List.of()
                )
        );

        assertThat(executable.runtimeReadiness().state()).isEqualTo("RUNTIME_EXECUTABLE");
        assertThat(executable.runtimeReadiness().executable()).isTrue();
        assertThat(designOnly.runtimeReadiness().state()).isEqualTo("DESIGN_ONLY");
        assertThat(designOnly.runtimeReadiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(forged.runtimeReadiness().state()).isEqualTo("DESIGN_ONLY");
        assertThat(forged.runtimeReadiness().title()).isEqualTo("Design-only operator");
    }

    @Test
    void catalogSearchMatchesSchemaFieldsAndTypes() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition configured = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                SchemaEnvelope.object(Map.of("threshold", Map.of("type", "number")), List.of()),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(configured)
        ));

        assertThat(catalog.list(search("inputs.score")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(search("output.eligible")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(search("config.threshold")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(search("integer")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(search("risk inputs.score config.threshold integer")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(search("risk inputs.score missingField")))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:eligibility");
    }

    @Test
    void filtersOperatorsBySourceLoweringAndCapabilityFacets() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library("risk-executable", "ACTIVE",
                VisualCatalogTestSupport.eligibilityOperator("integer")));
        libraries.upsert(library("risk-design", "ACTIVE", schemaOnlyIntakeOperator()));
        libraries.upsert(library("risk-governed", "ACTIVE", secretMutationOperator()));
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("normalizeText", new NormalizeTextOperator());
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                new SingleResourceRegistry(VisualCatalogTestSupport.loanApplicantDescriptor()),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries,
                JavaOperatorInventoryProjector.forRegistry(registry)
        );

        assertThat(catalog.list(facetQuery(List.of("user-library"), List.of(), List.of())))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility", "risk:designIntake", "risk:writeAudit")
                .doesNotContain("normalizeText", "resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(catalog.list(facetQuery(List.of("resource-descriptor"), List.of(), List.of())))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(catalog.list(facetQuery(List.of("java-operator"), List.of(), List.of())))
                .extracting(OperatorDefinition::operatorRef)
                .contains("normalizeText")
                .doesNotContain("risk:eligibility");
        assertThat(catalog.list(facetQuery(List.of(), List.of("design"), List.of())))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:designIntake");
        assertThat(catalog.list(facetQuery(List.of("user-library"), List.of("transform"), List.of())))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility", "risk:writeAudit");
        assertThat(catalog.list(facetQuery(List.of(), List.of(), List.of("design-only"))))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:designIntake");
        assertThat(catalog.list(facetQuery(List.of(), List.of(), List.of("runtime-executable"))))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility", "risk:writeAudit", "normalizeText",
                        "resource:" + VisualCatalogTestSupport.RESOURCE_ID)
                .doesNotContain("risk:designIntake");
        assertThat(catalog.list(facetQuery(List.of(), List.of(), List.of("requires-secret", "external-effect",
                "non-idempotent"))))
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:writeAudit");
    }

    @Test
    void skipsNullOperatorsFromStoredLibraryDefinitions() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                java.util.Arrays.asList(null, VisualCatalogTestSupport.eligibilityOperator("integer"))
        ));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.diagnostics(OperatorCatalogQuery.all()))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.catalog.operatorHiddenMalformed");
                    assertThat(diagnostic.target()).isEqualTo("/libraries/risk-policy/operators/0");
                });
    }

    @Test
    void skipsOperatorsWithNullPortsFromStoredLibraryDefinitions() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition malformed = new OperatorDefinition(
                base.schemaVersion(),
                "risk:malformedPorts",
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
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(malformed)
        ));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:malformedPorts");
        assertThat(catalog.diagnostics(OperatorCatalogQuery.all()))
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.catalog.operatorHiddenMalformed");
                    assertThat(diagnostic.message()).isEqualTo(
                            "Operator 'risk:malformedPorts' from library 'risk-policy' has a null outputs port hidden from the visual catalog.");
                    assertThat(diagnostic.target()).isEqualTo("/libraries/risk-policy/operators/0/ports/outputs/0");
                });
    }

    @Test
    void hiddenMalformedOperatorDiagnosticsRespectCatalogQueryScope() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer",
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("local"), List.of("browser")));
        OperatorDefinition malformed = new OperatorDefinition(
                base.schemaVersion(),
                "risk:scopedMalformedPorts",
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
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(malformed)
        ));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.diagnostics(new OperatorCatalogQuery("", List.of(), false, false,
                "tenant-b", "local", "browser"))).isEmpty();
        assertThat(catalog.diagnostics(new OperatorCatalogQuery("unrelated", List.of(), false, false,
                "tenant-a", "local", "browser"))).isEmpty();
        assertThat(catalog.diagnostics(new OperatorCatalogQuery("", List.of("risk"), false, false,
                "tenant-a", "local", "browser")))
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.catalog.operatorHiddenMalformed");
    }

    @Test
    void includesJavaOperatorsFromRuntimeRegistry() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("normalizeText", new NormalizeTextOperator());
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                new JavaOperatorInventoryProjector(registry)
        );

        OperatorDefinition operator = catalog.find("normalizeText").orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("java-operator");
        assertThat(operator.lowering().mode()).isEqualTo("native");
        assertThat(operator.lowering().operatorRef()).isEqualTo("normalizeText");
        assertThat(operator.ports().inputs().getFirst().schema().properties()).containsKey("raw");
        assertThat(operator.ports().outputs().getFirst().schema().properties()).containsKey("value");
    }

    @Test
    void hidesStoredLibraryOperatorWhenRuntimeJavaOperatorOwnsSameRef() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("risk:eligibility", new NormalizeTextOperator());
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries,
                JavaOperatorInventoryProjector.forRegistry(registry)
        );

        List<OperatorDefinition> matches = catalog.list(OperatorCatalogQuery.all()).stream()
                .filter(operator -> operator.operatorRef().equals("risk:eligibility"))
                .toList();

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().source().kind()).isEqualTo("java-operator");
        assertThat(matches.getFirst().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.catalog.operatorRefShadowed");
                    assertThat(diagnostic.message()).isEqualTo(
                            "OperatorRef 'risk:eligibility' from imported operator library is hidden because runtime Java operator already owns this catalog key.");
                });
        assertThat(catalog.find("risk:eligibility"))
                .map(operator -> operator.source().kind())
                .contains("java-operator");
    }

    private static OperatorCatalogQuery search(String value) {
        return new OperatorCatalogQuery(value, List.of(), false, false);
    }

    private static OperatorCatalogQuery facetQuery(List<String> sourceKinds,
                                                   List<String> loweringModes,
                                                   List<String> capabilities) {
        return new OperatorCatalogQuery("", List.of(), false, false, "", "", "",
                sourceKinds, loweringModes, capabilities);
    }

    @Test
    void includesPublishedVisualGraphsAsReusableSubgraphOperators() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(publishedEligibilityGraph());
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.empty(),
                publications,
                new VisualGraphPublicationOperatorProjector()
        );

        OperatorDefinition operator = catalog.find("publication:pub-eligibility").orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("visual-publication");
        assertThat(operator.source().virtual()).isTrue();
        assertThat(operator.display().tags()).contains("publication", "subgraph", "visual-graph");
        assertThat(operator.policy().tenants()).containsExactly("tenant-a");
        assertThat(operator.policy().namespaces()).containsExactly("risk");
        assertThat(operator.policy().environments()).containsExactly("prod");
        assertThat(operator.capabilities().effect()).isEqualTo("EXTERNAL");
        assertThat(operator.capabilities().idempotency()).isEqualTo("UNKNOWN");
        assertThat(operator.capabilities().streaming()).isTrue();
        assertThat(operator.capabilities().durable()).isTrue();
        assertThat(operator.capabilities().requiresSecrets()).isTrue();
        assertThat(operator.lowering().mode()).isEqualTo("native");
        assertThat(operator.lowering().operatorRef()).isEqualTo(VisualGraphPublicationOperator.NAME);
        assertThat(operator.lowering().parameters()).containsEntry("publicationId", "pub-eligibility");
        assertThat(operator.ports().inputs().getFirst().schema().required())
                .containsExactlyInAnyOrder("score", "amount");
        assertThat(operator.ports().outputs().getFirst().schema().schema())
                .containsEntry("type", "boolean");

        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), true, false)))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("publication:pub-eligibility");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "tenant-a", "risk", "prod")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("publication:pub-eligibility");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "tenant-a", "risk", "local")))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("publication:pub-eligibility");
    }

    @Test
    void excludesDesignPublicationsFromReusableSubgraphOperators() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication executable = publishedEligibilityGraph();
        publications.create(VisualGraphPublication.design(
                executable.draft(),
                executable.operatorSnapshots(),
                executable.validation(),
                new DslGenerationResult(false, "", List.of(VisualDiagnostic.error(
                        "visual.codegen.designOnlyOperator",
                        "Schema-only operator cannot lower to executable BLOGE DSL.",
                        "/nodes/eligibility/operatorRef"
                )))
        ).withIdentity("pub-design-only", null));
        DefaultVisualOperatorCatalog catalog = publicationCatalog(publications);

        assertThat(catalog.find("publication:pub-design-only")).isEmpty();
        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("publication:pub-design-only");
    }

    @Test
    void projectsPublishedVisualGraphSelectedNamedOutputPathSchema() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(publishedScoreFactsGraph("pub-score-facts", "facts.score"));
        DefaultVisualOperatorCatalog catalog = publicationCatalog(publications);

        OperatorDefinition operator = catalog.find("publication:pub-score-facts").orElseThrow();

        assertThat(operator.ports().outputs().getFirst().schema().schema())
                .containsEntry("type", "integer");
    }

    @Test
    void projectsPublishedVisualGraphWithNonCanonicalArrayIndexOutputPathAsOpaque() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(publishedListFactsGraph("pub-list-facts-bad-index", "items.+1"));
        DefaultVisualOperatorCatalog catalog = publicationCatalog(publications);

        Map<String, Object> schema = catalog.find("publication:pub-list-facts-bad-index")
                .orElseThrow()
                .ports()
                .outputs()
                .getFirst()
                .schema()
                .schema();

        assertThat(schema).isEqualTo(SchemaEnvelope.opaque().schema());
        assertThat(schema).doesNotContainEntry("type", "integer");
    }

    @Test
    void projectsPublishedVisualGraphWholeMultiOutputAsPortObjectSchema() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(publishedScoreFactsGraph("pub-score-facts-whole", ""));
        DefaultVisualOperatorCatalog catalog = publicationCatalog(publications);

        Map<String, Object> schema = catalog.find("publication:pub-score-facts-whole")
                .orElseThrow()
                .ports()
                .outputs()
                .getFirst()
                .schema()
                .schema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(schema).containsEntry("type", "object");
        assertThat(required).containsExactlyInAnyOrder("summary", "facts");
        assertThat(properties).containsKeys("summary", "facts");
    }

    @Test
    void computesStableFingerprintFromSchemaAndLoweringMetadata() {
        OperatorDefinition integerScore = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition sameDefinition = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition stringScore = VisualCatalogTestSupport.eligibilityOperator("string");

        assertThat(integerScore.fingerprint()).startsWith("sha256:");
        assertThat(integerScore.fingerprint()).hasSize(71);
        assertThat(integerScore.fingerprint()).isEqualTo(sameDefinition.fingerprint());
        assertThat(integerScore.fingerprint()).isNotEqualTo(stringScore.fingerprint());
    }

    @Test
    void ignoresUserSuppliedFingerprintAndRecomputesServerSide() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");

        OperatorDefinition forged = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                "sha256:forged",
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.lowering(),
                base.diagnostics()
        );

        assertThat(forged.fingerprint()).isEqualTo(base.fingerprint());
    }

    @Test
    void filtersOperatorsByAuthoringScopePolicy() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer",
                        new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("prod"))));

        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "demo-tenant", "local", "prod")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "demo-tenant", "local", "browser")))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:eligibility");
    }

    @Test
    void filtersUserLibraryOperatorsByLifecycleStatus() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library("active-policy", "ACTIVE", VisualCatalogTestSupport.eligibilityOperator("integer")));
        libraries.upsert(library("deprecated-policy", "DEPRECATED", VisualCatalogTestSupport.numericPassOperator()));
        libraries.upsert(library("disabled-policy", "DISABLED", VisualCatalogTestSupport.scoreFactsOperator()));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility")
                .doesNotContain("risk:numericPass", "risk:scoreFacts");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, true)))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility", "risk:numericPass")
                .doesNotContain("risk:scoreFacts");
        assertThat(catalog.find("risk:numericPass").orElseThrow().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lifecycle.deprecated");
                    assertThat(diagnostic.message()).contains("deprecated operator library 'deprecated-policy'");
                    assertThat(diagnostic.metadata())
                            .containsEntry("libraryId", "deprecated-policy")
                            .containsEntry("libraryStatus", "DEPRECATED")
                            .containsEntry("operatorRef", "risk:numericPass");
                });
        assertThat(catalog.find("risk:scoreFacts")).isEmpty();
    }

    @Test
    void filtersResourceBackedOperatorsByContractLifecycleStatus() {
        DefaultVisualOperatorCatalog deprecatedCatalog = catalogWithResourceContractStatus("DEPRECATED");
        DefaultVisualOperatorCatalog disabledCatalog = catalogWithResourceContractStatus("DISABLED");

        assertThat(deprecatedCatalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(deprecatedCatalog.list(new OperatorCatalogQuery("", List.of(), true, true)))
                .extracting(OperatorDefinition::operatorRef)
                .contains("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(deprecatedCatalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)).isPresent();
        assertThat(deprecatedCatalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)
                .orElseThrow()
                .diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.lifecycle.deprecated");
                    assertThat(diagnostic.message()).contains("Resource design contract")
                            .contains("deprecated");
                    assertThat(diagnostic.metadata())
                            .containsEntry("resourceId", VisualCatalogTestSupport.RESOURCE_ID)
                            .containsEntry("contractStatus", "DEPRECATED");
                });

        assertThat(disabledCatalog.list(new OperatorCatalogQuery("", List.of(), true, true)))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(disabledCatalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)).isEmpty();
    }

    @Test
    void resolvesDeprecatedLibraryOperatorsForStoredDrafts() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library("deprecated-policy", "DEPRECATED", VisualCatalogTestSupport.numericPassOperator()));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:numericPass");
        assertThat(catalog.find("risk:numericPass")).isPresent();
    }

    private static OperatorLibrary library(String libraryId, String status, OperatorDefinition operator) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                status,
                List.of(operator)
        );
    }

    private static OperatorDefinition schemaOnlyIntakeOperator() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        return new OperatorDefinition(
                base.schemaVersion(),
                "risk:designIntake",
                base.operatorVersion(),
                new OperatorDefinition.Display("Design intake", "Schema-only intake contract.",
                        List.of("risk", "design")),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                base.diagnostics()
        );
    }

    private static OperatorDefinition secretMutationOperator() {
        OperatorDefinition base = VisualCatalogTestSupport.numericPassOperator();
        return new OperatorDefinition(
                base.schemaVersion(),
                "risk:writeAudit",
                base.operatorVersion(),
                new OperatorDefinition.Display("Write audit", "Writes an external audit event.",
                        List.of("risk", "audit")),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("WRITE_EXTERNAL", "NON_IDEMPOTENT",
                        false, false, true),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
    }

    private static DefaultVisualOperatorCatalog publicationCatalog(
            InMemoryVisualGraphPublicationRepository publications) {
        return new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.empty(),
                publications,
                new VisualGraphPublicationOperatorProjector()
        );
    }

    private static DefaultVisualOperatorCatalog catalogWithResourceContractStatus(String status) {
        ResourceDescriptor descriptor = VisualCatalogTestSupport.loanApplicantDescriptor();
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(new ResourceDesignContract(
                "contract:" + VisualCatalogTestSupport.RESOURCE_ID,
                VisualCatalogTestSupport.RESOURCE_ID,
                "Loan applicant profile",
                "Reads applicant facts.",
                List.of("loan", "applicant"),
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer")
                ), List.of()),
                Map.of(),
                status
        ));
        return new DefaultVisualOperatorCatalog(
                new SingleResourceRegistry(descriptor),
                contracts,
                new ResourceVirtualOperatorProjector()
        );
    }

    private static VisualGraphPublication publishedEligibilityGraph() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition eligibility = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", true, true, true),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        GraphDraft draft = new GraphDraft(
                "",
                "draft-eligibility",
                7,
                "publishedEligibility",
                "tenant-a",
                "risk",
                "prod",
                "",
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ), List.of("score", "amount")),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        eligibility.operatorRef(),
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "eligible"),
                Map.of("eligibility", eligibility.fingerprint())
        );
        String dsl = """
                graph publishedEligibility {
                  transform eligibility {
                    eligible = ctx.score >= 700 && ctx.amount <= 300000
                    ruleId = "ELIGIBILITY_V1"
                  }
                }
                """;
        return new VisualGraphPublication(
                "",
                "pub-eligibility",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(eligibility),
                draft.operatorFingerprints(),
                Map.of(),
                dsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, dsl, List.of())
        );
    }

    private static VisualGraphPublication publishedScoreFactsGraph(String publicationId, String outputPath) {
        OperatorDefinition scoreFacts = VisualCatalogTestSupport.scoreFactsOperator();
        GraphDraft draft = new GraphDraft(
                "",
                "draft-score-facts",
                3,
                "publishedScoreFacts",
                "tenant-a",
                "risk",
                "prod",
                "",
                SchemaEnvelope.object(Map.of(), List.of()),
                List.of(new GraphDraft.DraftNode(
                        "scoreFacts",
                        scoreFacts.operatorRef(),
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreFacts", outputPath),
                Map.of("scoreFacts", scoreFacts.fingerprint())
        );
        String dsl = """
                graph publishedScoreFacts {
                  node scoreFacts : riskScoreFacts {
                    input {
                    }
                  }
                }
                """;
        return new VisualGraphPublication(
                "",
                publicationId,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(scoreFacts),
                draft.operatorFingerprints(),
                Map.of(),
                dsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, dsl, List.of())
        );
    }

    private static VisualGraphPublication publishedListFactsGraph(String publicationId, String outputPath) {
        OperatorDefinition listFacts = VisualCatalogTestSupport.listFactsOperator("integer");
        GraphDraft draft = new GraphDraft(
                "",
                "draft-list-facts",
                3,
                "publishedListFacts",
                "tenant-a",
                "risk",
                "prod",
                "",
                SchemaEnvelope.object(Map.of(), List.of()),
                List.of(new GraphDraft.DraftNode(
                        "listFacts",
                        listFacts.operatorRef(),
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("listFacts", outputPath),
                Map.of("listFacts", listFacts.fingerprint())
        );
        String dsl = """
                graph publishedListFacts {
                  node listFacts : riskListFacts {
                    input {
                    }
                  }
                }
                """;
        return new VisualGraphPublication(
                "",
                publicationId,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(listFacts),
                draft.operatorFingerprints(),
                Map.of(),
                dsl,
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, dsl, List.of())
        );
    }

    private record NormalizeInput(String raw) {
    }

    private record NormalizeOutput(String value) {
    }

    private static final class NormalizeTextOperator implements Operator<NormalizeInput, NormalizeOutput> {
        @Override
        public NormalizeOutput execute(NormalizeInput input, OperatorContext ctx) {
            return new NormalizeOutput(input.raw().trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record SingleResourceRegistry(ResourceDescriptor descriptor) implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (descriptor.resourceId().equals(resourceId)) {
                return descriptor;
            }
            throw new com.leanowtech.bloge.gateway.exception.ResourceNotFoundException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptor.resourceId().equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of(descriptor);
        }
    }
}
