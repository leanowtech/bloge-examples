package com.leanowtech.bloge.gateway.visual.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated application boundary for mutable Scenario draft-set authoring.
 *
 * <p>The service derives enterprise scope from the verified integration identity, resolves the
 * current graph and Contract again on every write, rejects raw credentials, and delegates only an
 * exact valid asset to the optimistic-concurrency repository. It never publishes fixtures or
 * executes tests; those are separate permissions and transactions.</p>
 */
public final class ScenarioDraftSetAuthoringService {

    private static final int MAX_TARGET_BYTES = 16 * 1_048_576;
    private static final int MAX_ID_LENGTH = 255;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final ScenarioDraftSetRepository repository;
    private final GraphDraftRepository graphDrafts;
    private final ContractDraftProjectionService contracts;
    private final ScenarioValidationService validation;
    private final ObjectMapper objectMapper;

    /**
     * @param repository mutable Scenario asset store
     * @param graphDrafts current graph-draft registry
     * @param contracts Contract projection service
     * @param validation exact-input Scenario validator
     * @param objectMapper canonical protocol serializer
     */
    public ScenarioDraftSetAuthoringService(
            ScenarioDraftSetRepository repository,
            GraphDraftRepository graphDrafts,
            ContractDraftProjectionService contracts,
            ScenarioValidationService validation,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.graphDrafts = Objects.requireNonNull(graphDrafts, "graphDrafts");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Validates and stores the next authoring revision.
     *
     * @param scenarioDraftSetId path-bound stable asset id
     * @param expectedRevision optimistic revision observed by the caller
     * @param candidate authoring payload
     * @param identity verified workload identity
     * @return exact stored revision
     */
    public StoredScenarioDraftSet save(
            String scenarioDraftSetId,
            long expectedRevision,
            ScenarioDraftSet candidate,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        if (candidate == null || expectedRevision < 0
                || candidate.revision() != expectedRevision
                || (!candidate.scenarioDraftSetId().isBlank()
                && !candidate.scenarioDraftSetId().equals(id))) {
            throw badRequest(identity, "RG.SCENARIO.IDENTITY_INVALID",
                    "Path id, body id, and expected revision must identify one mutable Scenario asset.",
                    Map.of("expectedRevision", Math.max(0, expectedRevision)));
        }
        requireScope(candidate.scope(), identity);
        requireClassification(candidate.metadata().classification(), identity);
        ScenarioDraftSet normalized = new ScenarioDraftSet(
                candidate.schemaVersion(),
                id,
                expectedRevision,
                candidate.scope(),
                candidate.target(),
                candidate.contractFingerprint(),
                candidate.scenarios(),
                candidate.metadata());
        List<VisualDiagnostic> secrets = VisualSecretGuard.detectRawSecrets(
                objectMapper.convertValue(normalized, Object.class), "/");
        if (!secrets.isEmpty()) {
            throw badRequest(identity, "RG.SCENARIO.RAW_SECRET_FORBIDDEN",
                    "Raw secret material must not be stored in Scenario assets; use a secretRef.",
                    Map.of("paths", secrets.stream().map(VisualDiagnostic::target)
                            .distinct().sorted().toList()));
        }
        ScenarioValidationReport report = validateCurrent(normalized, identity);
        if (!report.valid()) {
            throw badRequest(identity, "RG.SCENARIO.VALIDATION_FAILED",
                    "Scenario draft set is invalid or stale and was not stored.",
                    Map.of("diagnosticCodes", report.diagnostics().stream()
                            .map(VisualDiagnostic::code).distinct().sorted().toList()));
        }
        return repository.saveIfRevision(expectedRevision, normalized, identity.actorId())
                .orElseThrow(() -> conflict(identity, id));
    }

    /**
     * Validates a local Scenario draft set without storing it.
     *
     * @param candidate local authoring payload
     * @param identity verified workload identity
     * @return current exact-input validation report
     */
    public ScenarioValidationReport validate(
            ScenarioDraftSet candidate,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        if (candidate == null) {
            throw badRequest(identity, "RG.SCENARIO.REQUEST_INVALID",
                    "Scenario draft set is required.", Map.of());
        }
        requireScope(candidate.scope(), identity);
        requireClassification(candidate.metadata().classification(), identity);
        return validateCurrent(candidate, identity);
    }

    /**
     * Reads the current revision in the caller's verified enterprise scope.
     *
     * @param scenarioDraftSetId stable authoring asset id
     * @param identity verified workload identity
     * @return current exact revision
     */
    public StoredScenarioDraftSet find(
            String scenarioDraftSetId,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        return repository.find(scope(identity), id)
                .map(stored -> {
                    requireClassification(stored.draftSet().metadata().classification(), identity);
                    return stored;
                })
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.NOT_FOUND",
                        "Scenario draft set was not found in the authorized scope.",
                        identity.correlationId(), Map.of("scenarioDraftSetId", id))));
    }

    /**
     * Reads retained revision history in the caller's verified enterprise scope.
     *
     * @param scenarioDraftSetId stable authoring asset id
     * @param identity verified workload identity
     * @return newest-first immutable snapshots
     */
    public List<StoredScenarioDraftSet> revisions(
            String scenarioDraftSetId,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        List<StoredScenarioDraftSet> revisions = repository.revisions(scope(identity), id);
        revisions.forEach(stored ->
                requireClassification(stored.draftSet().metadata().classification(), identity));
        return revisions;
    }

    /**
     * Projects the server-authoritative Contract coordinate for one retained Graph draft.
     *
     * <p>Clients must use this result after Graph persistence instead of hashing their pre-save
     * browser model. The read remains scope- and clearance-bound by the verified workload
     * identity.</p>
     *
     * @param draftId retained Graph draft id
     * @param identity verified authoring workload identity
     * @return exact Contract projection and canonical fingerprint
     */
    public ScenarioContractProjection projectGraphContract(
            String draftId,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(draftId, identity);
        GraphDraft graph = graphDrafts.find(id)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.TARGET_NOT_FOUND",
                        "The exact graph draft target was not found.",
                        identity.correlationId(), Map.of("targetId", id))));
        requireGraphScope(graph, identity);
        String targetFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES);
        ContractDraft contract = contracts.project(graph, targetFingerprint);
        return new ScenarioContractProjection(
                "",
                scope(identity),
                contract,
                contract.fingerprint(objectMapper));
    }

    private ScenarioValidationReport validateCurrent(
            ScenarioDraftSet candidate,
            IntegrationRequestContext identity) {
        if (candidate.target().kind() != ContractDraft.TargetKind.GRAPH) {
            throw badRequest(identity, "RG.SCENARIO.OPERATOR_TARGET_NOT_YET_RESOLVABLE",
                    "This authoring endpoint currently resolves stored graph targets only.",
                    Map.of("targetKind", candidate.target().kind().name()));
        }
        GraphDraft graph = graphDrafts.find(candidate.target().id())
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.TARGET_NOT_FOUND",
                        "The exact graph draft target was not found.",
                        identity.correlationId(), Map.of("targetId", candidate.target().id()))));
        requireGraphScope(graph, identity);
        String targetFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES);
        ContractDraft contract = contracts.project(graph, targetFingerprint);
        return validation.validate(candidate, contract, graph);
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (!ENABLED_ENVIRONMENTS.contains(environment)
                || identity.projectId().isBlank() || identity.region().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.ENVIRONMENT_FORBIDDEN",
                    "Scenario authoring persistence is restricted to complete test or staging identities.",
                    identity.correlationId(), Map.of("environmentId", identity.environmentId())));
        }
    }

    private static void requireScope(
            ScenarioDraftSet.EnterpriseScope supplied,
            IntegrationRequestContext identity) {
        ScenarioDraftSet.EnterpriseScope expected = scope(identity);
        if (!expected.equals(supplied)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.SCOPE_MISMATCH",
                    "Scenario enterprise scope must match the verified workload identity.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireGraphScope(GraphDraft graph, IntegrationRequestContext identity) {
        if (!graph.tenantId().equals(identity.tenantId())
                || !graph.environment().equals(identity.environmentId())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.TARGET_SCOPE_MISMATCH",
                    "Graph target is outside the verified Scenario authoring scope.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireClassification(
            String classification,
            IntegrationRequestContext identity) {
        String required = classification == null
                ? "" : classification.trim().toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.SCENARIO.CLASSIFICATION_INVALID",
                    "Scenario classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.",
                    Map.of("classification", required));
        }
        if (!identity.hasClearanceAtLeast(required)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot access this Scenario classification.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private static String requireId(String value, IntegrationRequestContext identity) {
        String id = value == null ? "" : value.trim();
        if (id.isBlank() || id.length() > MAX_ID_LENGTH
                || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw badRequest(identity, "RG.SCENARIO.ID_INVALID",
                    "Scenario draft-set id must be a bounded portable identifier.", Map.of());
        }
        return id;
    }

    private static ScenarioDraftSet.EnterpriseScope scope(IntegrationRequestContext identity) {
        return new ScenarioDraftSet.EnterpriseScope(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region());
    }

    private IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String id) {
        long currentRevision = repository.find(scope(identity), id)
                .map(StoredScenarioDraftSet::revision)
                .orElse(0L);
        return new IntegrationProblemException(IntegrationProblem.retryableConflict(
                "RG.SCENARIO.REVISION_CONFLICT",
                "Scenario draft set changed after it was loaded.",
                identity.correlationId(), Map.of("currentRevision", currentRevision)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }
}
