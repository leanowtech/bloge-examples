package com.leanowtech.bloge.gateway.visual.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Default metadata adapter over Resource Gateway's authoritative Graph and operator catalogs. */
public final class ResourceGatewayReferenceCandidateProvider implements ReferenceCandidateProvider {
    private static final int MAX_FINGERPRINT_MATERIAL_BYTES = 20 * 1024 * 1024;

    private final GraphDraftRepository graphDrafts;
    private final VisualOperatorCatalog operators;
    private final ObjectMapper mapper;
    private final CorrectnessDefinitionRepository definitions;

    public ResourceGatewayReferenceCandidateProvider(GraphDraftRepository graphDrafts,
                                                     VisualOperatorCatalog operators,
                                                     ObjectMapper mapper) {
        this(graphDrafts, operators, mapper, null);
    }

    public ResourceGatewayReferenceCandidateProvider(GraphDraftRepository graphDrafts,
                                                     VisualOperatorCatalog operators,
                                                     ObjectMapper mapper,
                                                     CorrectnessDefinitionRepository definitions) {
        this.graphDrafts = Objects.requireNonNull(graphDrafts, "graphDrafts");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.definitions = definitions;
    }

    @Override
    public ProviderSnapshot snapshot(SearchRequest request) {
        List<ReferenceCandidate> candidates = candidates(request.scope());
        return new ProviderSnapshot(generation(candidates), candidates);
    }

    @Override
    public ProviderResolution resolve(ResolveRequest request) {
        ReferenceCandidate current = candidates(request.scope()).stream()
                .filter(candidate -> candidate.kind().equals(request.kind())
                        && candidate.id().equals(request.id()))
                .max(Comparator.comparingLong(ReferenceCandidate::revision))
                .orElse(null);
        if (current == null) {
            return new ProviderResolution(ResolveResult.Status.NOT_FOUND, null);
        }
        if (!current.exactCoordinateEquals(
                request.kind(), request.id(), request.revision(), request.fingerprint())) {
            return new ProviderResolution(ResolveResult.Status.DRIFTED, current);
        }
        return new ProviderResolution(ResolveResult.Status.RESOLVED, current);
    }

    private List<ReferenceCandidate> candidates(ReferenceScope scope) {
        Map<String, ReferenceCandidate> candidates = new LinkedHashMap<>();
        graphDrafts.all().stream()
                .filter(draft -> draft.tenantId().equals(scope.tenantId()))
                .filter(draft -> draft.environment().equals(scope.environmentId()))
                .filter(draft -> draft.namespace().equals(scope.projectId()))
                .map(draft -> graphCandidate(draft, scope))
                .forEach(candidate -> candidates.put(coordinate(candidate), candidate));

        OperatorCatalogQuery query = new OperatorCatalogQuery(
                "", List.of(), false, true, scope.tenantId(), scope.projectId(), scope.environmentId());
        List<OperatorDefinition> catalog = operators.list(query);
        catalog.stream().map(operator -> operatorCandidate(operator, scope))
                .forEach(candidate -> candidates.put(coordinate(candidate), candidate));
        operators.builtInFunctions(query).stream().map(function -> functionCandidate(function, scope))
                .forEach(candidate -> candidates.put(coordinate(candidate), candidate));
        if (definitions != null && definitions.supportsHeadListing()) {
            EnterpriseScope enterpriseScope = new EnterpriseScope(
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region());
            for (StoredCorrectnessDefinition stored : definitions.listHeads(
                    enterpriseScope, SearchRequest.MAX_LIMIT)) {
                ReferenceCandidate target = correctnessTargetCandidate(stored, scope);
                candidates.put(coordinate(target), target);
                ReferenceCandidate definition = correctnessDefinitionCandidate(stored, scope);
                candidates.put(coordinate(definition), definition);
            }
        }
        return List.copyOf(candidates.values());
    }

    private ReferenceCandidate graphCandidate(GraphDraft draft, ReferenceScope scope) {
        String actor = draft.revisionMetadata().updatedBy().isBlank()
                ? draft.revisionMetadata().createdBy() : draft.revisionMetadata().updatedBy();
        ReferenceCandidate.Owner owner = actor.isBlank() ? null : new ReferenceCandidate.Owner(actor, actor);
        return candidate("GRAPH", draft.draftId(), draft.graphName(),
                "Editable graph draft in " + draft.namespace() + ".",
                draft.revision(),
                VisualBundleFingerprint.fromCanonicalValue(
                        mapper, draft.withNodeFixtures(Map.of()), MAX_FINGERPRINT_MATERIAL_BYTES),
                "resource-gateway://graph-drafts", scope,
                ReferenceCandidate.Lifecycle.DRAFT, owner,
                List.of("graph", draft.namespace(), draft.status()),
                ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private ReferenceCandidate operatorCandidate(OperatorDefinition operator, ReferenceScope scope) {
        boolean deprecated = operator.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().toUpperCase(java.util.Locale.ROOT).contains("DEPRECATED"));
        String libraryId = operator.source().libraryId();
        ReferenceCandidate.Owner owner = libraryId.isBlank()
                ? null : new ReferenceCandidate.Owner(libraryId, libraryId);
        List<String> labels = new ArrayList<>(operator.display().tags());
        labels.add(operator.operatorVersion());
        labels.add(operator.source().kind());
        return candidate("OPERATOR", operator.operatorRef(), operator.display().name(),
                operator.display().description(), 1, operator.fingerprint(),
                libraryId.isBlank() ? "resource-gateway://operator-catalog"
                        : "resource-gateway://operator-libraries/" + libraryId,
                scope, deprecated ? ReferenceCandidate.Lifecycle.DEPRECATED
                        : ReferenceCandidate.Lifecycle.ACTIVE,
                owner, labels,
                operator.runtimeReadiness().executable()
                        ? ReferenceCandidate.Compatibility.COMPATIBLE
                        : ReferenceCandidate.Compatibility.REVIEW,
                "");
    }

    private ReferenceCandidate functionCandidate(OperatorLibrary.BuiltInFunction function,
                                                 ReferenceScope scope) {
        String authority = function.namespace().isBlank()
                ? "resource-gateway://built-in-functions"
                : "resource-gateway://built-in-functions/" + function.namespace();
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, function, MAX_FINGERPRINT_MATERIAL_BYTES);
        List<String> labels = new ArrayList<>();
        labels.add(function.category());
        labels.add(function.namespace());
        return candidate("FUNCTION", function.name(), function.displayName(), function.description(),
                1, fingerprint, authority, scope, ReferenceCandidate.Lifecycle.ACTIVE, null,
                labels, ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private ReferenceCandidate correctnessTargetCandidate(StoredCorrectnessDefinition stored,
                                                          ReferenceScope scope) {
        CorrectnessDefinition definition = stored.definition();
        return candidate(definition.target().kind().name(), definition.target().id(),
                definition.title(), definition.businessIntent(), definition.target().revision(),
                definition.target().fingerprint(), "resource-gateway://correctness-targets", scope,
                lifecycle(definition), owner(definition),
                List.of(definition.riskLevel().name(), definition.definitionId()),
                ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private ReferenceCandidate correctnessDefinitionCandidate(StoredCorrectnessDefinition stored,
                                                              ReferenceScope scope) {
        CorrectnessDefinition definition = stored.definition();
        return candidate("CORRECTNESS_DEFINITION", definition.definitionId(), definition.title(),
                definition.businessIntent(), definition.revision(), stored.definitionFingerprint(),
                "resource-gateway://correctness-definitions", scope, lifecycle(definition),
                owner(definition),
                List.of(definition.riskLevel().name(), definition.target().kind().name(),
                        definition.target().id()),
                ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private static ReferenceCandidate.Lifecycle lifecycle(CorrectnessDefinition definition) {
        return switch (definition.lifecycle()) {
            case DRAFT -> ReferenceCandidate.Lifecycle.DRAFT;
            case REVIEWED, ACTIVE -> ReferenceCandidate.Lifecycle.ACTIVE;
            case SUPERSEDED -> ReferenceCandidate.Lifecycle.SUPERSEDED;
        };
    }

    private static ReferenceCandidate.Owner owner(CorrectnessDefinition definition) {
        return new ReferenceCandidate.Owner(
                definition.owner().id(), definition.owner().displayName());
    }

    private static ReferenceCandidate candidate(String kind,
                                                String id,
                                                String displayName,
                                                String description,
                                                long revision,
                                                String fingerprint,
                                                String authority,
                                                ReferenceScope scope,
                                                ReferenceCandidate.Lifecycle lifecycle,
                                                ReferenceCandidate.Owner owner,
                                                List<String> labels,
                                                ReferenceCandidate.Compatibility compatibility,
                                                String disabledReasonCode) {
        return new ReferenceCandidate(ReferenceCandidate.SCHEMA_VERSION, kind, id,
                displayName == null || displayName.isBlank() ? id : displayName,
                description, revision, fingerprint, authority, scope, lifecycle, owner,
                labels.stream().filter(value -> value != null && !value.isBlank()).distinct().toList(),
                compatibility, disabledReasonCode);
    }

    private static String coordinate(ReferenceCandidate candidate) {
        return String.join("\u0000", candidate.kind(), candidate.id(),
                Long.toString(candidate.revision()), candidate.fingerprint());
    }

    private static long generation(List<ReferenceCandidate> candidates) {
        String canonical = candidates.stream().map(ResourceGatewayReferenceCandidateProvider::coordinate)
                .sorted().reduce("", (left, right) -> left + "\n" + right);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
