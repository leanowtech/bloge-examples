package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;

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
                                        VisualGraphPublicationOperatorProjector publicationProjector) {
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
            operators.addAll(libraryRegistry.operators(effectiveQuery.includeDeprecated()));
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
                if (!matches(operator, effectiveQuery)) {
                    continue;
                }
                hiddenMalformedPortDiagnostic(library, operator, i).ifPresent(diagnostics::add);
            }
        }
        return List.copyOf(diagnostics);
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
                && query.capabilities().isEmpty();
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
        if (!query.loweringModes().isEmpty()
                && !query.loweringModes().contains(normalizeFacetValue(operator.lowering().mode()))) {
            return false;
        }
        if (!query.capabilities().isEmpty()
                && query.capabilities().stream().anyMatch(capability -> !matchesCapability(operator, capability))) {
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
        addSearchValue(values, map.get("const"));
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
