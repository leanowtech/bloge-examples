package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Action;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.ActionKind;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Item;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Kind;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Status;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Summary;

/**
 * Projects existing authoring authorities into an explicit, read-only migration inventory.
 *
 * <p>The module never returns descriptor transport details, schemas, fixture values, governed material,
 * or credentials. A READY item is eligible for visible re-authoring only; this module performs no
 * mutation and never invents a Connection, revision, fingerprint, or missing contract.</p>
 */
public final class LegacyAssetMigrationModule {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    private static final Set<String> SUPPORTED_TYPES = Set.of("string", "integer", "number", "boolean", "object");
    private static final List<Integer> HTTP_SUCCESS = IntStream.rangeClosed(200, 299).boxed().toList();
    private final LegacyResourceDescriptorSource resources;
    private final ResourceDesignContractRegistry contracts;
    private final GraphDraftRepository drafts;
    private final VisualGraphPublicationRepository publications;
    private final JsonSchemaSampleGenerator samples;
    private final ObjectMapper mapper;
    private final ApiResourceDecisions decisions;

    public LegacyAssetMigrationModule(LegacyResourceDescriptorSource resources,
                                      ResourceDesignContractRegistry contracts,
                                      GraphDraftRepository drafts,
                                      VisualGraphPublicationRepository publications) {
        this(resources, contracts, drafts, publications, new JsonSchemaSampleGenerator(),
                new ObjectMapper(), new ApiResourceDecisions());
    }

    /** Creates the module with the same schema, JSON, and Resource validation authorities used by authoring. */
    public LegacyAssetMigrationModule(LegacyResourceDescriptorSource resources,
                                      ResourceDesignContractRegistry contracts,
                                      GraphDraftRepository drafts,
                                      VisualGraphPublicationRepository publications,
                                      JsonSchemaSampleGenerator samples,
                                      ObjectMapper mapper,
                                      ApiResourceDecisions decisions) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.samples = Objects.requireNonNull(samples, "samples");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /**
     * Builds one visible re-authoring command without returning or persisting legacy transport material.
     * The caller must still choose a committed Connection and explicitly save the new Resource.
     */
    public LegacyApiResourceReauthorPreview previewResource(String resourceId) {
        if (resourceId == null || !IDENTIFIER.matcher(resourceId).matches()) {
            throw new LegacyAssetMigrationFailure(LegacyAssetMigrationFailure.Code.NOT_FOUND);
        }
        Optional<ResourceDesignContract> contract = contracts.findByResourceId(resourceId);
        Optional<LegacyResourceDescriptorSource.Descriptor> descriptor = resources.find(resourceId);
        if (contract.isEmpty() || (!resources.resourceIds().contains(resourceId) && descriptor.isEmpty())) {
            throw new LegacyAssetMigrationFailure(LegacyAssetMigrationFailure.Code.NOT_FOUND);
        }
        if (descriptor.isEmpty()) throw needsRepair();
        if (!ResourceDesignContract.STATUS_ACTIVE.equals(contract.orElseThrow().status())) {
            throw needsRepair();
        }
        return project(contract.orElseThrow(), descriptor.orElseThrow());
    }

    /** Builds one stable inventory for the verified authoring scope without modifying legacy state. */
    public LegacyAssetMigrationInventory inventory(AuthoringScope scope) {
        Objects.requireNonNull(scope, "scope");
        List<Item> items = new ArrayList<>();
        resourceItems(items);
        drafts.all().stream().filter(draft -> inScope(scope, draft.tenantId(), draft.namespace(), draft.environment()))
                .forEach(draft -> draftItems(items, draft));
        publications.all().stream()
                .filter(value -> inScope(scope, value.tenantId(), value.namespace(), value.environment()))
                .forEach(value -> publicationItem(items, value));
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::sourceId)
                .thenComparingLong(Item::sourceRevision));
        long ready = count(items, Status.READY_TO_REAUTHOR);
        long repair = count(items, Status.NEEDS_REPAIR);
        long legacy = count(items, Status.LEGACY_ONLY);
        return new LegacyAssetMigrationInventory(null,
                new Summary(items.size(), Math.toIntExact(ready), Math.toIntExact(repair), Math.toIntExact(legacy)),
                items);
    }

    private void resourceItems(List<Item> items) {
        Map<String, ResourceDesignContract> contractById = new LinkedHashMap<>();
        contracts.all().forEach(value -> contractById.put(value.resourceId(), value));
        TreeSet<String> descriptorIds = new TreeSet<>(resources.resourceIds());
        TreeSet<String> ids = new TreeSet<>(descriptorIds);
        ids.addAll(contractById.keySet());
        for (String id : ids) {
            ResourceDesignContract contract = contractById.get(id);
            List<String> reasons = new ArrayList<>();
            if (!descriptorIds.contains(id)) reasons.add("DESCRIPTOR_MISSING");
            if (contract == null) reasons.add("DESIGN_CONTRACT_MISSING");
            if (contract != null && !ResourceDesignContract.STATUS_ACTIVE.equals(contract.status())) {
                reasons.add("DESIGN_CONTRACT_NOT_ACTIVE");
            }
            LegacyApiResourceReauthorPreview preview = null;
            if (reasons.isEmpty()) {
                try {
                    preview = previewResource(id);
                } catch (LegacyAssetMigrationFailure failure) {
                    reasons.add("UNSAFE_LEGACY_RESOURCE_SHAPE");
                }
            }
            Status status = reasons.isEmpty() ? Status.READY_TO_REAUTHOR : Status.NEEDS_REPAIR;
            if (preview != null) preview.diagnostics().forEach(value -> reasons.add(value.code()));
            String displayName = contract == null ? id : contract.displayName();
            Action action = status == Status.READY_TO_REAUTHOR
                    ? new Action(ActionKind.REAUTHOR_RESOURCE, "/workbench/?create=api&legacyResourceId="
                            + URLEncoder.encode(id, StandardCharsets.UTF_8))
                    : new Action(ActionKind.REPAIR_SOURCE, "/capabilities/");
            items.add(new Item(Kind.API_RESOURCE, id, 0, displayName, status, 0, reasons, action));
        }
    }

    private LegacyApiResourceReauthorPreview project(ResourceDesignContract contract,
                                                      LegacyResourceDescriptorSource.Descriptor descriptor) {
        try {
            if (!contract.resourceId().equals(descriptor.resourceId()) || !"GET".equals(descriptor.method())) {
                throw needsRepair();
            }
            SimplifiedSchema input = simplify(contract.requestSchema());
            SimplifiedSchema output = simplify(contract.responseSchema());
            ApiResourceCommand command = new ApiResourceCommand(
                    contract.displayName(), blankToNull(contract.description()),
                    new ApiResourceCommand.Operation(descriptor.method(), descriptor.path(),
                            bindings(descriptor.parameterMapping(), input.envelope())),
                    new ApiResourceCommand.Contract(input.envelope(), output.envelope()), response(descriptor),
                    ApiResourceCommand.Effect.readOnly(), List.of(new ApiResourceCommand.Example(
                            "legacy-example", mapper.valueToTree(samples.generate(input.envelope())),
                            mapper.valueToTree(samples.generate(output.envelope())))));
            decisions.validateForAuthoring(command);
            List<LegacyApiResourceReauthorPreview.Diagnostic> diagnostics = new ArrayList<>();
            diagnostics.add(new LegacyApiResourceReauthorPreview.Diagnostic("CONNECTION_SELECTION_REQUIRED",
                    "Choose a committed Connection before saving this Resource."));
            if (input.simplified() || output.simplified()) {
                diagnostics.add(new LegacyApiResourceReauthorPreview.Diagnostic("LEGACY_SCHEMA_SIMPLIFIED",
                        "Review the generated examples because legacy schema annotations were simplified."));
            }
            return new LegacyApiResourceReauthorPreview(null,
                    new LegacyApiResourceReauthorPreview.Source("API_RESOURCE", contract.resourceId(), 0),
                    command, diagnostics);
        } catch (LegacyAssetMigrationFailure failure) {
            throw failure;
        } catch (ApiResourceAuthoringException | IllegalArgumentException failure) {
            throw needsRepair();
        }
    }

    private SimplifiedSchema simplify(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope source) {
        if (source == null || !(source.schema().get("properties") instanceof Map<?, ?> rawProperties)
                || !"object".equals(source.schema().get("type"))) {
            throw needsRepair();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        boolean simplified = source.schema().keySet().stream()
                .anyMatch(key -> !Set.of("type", "properties", "required", "additionalProperties").contains(key));
        for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!IDENTIFIER.matcher(name).matches() || !(entry.getValue() instanceof Map<?, ?> definition)) {
                throw needsRepair();
            }
            Object rawType = definition.get("type");
            String type = rawType instanceof String value ? value : "";
            if (!SUPPORTED_TYPES.contains(type)) throw needsRepair();
            properties.put(name, Map.of("type", type));
            simplified |= definition.size() != 1;
        }
        List<String> required = source.required().stream().filter(properties::containsKey).toList();
        return new SimplifiedSchema(
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.object(properties, required), simplified);
    }

    private List<ApiResourceCommand.Binding> bindings(VisualResourceParameterMapping mapping,
                                                       com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope input) {
        if (mapping == null || !mapping.cookieExpressions().isEmpty()) throw needsRepair();
        List<ApiResourceCommand.Binding> result = new ArrayList<>();
        addBindings(result, mapping.pathExpressions(), "PATH", input);
        addBindings(result, mapping.queryExpressions(), "QUERY", input);
        addBindings(result, mapping.headerExpressions(), "HEADER", input);
        if (mapping.bodyExpression() != null && !mapping.bodyExpression().isBlank()) {
            String name = inputName(mapping.bodyExpression());
            requireInput(input, name);
            result.add(new ApiResourceCommand.Binding("$." + name,
                    new ApiResourceCommand.Location("BODY", "body")));
        }
        result.sort(Comparator.comparingInt((ApiResourceCommand.Binding binding) -> switch (binding.to().location()) {
            case "PATH" -> 0;
            case "QUERY" -> 1;
            case "HEADER" -> 2;
            default -> 3;
        }).thenComparing(binding -> binding.to().name()));
        return List.copyOf(result);
    }

    private void addBindings(List<ApiResourceCommand.Binding> target, Map<String, String> expressions,
                             String location,
                             com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope input) {
        expressions.forEach((name, expression) -> {
            String inputName = inputName(expression);
            requireInput(input, inputName);
            target.add(new ApiResourceCommand.Binding("$." + inputName,
                    new ApiResourceCommand.Location(location, name)));
        });
    }

    private ApiResourceCommand.Response response(LegacyResourceDescriptorSource.Descriptor descriptor) {
        ApiResourceCommand.Success success = switch (descriptor.responseProtocol()) {
            case VisualResourceResponseProtocol.HttpStatus ignored -> new ApiResourceCommand.HttpStatus(HTTP_SUCCESS);
            case VisualResourceResponseProtocol.StatusCodes status -> new ApiResourceCommand.HttpStatus(
                    status.successCodes().stream().sorted().toList());
            case VisualResourceResponseProtocol.BodyCode body -> new ApiResourceCommand.BodyMatch(
                    jsonPath(body.codePath()), body.successValues().stream()
                            .map(value -> (JsonNode) mapper.valueToTree(value)).toList());
            case VisualResourceResponseProtocol.BodyFlag body -> new ApiResourceCommand.BodyMatch(
                    jsonPath(body.flagPath()), List.of(mapper.valueToTree(true)));
            case VisualResourceResponseProtocol.BlogeExpression ignored -> throw needsRepair();
        };
        return new ApiResourceCommand.Response(success, descriptor.payloadPath() == null
                || descriptor.payloadPath().isBlank() ? null : jsonPath(descriptor.payloadPath()));
    }

    private static String inputName(String expression) {
        if (expression != null && expression.matches("^ctx\\.params\\.[A-Za-z0-9._:-]+$")) {
            return expression.substring(expression.lastIndexOf('.') + 1);
        }
        if (expression != null && expression.matches("^ctx\\.params\\[\"[A-Za-z0-9._:-]+\"\\]$")) {
            return expression.substring(expression.indexOf('"') + 1, expression.lastIndexOf('"'));
        }
        throw needsRepair();
    }

    private static void requireInput(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope input, String name) {
        if (!input.properties().containsKey(name)) throw needsRepair();
    }

    private static String jsonPath(String value) {
        String normalized = value == null ? "" : value.trim();
        if ("$".equals(normalized)) return "$";
        if (normalized.startsWith("$.")) return normalized;
        if (normalized.matches("^[A-Za-z0-9._~-]+$")) return "$." + normalized;
        throw needsRepair();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LegacyAssetMigrationFailure needsRepair() {
        return new LegacyAssetMigrationFailure(LegacyAssetMigrationFailure.Code.NEEDS_REPAIR);
    }

    private record SimplifiedSchema(
            com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope envelope, boolean simplified) {
    }

    private static void draftItems(List<Item> items, GraphDraft draft) {
        boolean advanced = hasAdvancedEdges(draft);
        Status status = advanced ? Status.LEGACY_ONLY : Status.READY_TO_REAUTHOR;
        List<String> reasons = List.of(advanced ? "ADVANCED_EDGE_UNSUPPORTED" : "EXPLICIT_REAUTHORING_REQUIRED");
        String path = legacyDraftPath(draft.draftId());
        items.add(new Item(Kind.REUSABLE_FLOW_DRAFT, draft.draftId(), draft.revision(), draft.graphName(), status,
                draft.nodeFixtures().size(), reasons, new Action(ActionKind.OPEN_LEGACY_FLOW, path)));
        if (!draft.nodeFixtures().isEmpty()) {
            boolean governed = draft.nodeFixtures().values().stream()
                    .anyMatch(value -> value.governedRef() != null);
            items.add(new Item(Kind.FIXTURE_SET, draft.draftId(), draft.revision(),
                    draft.graphName() + " fixtures", Status.READY_TO_REAUTHOR, draft.nodeFixtures().size(),
                    List.of(governed ? "GOVERNED_REFERENCE_REVIEW_REQUIRED" : "EXPLICIT_CASE_AUTHORING_REQUIRED"),
                    new Action(ActionKind.REAUTHOR_FIXTURE, path)));
        }
    }

    private static void publicationItem(List<Item> items, VisualGraphPublication publication) {
        boolean advanced = publication.draft() == null || hasAdvancedEdges(publication.draft());
        Status status = advanced ? Status.LEGACY_ONLY : Status.READY_TO_REAUTHOR;
        String draftId = publication.draftId();
        items.add(new Item(Kind.REUSABLE_FLOW_VERSION, publication.publicationId(), publication.draftRevision(),
                publication.graphName(), status, publication.draft() == null ? 0 : publication.draft().nodeFixtures().size(),
                List.of(advanced ? "ADVANCED_EDGE_UNSUPPORTED" : "EXPLICIT_REAUTHORING_REQUIRED"),
                new Action(ActionKind.OPEN_LEGACY_FLOW, legacyDraftPath(draftId))));
    }

    private static boolean hasAdvancedEdges(GraphDraft draft) {
        return draft.edges().stream().anyMatch(edge -> !"data".equals(edge.kind()));
    }

    private static boolean inScope(AuthoringScope scope, String tenant, String project, String environment) {
        return scope.tenantId().equals(tenant) && scope.projectId().equals(project)
                && scope.environmentId().equals(environment);
    }

    private static String legacyDraftPath(String draftId) {
        return "/author/?authorWorkspace=legacy&draftId="
                + URLEncoder.encode(draftId, StandardCharsets.UTF_8);
    }

    private static long count(List<Item> items, Status status) {
        return items.stream().filter(item -> item.status() == status).count();
    }
}
