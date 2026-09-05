package com.leanowtech.bloge.gateway.solution.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds one payload-free, scope-bound read model across solution entities, operator libraries,
 * runtime operators, visual drafts and immutable publications.
 *
 * <p>The source repositories do not share a transaction. A freeze therefore materializes every
 * source twice and accepts the result only when all normalized source fingerprints match. Three
 * failed attempts produce {@code CAPABILITY_INDEX_UNSTABLE}; callers never receive a mixed
 * catalog assembled across concurrent revisions.</p>
 */
@Service
public final class BusinessCapabilityIndex {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FREEZE_ATTEMPTS = 3;
    private static final Set<String> ENTITY_KINDS = Set.of(
            SolutionEntityRegistry.FEATURE, SolutionEntityRegistry.SCENARIO,
            SolutionEntityRegistry.INSTRUCTION, SolutionEntityRegistry.SOLUTION);

    private final AgentTddStateRepository states;
    private final OperatorLibraryRegistry libraries;
    private final VisualOperatorCatalog catalog;
    private final GraphDraftRepository drafts;
    private final VisualGraphPublicationRepository publications;
    private final ObjectMapper mapper;
    private final BusinessContractMatcher matcher;

    /** Creates the production index over the five canonical read sources. */
    public BusinessCapabilityIndex(AgentTddStateRepository states,
                                   OperatorLibraryRegistry libraries,
                                   VisualOperatorCatalog catalog,
                                   GraphDraftRepository drafts,
                                   VisualGraphPublicationRepository publications,
                                   ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.matcher = new BusinessContractMatcher();
    }

    /**
     * Freezes a consistent business capability snapshot for the authenticated scope.
     *
     * @param identity trusted request identity
     * @return immutable snapshot containing business-only cards
     */
    public Snapshot freeze(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        for (int attempt = 0; attempt < MAX_FREEZE_ATTEMPTS; attempt++) {
            Capture first = capture(identity);
            Capture second = capture(identity);
            if (first.vector().equals(second.vector())) {
                return snapshot(identity, second);
            }
        }
        throw new AgentTddToolException("CAPABILITY_INDEX_UNSTABLE",
                "Business capability sources changed while the snapshot was created.", Map.of(), true);
    }

    /** Lists cards using a cursor bound to scope, filters and the frozen snapshot. */
    public Map<String, Object> list(JsonNode arguments, IntegrationRequestContext identity) {
        Snapshot snapshot = freeze(identity);
        Set<String> kinds = upperSet(arguments.path("entityKinds"));
        String lifecycle = text(arguments, "lifecycle").toUpperCase(Locale.ROOT);
        int limit = boundedLimit(arguments.path("limit").asInt(50));
        String queryFingerprint = fingerprint(Map.of("kinds", kinds, "lifecycle", lifecycle));
        int offset = decodeCursor(text(arguments, "cursor"), identity, snapshot, queryFingerprint);
        List<Card> filtered = snapshot.capabilities().stream()
                .filter(card -> kinds.isEmpty() || kinds.contains(card.assetKind()))
                .filter(card -> lifecycle.isBlank() || lifecycle.equals(card.lifecycle()))
                .toList();
        if (offset > filtered.size()) stale();
        int end = Math.min(filtered.size(), offset + limit);
        String next = end < filtered.size()
                ? encodeCursor(identity, snapshot, queryFingerprint, end) : "";
        return Map.of("entities", filtered.subList(offset, end), "nextCursor", next,
                "snapshotFingerprint", snapshot.snapshotFingerprint());
    }

    /** Returns one business-only card, semantic contract and dependency projection. */
    public Map<String, Object> get(JsonNode arguments, IntegrationRequestContext identity) {
        String assetRef = requiredText(arguments, "assetRef");
        Card card = freeze(identity).capabilities().stream()
                .filter(candidate -> candidate.assetRef().equals(assetRef))
                .findFirst().orElseThrow(() -> new AgentTddToolException(
                        "CAPABILITY_NOT_FOUND", "Business capability was not found."));
        return Map.of("card", card, "businessContract", card.business(),
                "dependencies", dependencies(card),
                "readiness", Map.of("lifecycle", card.lifecycle(), "runtimeState", card.runtimeState(),
                        "speccing", card.speccing()),
                "contractFingerprint", card.contractFingerprint(), "revision", card.revision());
    }

    /**
     * Recalls candidates from business names, aliases, tags and intent without claiming semantic
     * equivalence. P3's contract matcher is the only component allowed to promote a result to EXACT.
     */
    public Map<String, Object> search(JsonNode arguments, IntegrationRequestContext identity) {
        Snapshot snapshot = freeze(identity);
        JsonNode query = arguments.path("query");
        String intent = text(query, "intent").toLowerCase(Locale.ROOT);
        Set<String> kinds = upperSet(arguments.path("assetKinds"));
        int limit = boundedLimit(arguments.path("limit").asInt(10));
        List<Map<String, Object>> ranked = snapshot.capabilities().stream()
                .filter(card -> kinds.isEmpty() || kinds.contains(card.assetKind()))
                .filter(card -> intent.isBlank() || searchableText(card).contains(intent)
                        || tokens(intent).stream().anyMatch(searchableText(card)::contains))
                .map(card -> candidate(card, matcher.match(query, card.business())))
                .sorted(Comparator.<Map<String, Object>>comparingInt(
                                value -> matchRank(value.get("matchType").toString()))
                        .thenComparing(value -> value.get("assetRef").toString()))
                .toList();
        long exact = ranked.stream().filter(value -> "EXACT".equals(value.get("matchType"))).count();
        List<Map<String, Object>> candidates = ranked.stream().limit(limit).toList();
        String status = candidates.isEmpty() ? "NONE" : exact > 1 ? "AMBIGUOUS" : exact == 1 ? "EXACT" : "INCOMPLETE";
        boolean clarificationRequired = !candidates.isEmpty() && exact != 1;
        return Map.of("status", status,
                "snapshotFingerprint", snapshot.snapshotFingerprint(), "candidates", candidates,
                "clarification", Map.of("required", clarificationRequired,
                        "dimension", exact > 1 ? "ambiguousExactMatch" : "businessDefinition",
                        "question", clarificationRequired ? "请补充或确认候选能力的业务定义。" : ""));
    }

    private Map<String, Object> candidate(Card card, BusinessContractMatcher.Match match) {
        boolean activeSemanticKey = "ACTIVE".equals(card.business()
                .at("/businessDefinition/lifecycle").asText());
        String matchType = match.exact() && !activeSemanticKey ? "PARTIAL" : match.type().name();
        List<String> missing = match.exact() && !activeSemanticKey
                ? List.of("semanticLifecycle") : match.missingFacets();
        return Map.of("assetRef", card.assetRef(), "assetKind", card.assetKind(),
                "businessName", card.display().path("businessName").asText(card.assetRef()),
                "matchType", matchType, "matchedFacets", match.matchedFacets(),
                "missingFacets", missing, "conflicts", match.conflicts(),
                "reuseAllowed", match.exact() && activeSemanticKey
                        && List.of("READY", "PUBLISHED").contains(card.lifecycle()),
                "contractFingerprint", card.contractFingerprint(), "lifecycle", card.lifecycle());
    }

    private static int matchRank(String type) {
        return switch (type) { case "EXACT" -> 0; case "PARTIAL" -> 1; default -> 2; };
    }

    private Capture capture(IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        List<AgentTddStoredAsset> entities = ENTITY_KINDS.stream().sorted()
                .flatMap(kind -> states.list(scope, kind).stream()).toList();
        List<OperatorLibrary> libraryValues = libraries.all().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId)).toList();
        List<OperatorDefinition> runtime = catalog.list(new OperatorCatalogQuery(
                "", List.of(), false, true, identity.tenantId(), identity.projectId(), identity.environmentId()))
                .stream().filter(Objects::nonNull).sorted(Comparator.comparing(OperatorDefinition::operatorRef)).toList();
        List<GraphDraft> draftValues = drafts.all().stream().filter(identity::matchesDraftScope)
                .sorted(Comparator.comparing(GraphDraft::draftId)).toList();
        List<VisualGraphPublication> publicationValues = publications.all().stream()
                .filter(value -> identity.tenantId().equals(value.tenantId())
                        && identity.projectId().equals(value.namespace())
                        && identity.environmentId().equals(value.environment()))
                .sorted(Comparator.comparing(VisualGraphPublication::publicationId)).toList();

        LinkedHashMap<String, String> vector = new LinkedHashMap<>();
        vector.put("solutionEntities", fingerprint(entities.stream().map(this::entityIdentity).toList()));
        vector.put("operatorLibraries", fingerprint(libraryValues));
        vector.put("runtimeCatalog", fingerprint(runtime));
        vector.put("graphDrafts", fingerprint(draftValues.stream().map(this::draftIdentity).toList()));
        vector.put("publications", fingerprint(publicationValues.stream().map(this::publicationIdentity).toList()));

        LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
        entities.forEach(asset -> put(cards, entityCard(asset)));
        libraryValues.forEach(library -> library.operators().stream().filter(Objects::nonNull)
                .forEach(operator -> put(cards, operatorCard(operator, library.owner(), "OPERATOR_LIBRARY"))));
        runtime.forEach(operator -> put(cards, operatorCard(operator, "", "RUNTIME_CATALOG")));
        draftValues.forEach(draft -> put(cards, draftCard(draft)));
        publicationValues.forEach(publication -> put(cards, publicationCard(publication)));
        return new Capture(Map.copyOf(vector), cards.values().stream()
                .sorted(Comparator.comparing(Card::assetKind).thenComparing(Card::assetRef)).toList());
    }

    private Snapshot snapshot(IntegrationRequestContext identity, Capture capture) {
        String scopeFingerprint = fingerprint(Map.of("scope", AgentTddMutationService.scopeKey(identity)));
        String snapshotFingerprint = fingerprint(Map.of("scope", scopeFingerprint,
                "vector", capture.vector(), "cards", capture.cards().stream().map(card -> Map.of(
                        "kind", card.assetKind(), "ref", card.assetRef(),
                        "fingerprint", card.contractFingerprint(), "revision", card.revision(),
                        "lifecycle", card.lifecycle())).toList()));
        return new Snapshot(scopeFingerprint, capture.vector(), snapshotFingerprint, Instant.now(), capture.cards());
    }

    private Card entityCard(AgentTddStoredAsset asset) {
        JsonNode data = asset.data();
        String kind = data.path("entityKind").asText(kindFromStored(asset.kind()));
        JsonNode contract = data.path("contract");
        ObjectNode display = mapper.createObjectNode();
        JsonNode summary = contract.path("businessSemantics");
        display.put("businessName", firstText(summary, "businessName", "name", "intent", asset.assetRef()));
        display.put("description", firstText(summary, "description", "intent", "", ""));
        display.set("aliases", safeArray(summary.path("aliases")));
        display.set("tags", safeArray(summary.path("tags")));
        ObjectNode business = safeEntityBusiness(kind, contract);
        boolean speccing = data.path("speccing").asBoolean(false);
        return new Card(asset.assetRef(), kind, display, business,
                speccing ? "DRAFT" : "READY", speccing, speccing ? "SPECCING" : "READY", "",
                data.path("contractFingerprint").asText(asset.fingerprint()), asset.revision(),
                new Source("SOLUTION_ENTITY", false));
    }

    private ObjectNode safeEntityBusiness(String kind, JsonNode contract) {
        ObjectNode safe = mapper.createObjectNode();
        safe.put("profile", kind);
        copy(safe, contract, "businessDefinition", "businessSemantics", "output", "evaluationKind",
                "determinism", "inputs", "rules", "otherwise", "effect", "writeGovernance",
                "problem", "rootScenarioRef", "instructions", "goldenRef");
        return safe;
    }

    private Card operatorCard(OperatorDefinition operator, String owner, String registry) {
        ObjectNode display = mapper.createObjectNode();
        display.put("businessName", operator.display().name());
        display.put("description", operator.display().description());
        display.set("aliases", mapper.createArrayNode());
        display.set("tags", mapper.valueToTree(operator.display().tags()));
        ObjectNode business = mapper.createObjectNode();
        business.put("profile", operator.capabilities().effect().equalsIgnoreCase("PURE") ? "FEATURE" : "INSTRUCTION");
        business.put("effect", operator.capabilities().effect());
        business.set("inputs", mapper.valueToTree(operator.ports().inputs()));
        business.set("outputs", mapper.valueToTree(operator.ports().outputs()));
        boolean ready = operator.runtimeReadiness().executable();
        return new Card(operator.operatorRef(), "OPERATOR", display, business,
                ready ? "READY" : "DRAFT", !ready, operator.runtimeReadiness().state(), owner,
                operator.fingerprint(), 0, new Source(registry, false));
    }

    private Card draftCard(GraphDraft draft) {
        String kind = String.valueOf(draft.visualLayout().getOrDefault("assetKind", "TOOL"))
                .toUpperCase(Locale.ROOT);
        ObjectNode display = mapper.createObjectNode();
        display.put("businessName", draft.graphName());
        display.put("description", "");
        display.set("aliases", mapper.createArrayNode());
        display.set("tags", mapper.createArrayNode());
        ObjectNode business = mapper.createObjectNode();
        business.put("profile", kind);
        business.set("inputs", mapper.valueToTree(draft.inputSchema()));
        business.set("outputs", mapper.valueToTree(draft.outputSchema()));
        return new Card(draft.draftId(), kind, display, business, "DRAFT", true, draft.status(), "",
                fingerprint(draftIdentity(draft)), draft.revision(), new Source("GRAPH_DRAFT", false));
    }

    private Card publicationCard(VisualGraphPublication publication) {
        ObjectNode display = mapper.createObjectNode();
        display.put("businessName", publication.graphName());
        display.put("description", "");
        display.set("aliases", mapper.createArrayNode());
        display.set("tags", mapper.createArrayNode());
        ObjectNode business = mapper.createObjectNode();
        business.put("profile", "TOOL");
        business.put("artifactKind", publication.artifactKind());
        return new Card(publication.publicationId(), "PUBLICATION", display, business,
                "PUBLISHED", false, publication.artifactKind(), "",
                fingerprint(publicationIdentity(publication)), publication.draftRevision(),
                new Source("PUBLICATION_STORE", false));
    }

    private static void put(Map<String, Card> cards, Card card) {
        cards.putIfAbsent(card.assetKind() + "\u0000" + card.assetRef(), card);
    }

    private List<String> dependencies(Card card) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        collectText(card.business().path("rootScenarioRef"), refs);
        collectText(card.business().path("instructions"), refs);
        collectText(card.business().path("inputs"), refs);
        return List.copyOf(refs);
    }

    private static void collectText(JsonNode value, Set<String> refs) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isTextual() && value.asText().contains(":")) refs.add(value.asText());
        else if (value.isArray()) value.forEach(item -> collectText(item, refs));
        else if (value.isObject()) value.fields().forEachRemaining(entry -> collectText(entry.getValue(), refs));
    }

    private String encodeCursor(IntegrationRequestContext identity, Snapshot snapshot, String query, int offset) {
        String raw = fingerprint(Map.of("scope", AgentTddMutationService.scopeKey(identity))) + "|"
                + snapshot.snapshotFingerprint() + "|" + query + "|" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(String cursor, IntegrationRequestContext identity, Snapshot snapshot, String query) {
        if (cursor.isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 4
                    || !parts[0].equals(fingerprint(Map.of("scope", AgentTddMutationService.scopeKey(identity))))
                    || !parts[1].equals(snapshot.snapshotFingerprint()) || !parts[2].equals(query)) stale();
            int offset = Integer.parseInt(parts[3]);
            if (offset < 0) stale();
            return offset;
        } catch (IllegalArgumentException failure) {
            stale();
            return 0;
        }
    }

    private static void stale() {
        throw new AgentTddToolException("CAPABILITY_CONTEXT_STALE",
                "Capability context changed; search or list again.", Map.of(), true);
    }

    private Object entityIdentity(AgentTddStoredAsset asset) {
        return Map.of("kind", asset.kind(), "ref", asset.assetRef(), "revision", asset.revision(),
                "fingerprint", asset.fingerprint());
    }

    private Object draftIdentity(GraphDraft draft) {
        return Map.of("ref", draft.draftId(), "revision", draft.revision(), "status", draft.status(),
                "operators", draft.operatorFingerprints());
    }

    private Object publicationIdentity(VisualGraphPublication publication) {
        return Map.of("ref", publication.publicationId(), "draftRef", publication.draftId(),
                "revision", publication.draftRevision(), "kind", publication.artifactKind(),
                "operators", publication.operatorFingerprints());
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static String kindFromStored(String kind) {
        return switch (kind) {
            case SolutionEntityRegistry.FEATURE -> "FEATURE";
            case SolutionEntityRegistry.SCENARIO -> "SCENARIO";
            case SolutionEntityRegistry.INSTRUCTION -> "INSTRUCTION";
            case SolutionEntityRegistry.SOLUTION -> "SOLUTION";
            default -> "UNKNOWN";
        };
    }

    private void copy(ObjectNode target, JsonNode source, String... names) {
        for (String name : names) if (source.has(name)) target.set(name, source.path(name).deepCopy());
    }

    private ArrayNode safeArray(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value.deepCopy() : mapper.createArrayNode();
    }

    private static String firstText(JsonNode node, String a, String b, String c, String fallback) {
        for (String name : List.of(a, b, c)) {
            if (!name.isBlank() && node.path(name).isTextual() && !node.path(name).asText().isBlank()) {
                return node.path(name).asText();
            }
        }
        return fallback;
    }

    private static int boundedLimit(int value) {
        if (value < 1 || value > 100) throw new AgentTddToolException(
                "INVALID_CAPABILITY_QUERY", "limit must be between 1 and 100.");
        return value;
    }

    private static Set<String> upperSet(JsonNode value) {
        if (!value.isArray()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().toUpperCase(Locale.ROOT));
        });
        return Set.copyOf(values);
    }

    private static String text(JsonNode node, String name) {
        return node != null && node.path(name).isTextual() ? node.path(name).asText().trim() : "";
    }

    private static String requiredText(JsonNode node, String name) {
        String value = text(node, name);
        if (value.isBlank()) throw new AgentTddToolException(
                "INVALID_CAPABILITY_QUERY", name + " is required.");
        return value;
    }

    private static List<String> tokens(String value) {
        return List.of(value.split("[\\s,，。;；:/_-]+" )).stream().filter(token -> token.length() > 1).toList();
    }

    private static String searchableText(Card card) {
        return (card.assetRef() + " " + card.display().toString() + " " + card.business().toString())
                .toLowerCase(Locale.ROOT);
    }

    private record Capture(Map<String, String> vector, List<Card> cards) { }

    /** Immutable, scope-bound index snapshot used by one list, get or search operation. */
    public record Snapshot(String scopeFingerprint,
                           Map<String, String> catalogRevisionVector,
                           String snapshotFingerprint,
                           Instant createdAt,
                           List<Card> capabilities) {
        /** Defensively freezes the revision vector and card list. */
        public Snapshot {
            catalogRevisionVector = Map.copyOf(catalogRevisionVector);
            capabilities = List.copyOf(capabilities);
        }
    }

    /** Business-only capability projection. Implementation bindings and source payloads are absent. */
    public record Card(String assetRef,
                       String assetKind,
                       JsonNode display,
                       JsonNode business,
                       String lifecycle,
                       boolean speccing,
                       String runtimeState,
                       String owner,
                       String contractFingerprint,
                       long revision,
                       Source source) {
        /** Freezes JSON projections and normalizes all public text values. */
        public Card {
            assetRef = assetRef == null ? "" : assetRef;
            assetKind = assetKind == null ? "" : assetKind;
            display = display == null ? null : display.deepCopy();
            business = business == null ? null : business.deepCopy();
            lifecycle = lifecycle == null ? "" : lifecycle;
            runtimeState = runtimeState == null ? "" : runtimeState;
            owner = owner == null ? "" : owner;
            contractFingerprint = contractFingerprint == null ? "" : contractFingerprint;
        }

        @Override public JsonNode display() { return display.deepCopy(); }
        @Override public JsonNode business() { return business.deepCopy(); }
    }

    /** Identifies the authoritative registry without exposing implementation details. */
    public record Source(String registry, boolean implementationVisible) { }
}
