package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
                operators.add(publicationProjector.project(publication));
            }
        }
        for (ResourceDescriptor descriptor : resourceRegistry.all()) {
            Optional<ResourceDesignContract> contract = contractRegistry.findByResourceId(descriptor.resourceId());
            if (contract.isPresent() && !contract.get().visibleInCatalog(effectiveQuery.includeDeprecated())) {
                continue;
            }
            operators.add(projector.project(descriptor, contract));
        }
        return operators.stream()
                .filter(operator -> matches(operator, effectiveQuery))
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
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

    private static boolean matches(OperatorDefinition operator, OperatorCatalogQuery query) {
        if (!query.search().isBlank()) {
            String haystack = (operator.operatorRef() + " "
                    + operator.display().name() + " "
                    + operator.display().description()).toLowerCase(Locale.ROOT);
            if (!haystack.contains(query.search().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (!query.tags().isEmpty() && !operator.display().tags().containsAll(query.tags())) {
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
                new OperatorDefinition.Capabilities("EXTERNAL", "UNKNOWN", false, true),
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
