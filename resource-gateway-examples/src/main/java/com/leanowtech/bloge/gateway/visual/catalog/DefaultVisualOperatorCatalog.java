package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.asset.InMemoryVisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Default catalog combining native visual operators and resource-backed virtual operators.
 */
@Service
public class DefaultVisualOperatorCatalog implements VisualOperatorCatalog {

    private final ResourceRegistry resourceRegistry;
    private final ResourceDesignContractRegistry contractRegistry;
    private final ResourceVirtualOperatorProjector projector;
    private final OperatorLibraryRegistry libraryRegistry;
    private final JavaOperatorInventoryProjector javaOperatorProjector;
    private final VisualGraphPublicationRepository publicationRepository;
    private final VisualGraphPublicationOperatorProjector publicationProjector;
    private final VisualRuntimeBindingImplementationRepository runtimeBindingRepository;
    private final VisualRuntimeAdapterActivationRepository runtimeAdapterActivationRepository;

    /**
     * @param resourceRegistry resource descriptor registry
     * @param contractRegistry visual design contract registry
     * @param projector resource operator projector
     */
    @Autowired
    public DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                        ResourceDesignContractRegistry contractRegistry,
                                        ResourceVirtualOperatorProjector projector,
                                        OperatorLibraryRegistry libraryRegistry,
                                        JavaOperatorInventoryProjector javaOperatorProjector,
                                        VisualGraphPublicationRepository publicationRepository,
                                        VisualGraphPublicationOperatorProjector publicationProjector,
                                        VisualRuntimeBindingImplementationRepository runtimeBindingRepository,
                                        VisualRuntimeAdapterActivationRepository runtimeAdapterActivationRepository) {
        this.resourceRegistry = resourceRegistry;
        this.contractRegistry = contractRegistry;
        this.projector = projector;
        this.libraryRegistry = libraryRegistry;
        this.javaOperatorProjector = javaOperatorProjector == null
                ? JavaOperatorInventoryProjector.empty()
                : javaOperatorProjector;
        this.publicationRepository = publicationRepository == null
                ? new InMemoryVisualGraphPublicationRepository()
                : publicationRepository;
        this.publicationProjector = publicationProjector == null
                ? new VisualGraphPublicationOperatorProjector()
                : publicationProjector;
        this.runtimeBindingRepository = runtimeBindingRepository == null
                ? new InMemoryVisualRuntimeBindingImplementationRepository()
                : runtimeBindingRepository;
        this.runtimeAdapterActivationRepository = runtimeAdapterActivationRepository == null
                ? new InMemoryVisualRuntimeAdapterActivationRepository()
                : runtimeAdapterActivationRepository;
    }

    public DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                        ResourceDesignContractRegistry contractRegistry,
                                        ResourceVirtualOperatorProjector projector,
                                        OperatorLibraryRegistry libraryRegistry,
                                        JavaOperatorInventoryProjector javaOperatorProjector,
                                        VisualGraphPublicationRepository publicationRepository,
                                        VisualGraphPublicationOperatorProjector publicationProjector,
                                        VisualRuntimeBindingImplementationRepository runtimeBindingRepository) {
        this(resourceRegistry, contractRegistry, projector, libraryRegistry, javaOperatorProjector,
                publicationRepository, publicationProjector, runtimeBindingRepository,
                new InMemoryVisualRuntimeAdapterActivationRepository());
    }

    public DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                        ResourceDesignContractRegistry contractRegistry,
                                        ResourceVirtualOperatorProjector projector,
                                        OperatorLibraryRegistry libraryRegistry,
                                        JavaOperatorInventoryProjector javaOperatorProjector,
                                        VisualGraphPublicationRepository publicationRepository,
                                        VisualGraphPublicationOperatorProjector publicationProjector) {
        this(resourceRegistry, contractRegistry, projector, libraryRegistry, javaOperatorProjector,
                publicationRepository, publicationProjector, new InMemoryVisualRuntimeBindingImplementationRepository());
    }

    DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                 ResourceDesignContractRegistry contractRegistry,
                                 ResourceVirtualOperatorProjector projector) {
        this(resourceRegistry, contractRegistry, projector, OperatorLibraryRegistry.empty(),
                JavaOperatorInventoryProjector.empty(), new InMemoryVisualGraphPublicationRepository(),
                new VisualGraphPublicationOperatorProjector());
    }

    DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                 ResourceDesignContractRegistry contractRegistry,
                                 ResourceVirtualOperatorProjector projector,
                                 OperatorLibraryRegistry libraryRegistry) {
        this(resourceRegistry, contractRegistry, projector, libraryRegistry, JavaOperatorInventoryProjector.empty(),
                new InMemoryVisualGraphPublicationRepository(), new VisualGraphPublicationOperatorProjector());
    }

    public DefaultVisualOperatorCatalog(ResourceRegistry resourceRegistry,
                                        ResourceDesignContractRegistry contractRegistry,
                                        ResourceVirtualOperatorProjector projector,
                                        OperatorLibraryRegistry libraryRegistry,
                                        JavaOperatorInventoryProjector javaOperatorProjector) {
        this(resourceRegistry, contractRegistry, projector, libraryRegistry, javaOperatorProjector,
                new InMemoryVisualGraphPublicationRepository(), new VisualGraphPublicationOperatorProjector());
    }

    @Override
    public List<OperatorDefinition> list(OperatorCatalogQuery query) {
        OperatorCatalogQuery effectiveQuery = query == null ? OperatorCatalogQuery.all() : query;
        List<OperatorDefinition> operators = new ArrayList<>();
        if (!effectiveQuery.resourceOnly()) {
            operators.addAll(nativeOperators());
            operators.addAll(javaOperatorProjector.project());
            operators.addAll(libraryOperators(effectiveQuery.includeDeprecated()));
            for (VisualGraphPublication publication : publicationRepository.all()) {
                if (publication.executable()) {
                    operators.add(publicationProjector.project(publication));
                }
            }
        }
        for (ResourceDescriptor descriptor : resourceRegistry.all()) {
            Optional<ResourceDesignContract> contract = contractRegistry.findByResourceId(descriptor.resourceId());
            if (contract.isPresent() && !contract.get().visibleInCatalog(effectiveQuery.includeDeprecated())) {
                continue;
            }
            operators.add(projector.project(descriptor, contract));
        }
        return uniqueByOperatorRef(operators).stream()
                .filter(operator -> matches(operator, effectiveQuery))
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
    }

    @Override
    public List<VisualDiagnostic> diagnostics(OperatorCatalogQuery query) {
        OperatorCatalogQuery effectiveQuery = query == null ? OperatorCatalogQuery.all() : query;
        if (effectiveQuery.resourceOnly()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (OperatorLibrary library : libraryRegistry.all()) {
            if (!library.visibleInCatalog(effectiveQuery.includeDeprecated())) {
                continue;
            }
            if (!effectiveQuery.operatorLibraryIds().isEmpty()
                    && !effectiveQuery.operatorLibraryIds().contains(library.libraryId())) {
                continue;
            }
            for (int i = 0; i < library.operators().size(); i++) {
                OperatorDefinition operator = library.operators().get(i);
                if (operator == null) {
                    if (!queryCanMatchNullOperator(effectiveQuery)) {
                        continue;
                    }
                    diagnostics.add(VisualDiagnostic.warning("visual.catalog.operatorHiddenMalformed",
                            "Operator library '%s' contains a null operator entry hidden from the visual catalog."
                                    .formatted(library.libraryId()),
                            "/libraries/%s/operators/%d".formatted(library.libraryId(), i)));
                    continue;
                }
                OperatorDefinition ownedOperator = withLibrarySource(library, operator);
                if (!matches(ownedOperator, effectiveQuery)) {
                    continue;
                }
                hiddenMalformedPortDiagnostic(library, operator, i).ifPresent(diagnostics::add);
            }
        }
        return List.copyOf(diagnostics);
    }

    @Override
    public Map<String, String> operatorLibraryIdsByOperatorRef(boolean includeDeprecated) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (OperatorDefinition operator : list(new OperatorCatalogQuery("", List.of(), false, includeDeprecated))) {
            String libraryId = operator.source().libraryId();
            if (libraryId.isBlank()) {
                continue;
            }
            owners.putIfAbsent(operator.operatorRef(), libraryId);
        }
        return Map.copyOf(owners);
    }

    @Override
    public List<OperatorRuntimeBindingProjection> runtimeBindingProjections(OperatorCatalogQuery query,
                                                                            List<OperatorDefinition> operators) {
        List<OperatorDefinition> safeOperators = operators == null ? list(query) : operators;
        return OperatorRuntimeBindingProjection.from(
                safeOperators,
                activeBindingsByOperatorRef(),
                activeAdapterActivationsByBindingId()
        );
    }

    @Override
    public Optional<OperatorDefinition> find(String operatorRef) {
        if (operatorRef == null || operatorRef.isBlank()) {
            return Optional.empty();
        }
        return list(new OperatorCatalogQuery("", List.of(), false, true)).stream()
                .filter(operator -> operator.operatorRef().equals(operatorRef))
                .findFirst();
    }

    private static boolean queryCanMatchNullOperator(OperatorCatalogQuery query) {
        return query.search().isBlank()
                && query.tags().isEmpty()
                && query.sourceKinds().isEmpty()
                && query.loweringModes().isEmpty()
                && query.capabilities().isEmpty()
                && query.runtimeReadinessStates().isEmpty();
    }

    private Map<String, VisualRuntimeBindingImplementationBinding> activeBindingsByOperatorRef() {
        Map<String, VisualRuntimeBindingImplementationBinding> bindings = new LinkedHashMap<>();
        for (VisualRuntimeBindingImplementationBinding binding : runtimeBindingRepository.all()) {
            if (binding == null || !binding.bound() || binding.operatorRef().isBlank()) {
                continue;
            }
            bindings.putIfAbsent(binding.operatorRef(), binding);
        }
        return Map.copyOf(bindings);
    }

    private Map<String, VisualRuntimeAdapterActivation> activeAdapterActivationsByBindingId() {
        Map<String, VisualRuntimeAdapterActivation> activations = new LinkedHashMap<>();
        for (VisualRuntimeAdapterActivation activation : runtimeAdapterActivationRepository.all()) {
            if (activation == null || !activation.active() || activation.bindingId().isBlank()) {
                continue;
            }
            activations.putIfAbsent(activation.bindingId(), activation);
        }
        return Map.copyOf(activations);
    }

    private List<OperatorDefinition> libraryOperators(boolean includeDeprecated) {
        List<OperatorDefinition> operators = new ArrayList<>();
        for (OperatorLibrary library : libraryRegistry.all()) {
            if (!library.visibleInCatalog(includeDeprecated)) {
                continue;
            }
            for (int i = 0; i < library.operators().size(); i++) {
                OperatorDefinition operator = library.operators().get(i);
                if (operator == null || !hasConcretePorts(operator)) {
                    continue;
                }
                operators.add(withLibraryLifecycleDiagnostic(library, withLibrarySource(library, operator), i));
            }
        }
        return operators;
    }

    private static OperatorDefinition withLibrarySource(OperatorLibrary library, OperatorDefinition operator) {
        OperatorDefinition.Source source = operator.source();
        String libraryId = library == null ? "" : library.libraryId();
        if (libraryId.equals(source.libraryId())) {
            return operator;
        }
        OperatorDefinition.Source ownedSource = new OperatorDefinition.Source(
                source.kind(),
                source.resourceId(),
                source.method(),
                source.urlTemplate(),
                source.virtual(),
                libraryId
        );
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.fingerprint(),
                operator.display(),
                ownedSource,
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                operator.diagnostics(),
                operator.runtimeReadiness()
        );
    }

    private static boolean hasConcretePorts(OperatorDefinition operator) {
        return operator.ports().inputs().stream().allMatch(java.util.Objects::nonNull)
                && operator.ports().outputs().stream().allMatch(java.util.Objects::nonNull);
    }

    private static OperatorDefinition withLibraryLifecycleDiagnostic(OperatorLibrary library,
                                                                     OperatorDefinition operator,
                                                                     int operatorIndex) {
        if (!OperatorLibrary.STATUS_DEPRECATED.equals(library.status())) {
            return operator;
        }
        return withDiagnostic(operator, VisualDiagnostic.warning(
                "visual.operator.lifecycle.deprecated",
                "Operator '%s' comes from deprecated operator library '%s'; existing drafts can still be reviewed, but production promotion should migrate to an active operator definition."
                        .formatted(operator.operatorRef(), library.libraryId()),
                "/libraries/%s/operators/%d".formatted(library.libraryId(), operatorIndex),
                Map.of(
                        "libraryId", library.libraryId(),
                        "libraryStatus", library.status(),
                        "operatorRef", operator.operatorRef()
                )
        ));
    }

    private static Optional<VisualDiagnostic> hiddenMalformedPortDiagnostic(OperatorLibrary library,
                                                                            OperatorDefinition operator,
                                                                            int operatorIndex) {
        for (int i = 0; i < operator.ports().inputs().size(); i++) {
            if (operator.ports().inputs().get(i) == null) {
                return Optional.of(hiddenMalformedPortDiagnostic(library, operator, operatorIndex,
                        "inputs", i));
            }
        }
        for (int i = 0; i < operator.ports().outputs().size(); i++) {
            if (operator.ports().outputs().get(i) == null) {
                return Optional.of(hiddenMalformedPortDiagnostic(library, operator, operatorIndex,
                        "outputs", i));
            }
        }
        return Optional.empty();
    }

    private static VisualDiagnostic hiddenMalformedPortDiagnostic(OperatorLibrary library,
                                                                  OperatorDefinition operator,
                                                                  int operatorIndex,
                                                                  String direction,
                                                                  int portIndex) {
        return VisualDiagnostic.warning("visual.catalog.operatorHiddenMalformed",
                "Operator '%s' from library '%s' has a null %s port hidden from the visual catalog."
                        .formatted(operator.operatorRef(), library.libraryId(), direction),
                "/libraries/%s/operators/%d/ports/%s/%d".formatted(library.libraryId(), operatorIndex,
                        direction, portIndex));
    }

    private static List<OperatorDefinition> uniqueByOperatorRef(List<OperatorDefinition> operators) {
        Map<String, OperatorDefinition> unique = new LinkedHashMap<>();
        for (OperatorDefinition operator : operators) {
            OperatorDefinition retained = unique.get(operator.operatorRef());
            if (retained == null) {
                unique.put(operator.operatorRef(), operator);
                continue;
            }
            unique.put(operator.operatorRef(), withDiagnostic(retained, VisualDiagnostic.warning(
                    "visual.catalog.operatorRefShadowed",
                    "OperatorRef '%s' from %s is hidden because %s already owns this catalog key."
                            .formatted(operator.operatorRef(), sourceLabel(operator), sourceLabel(retained)),
                    "/operators/" + operator.operatorRef()
            )));
        }
        return List.copyOf(unique.values());
    }

    private static OperatorDefinition withDiagnostic(OperatorDefinition operator, VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(operator.diagnostics());
        diagnostics.add(diagnostic);
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.display(),
                operator.source(),
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                diagnostics
        );
    }

    private static String sourceLabel(OperatorDefinition operator) {
        String kind = operator.source().kind();
        if ("resource-descriptor".equals(kind)) {
            return "resource descriptor '%s'".formatted(operator.source().resourceId());
        }
        if ("visual-publication".equals(kind)) {
            return "visual publication operator";
        }
        if ("java-operator".equals(kind)
                || "java-streaming-operator".equals(kind)
                || "java-suspendable-operator".equals(kind)) {
            return "runtime Java operator";
        }
        if ("user-library".equals(kind)) {
            return "imported operator library";
        }
        return "source kind '%s'".formatted(kind);
    }

    private static boolean matches(OperatorDefinition operator, OperatorCatalogQuery query) {
        if (!query.search().isBlank()) {
            String haystack = operatorSearchText(operator).toLowerCase(Locale.ROOT);
            if (searchTokens(query.search()).stream().anyMatch(token -> !haystack.contains(token))) {
                return false;
            }
        }
        if (!query.tags().isEmpty() && !operator.display().tags().containsAll(query.tags())) {
            return false;
        }
        if (!query.sourceKinds().isEmpty()
                && !query.sourceKinds().contains(normalizeFacetValue(operator.source().kind()))) {
            return false;
        }
        if (!query.operatorLibraryIds().isEmpty()
                && !query.operatorLibraryIds().contains(operator.source().libraryId())) {
            return false;
        }
        if (!query.loweringModes().isEmpty()
                && !query.loweringModes().contains(normalizeFacetValue(operator.lowering().mode()))) {
            return false;
        }
        if (!query.capabilities().isEmpty()
                && query.capabilities().stream().anyMatch(capability -> !matchesCapability(operator, capability))) {
            return false;
        }
        if (!query.runtimeReadinessStates().isEmpty()
                && query.runtimeReadinessStates().stream()
                .noneMatch(readinessState -> matchesRuntimeReadiness(operator, readinessState))) {
            return false;
        }
        if (!query.tenantId().isBlank() && !operator.policy().allowsTenant(query.tenantId())) {
            return false;
        }
        if (!query.namespace().isBlank() && !operator.policy().allowsNamespace(query.namespace())) {
            return false;
        }
        if (!query.environment().isBlank() && !operator.policy().allowsEnvironment(query.environment())) {
            return false;
        }
        return true;
    }

    private static boolean matchesRuntimeReadiness(OperatorDefinition operator, String readinessState) {
        String normalized = normalizeReadinessStateAlias(readinessState);
        return OperatorCatalogFacets.readinessStateValue(operator).equals(normalized);
    }

    private static String normalizeReadinessStateAlias(String value) {
        String normalized = normalizeFacetValue(value);
        return switch (normalized) {
            case "executable", "runtime" -> "runtime-executable";
            case "design", "schema-only" -> "design-only";
            case "blocked" -> "runtime-blocked";
            case "governance" -> "governance-review";
            case "repair", "catalog-repair" -> "catalog-repair-required";
            default -> normalized;
        };
    }

    private static boolean matchesCapability(OperatorDefinition operator, String capability) {
        String normalized = normalizeFacetValue(capability);
        if ("design".equals(normalized) || "schema-only".equals(normalized)) {
            normalized = "design-only";
        } else if ("executable".equals(normalized)) {
            normalized = "runtime-executable";
        } else if ("requires-secrets".equals(normalized)
                || "secret".equals(normalized)
                || "secret-bound".equals(normalized)) {
            normalized = "requires-secret";
        } else if ("external".equals(normalized)) {
            normalized = "external-effect";
        }
        return OperatorCatalogFacets.capabilityValues(operator).contains(normalized);
    }

    private static String normalizeFacetValue(String value) {
        return OperatorCatalogFacets.normalizeFacetValue(value);
    }

    private static List<String> searchTokens(String search) {
        return Arrays.stream(search.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static String operatorSearchText(OperatorDefinition operator) {
        List<String> values = new ArrayList<>();
        addSearchValue(values, operator.operatorRef());
        addSearchValue(values, operator.display().name());
        addSearchValue(values, operator.display().description());
        addSearchValue(values, operator.source().kind());
        addSearchValue(values, operator.source().resourceId());
        addSearchValue(values, operator.source().libraryId());
        addSearchValue(values, OperatorCatalogFacets.readinessStateValue(operator));
        addSearchValue(values, operator.runtimeReadiness().title());
        addSearchValue(values, operator.runtimeReadiness().summary());
        addSchemaSearchValues(values, "config", operator.configSchema());
        for (OperatorDefinition.Port port : operator.ports().inputs()) {
            addPortSearchValues(values, port);
        }
        for (OperatorDefinition.Port port : operator.ports().outputs()) {
            addPortSearchValues(values, port);
        }
        return String.join(" ", values);
    }

    private static void addPortSearchValues(List<String> values, OperatorDefinition.Port port) {
        if (port == null) {
            return;
        }
        addSearchValue(values, port.name());
        addSearchValue(values, port.description());
        addSchemaSearchValues(values, port.name(), port.schema());
    }

    private static void addSchemaSearchValues(List<String> values, String qualifier, SchemaEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        collectSchemaSearchValues(values, qualifier == null ? "" : qualifier, "", envelope.schema());
    }

    @SuppressWarnings("unchecked")
    private static void collectSchemaSearchValues(List<String> values,
                                                  String qualifier,
                                                  String path,
                                                  Object schema) {
        if (!(schema instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        if (!path.isBlank()) {
            addSearchValue(values, path);
            if (!qualifier.isBlank()) {
                addSearchValue(values, qualifier + "." + path);
            }
        }
        addTypeSearchValues(values, map.get("type"));
        addSearchValue(values, map.get("format"));
        addSearchValue(values, map.get("title"));
        addSearchValue(values, map.get("description"));
        addSearchValue(values, map.get("$comment"));
        addSearchValue(values, map.get("default"));
        addSearchValue(values, map.get("const"));
        Object examples = map.get("examples");
        if (examples instanceof List<?> examplesList) {
            examplesList.forEach(value -> addSearchValue(values, value));
        }
        Object enumValues = map.get("enum");
        if (enumValues instanceof List<?> valuesList) {
            valuesList.forEach(value -> addSearchValue(values, value));
        }

        Object properties = map.get("properties");
        if (properties instanceof Map<?, ?> propertyMap) {
            for (Map.Entry<?, ?> entry : propertyMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String childPath = path.isBlank() ? name : path + "." + name;
                addSearchValue(values, name);
                collectSchemaSearchValues(values, qualifier, childPath, entry.getValue());
            }
        }

        Object patternProperties = map.get("patternProperties");
        if (patternProperties instanceof Map<?, ?> patternMap) {
            for (Map.Entry<?, ?> entry : patternMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String childPath = path.isBlank() ? name : path + "." + name;
                addSearchValue(values, name);
                collectSchemaSearchValues(values, qualifier, childPath, entry.getValue());
            }
        }

        Object prefixItems = map.get("prefixItems");
        if (prefixItems instanceof List<?> items) {
            for (int i = 0; i < items.size(); i++) {
                String childPath = path.isBlank() ? String.valueOf(i) : path + "." + i;
                collectSchemaSearchValues(values, qualifier, childPath, items.get(i));
            }
        }

        Object items = map.get("items");
        if (items instanceof Map<?, ?>) {
            String childPath = path.isBlank() ? "0" : path + ".0";
            collectSchemaSearchValues(values, qualifier, childPath, items);
        }
    }

    private static void addTypeSearchValues(List<String> values, Object type) {
        if (type instanceof List<?> list) {
            list.forEach(item -> addSearchValue(values, item));
            return;
        }
        addSearchValue(values, type);
    }

    private static void addSearchValue(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            values.add(text);
        }
    }

    private static List<OperatorDefinition> nativeOperators() {
        return List.of(httpResource(), decisionTable(), transform());
    }

    private static OperatorDefinition httpResource() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("resourceId", Map.of("type", "string", "description", "Registered resource id."));
        properties.put("params", Map.of("type", "object", "additionalProperties", true));
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "httpResource",
                "1.0.0",
                new OperatorDefinition.Display("HTTP Resource", "Generic descriptor-backed HTTP resource operator.",
                        List.of("resource", "advanced")),
                OperatorDefinition.Source.builtIn("bloge-operator"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input",
                                SchemaEnvelope.object(properties, List.of("resourceId")), true,
                                "Raw httpResource input.")),
                        List.of(new OperatorDefinition.Port("output", SchemaEnvelope.opaque(), true,
                                "HTTP resource execution envelope."))
                ),
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", false, false, true),
                new OperatorDefinition.Lowering("native", "httpResource", Map.of()),
                List.of()
        );
    }

    private static OperatorDefinition decisionTable() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "bloge:decisionTable",
                "1.0.0",
                new OperatorDefinition.Display("Decision Table", "Rules with typed inputs and structured output.",
                        List.of("logic", "rules")),
                OperatorDefinition.Source.builtIn("bloge-dsl"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), true,
                                "Decision inputs.")),
                        List.of(new OperatorDefinition.Port("output", SchemaEnvelope.opaque(), true,
                                "Matched decision output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("dsl", "decision_table", Map.of()),
                List.of()
        );
    }

    private static OperatorDefinition transform() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "bloge:transform",
                "1.0.0",
                new OperatorDefinition.Display("Transform", "Maps expressions into a structured object.",
                        List.of("logic", "mapping")),
                OperatorDefinition.Source.builtIn("bloge-dsl"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), false,
                                "Referenced upstream values.")),
                        List.of(new OperatorDefinition.Port("output", SchemaEnvelope.opaque(), true,
                                "Mapped output object."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("dsl", "transform", Map.of()),
                List.of()
        );
    }
}
