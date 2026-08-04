package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final VisualOperatorCatalog operators;
    private final ContractDraftProjectionService contracts;
    private final ScenarioValidationService validation;
    private final ScenarioContractCompatibilityService compatibility;
    private final ObjectMapper objectMapper;

    /**
     * @param repository mutable Scenario asset store
     * @param graphDrafts current graph-draft registry
     * @param operators current operator catalog
     * @param contracts Contract projection service
     * @param validation exact-input Scenario validator
     * @param objectMapper canonical protocol serializer
     */
    public ScenarioDraftSetAuthoringService(
            ScenarioDraftSetRepository repository,
            GraphDraftRepository graphDrafts,
            VisualOperatorCatalog operators,
            ContractDraftProjectionService contracts,
            ScenarioValidationService validation,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.graphDrafts = Objects.requireNonNull(graphDrafts, "graphDrafts");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.compatibility = new ScenarioContractCompatibilityService(objectMapper);
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
        ResolvedTarget resolved = resolveCurrent(normalized.target(), identity);
        ScenarioValidationReport report = validation.validate(
                normalized, resolved.contract(), resolved.graph());
        if (!report.valid()) {
            throw badRequest(identity, "RG.SCENARIO.VALIDATION_FAILED",
                    "Scenario draft set is invalid or stale and was not stored.",
                    Map.of("diagnosticCodes", report.diagnostics().stream()
                            .map(VisualDiagnostic::code).distinct().sorted().toList()));
        }
        return repository.saveIfRevision(
                        expectedRevision, normalized, resolved.contract(), identity.actorId())
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
        ResolvedTarget resolved = resolveCurrent(candidate.target(), identity);
        return validation.validate(candidate, resolved.contract(), resolved.graph());
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
     * Returns one bounded, source-bound Matrix page without loading all Scenario payloads.
     *
     * @param scenarioDraftSetId stable Scenario asset id
     * @param query exact source, filters, sort, and opaque cursor
     * @param identity verified workload identity
     * @return bounded Matrix page
     */
    public ScenarioTablePage queryPage(
            String scenarioDraftSetId,
            ScenarioTablePageQuery query,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        validatePageQuery(query, identity);
        ScenarioTableHead current = repository.findTableHead(scope(identity), id)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.NOT_FOUND",
                        "Scenario draft set was not found in the authorized scope.",
                        identity.correlationId(), Map.of("scenarioDraftSetId", id))));
        requireClassification(current.classification(), identity);
        if (current.revision() != query.expectedRevision()
                || !current.draftFingerprint().equals(query.expectedDraftFingerprint())) {
            throw sourceConflict(identity, current, "RG.SCENARIO.TABLE_SOURCE_CONFLICT",
                    "Scenario Matrix source changed after this view was opened.", List.of());
        }
        String queryFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper,
                Map.of(
                        "scenarioDraftSetId", id,
                        "revision", query.expectedRevision(),
                        "draftFingerprint", query.expectedDraftFingerprint(),
                        "query", query.query(),
                        "caseTypes", query.caseTypes(),
                        "sortField", query.sortField(),
                        "sortDirection", query.sortDirection()),
                MAX_TARGET_BYTES);
        try {
            return repository.queryPage(scope(identity), id, query, queryFingerprint)
                    .orElseThrow(() -> sourceConflict(
                            identity,
                            repository.findTableHead(scope(identity), id).orElse(current),
                            "RG.SCENARIO.TABLE_SOURCE_CONFLICT",
                            "Scenario Matrix source changed while the page was being resolved.",
                            List.of()));
        } catch (IllegalArgumentException invalidCursor) {
            throw badRequest(identity, "RG.SCENARIO.TABLE_CURSOR_INVALID",
                    "Scenario Matrix cursor is invalid or belongs to another exact query.", Map.of());
        }
    }

    /**
     * Applies multiple Matrix cell edits as one validated optimistic-concurrency revision.
     *
     * <p>Every touched row is checked against the case fingerprint observed by the author before
     * any mutation is applied. Conflicts return only coordinates and fingerprints, never payloads.</p>
     */
    public ScenarioBulkEditResult bulkEdit(
            String scenarioDraftSetId,
            ScenarioBulkEditCommand command,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        validateBulkCommand(command, identity);
        StoredScenarioDraftSet current = repository.find(scope(identity), id)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.NOT_FOUND",
                        "Scenario draft set was not found in the authorized scope.",
                        identity.correlationId(), Map.of("scenarioDraftSetId", id))));
        requireClassification(current.draftSet().metadata().classification(), identity);
        List<String> caseIds = command.edits().stream()
                .map(ScenarioBulkEditCommand.CellEdit::caseId).distinct().toList();
        if (current.revision() != command.expectedRevision()
                || !current.fingerprint().equals(command.expectedDraftFingerprint())) {
            throw sourceConflict(identity, current, "RG.SCENARIO.BULK_SOURCE_CONFLICT",
                    "Scenario draft set changed after the selected cells were loaded.",
                    caseConflicts(current.draftSet(), command));
        }

        Map<String, ScenarioDraftSet.ScenarioDraft> originals = new LinkedHashMap<>();
        current.draftSet().scenarios().forEach(scenario -> originals.put(scenario.scenarioId(), scenario));
        for (ScenarioBulkEditCommand.CellEdit edit : command.edits()) {
            ScenarioDraftSet.ScenarioDraft scenario = originals.get(edit.caseId());
            if (scenario == null || !caseFingerprint(scenario).equals(edit.expectedCaseFingerprint())) {
                throw sourceConflict(identity, current, "RG.SCENARIO.BULK_CASE_CONFLICT",
                        "One or more selected Scenario rows changed or were deleted.",
                        caseConflicts(current.draftSet(), command));
            }
        }

        Map<String, ScenarioDraftSet.ScenarioDraft> changed = new LinkedHashMap<>(originals);
        for (ScenarioBulkEditCommand.CellEdit edit : command.edits()) {
            changed.put(edit.caseId(), applyCellEdit(changed.get(edit.caseId()), edit, identity));
        }
        ScenarioDraftSet source = current.draftSet();
        ScenarioDraftSet candidate = new ScenarioDraftSet(
                source.schemaVersion(), source.scenarioDraftSetId(), source.revision(),
                source.scope(), source.target(), source.contractFingerprint(),
                source.scenarios().stream().map(scenario -> changed.get(scenario.scenarioId())).toList(),
                source.metadata());
        StoredScenarioDraftSet stored;
        try {
            stored = save(id, current.revision(), candidate, identity);
        } catch (IntegrationProblemException conflict) {
            if (!"RG.SCENARIO.REVISION_CONFLICT".equals(conflict.problem().code())) {
                throw conflict;
            }
            StoredScenarioDraftSet latest = repository.find(scope(identity), id).orElse(current);
            throw sourceConflict(identity, latest, "RG.SCENARIO.BULK_SOURCE_CONFLICT",
                    "Scenario draft set changed while the atomic edit was being committed.",
                    caseConflicts(latest.draftSet(), command));
        }
        return new ScenarioBulkEditResult(
                "", command.commandId(), id, current.revision(), current.fingerprint(),
                stored.revision(), stored.fingerprint(), command.edits().size(), caseIds,
                stored.savedAt(), stored.savedBy());
    }

    /**
     * Explains current Contract drift for one retained Scenario revision.
     *
     * <p>The service resolves both the immutable Contract captured at save time and the current
     * authoritative target inside the verified scope. Legacy revisions without a baseline return a
     * fail-closed REVIEW_REQUIRED report instead of manufacturing a safe classification.</p>
     *
     * @param scenarioDraftSetId stable authoring asset id
     * @param revision exact positive retained revision
     * @param identity verified workload identity
     * @return deterministic compatibility, impact, and migration report
     */
    public ContractCompatibilityReport compatibility(
            String scenarioDraftSetId,
            long revision,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(scenarioDraftSetId, identity);
        if (revision <= 0) {
            throw badRequest(identity, "RG.CONTRACT.COMPATIBILITY_REVISION_INVALID",
                    "Compatibility requires an exact retained Scenario revision.", Map.of());
        }
        ScenarioDraftSet.EnterpriseScope scope = scope(identity);
        StoredScenarioDraftSet source = repository.findRevision(scope, id, revision)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.REVISION_NOT_FOUND",
                        "The exact retained Scenario revision was not found.",
                        identity.correlationId(),
                        Map.of("scenarioDraftSetId", id, "revision", revision))));
        requireClassification(source.draftSet().metadata().classification(), identity);
        ScenarioContractBaseline baseline =
                repository.findContractBaseline(scope, id, revision).orElse(null);
        ResolvedTarget current = resolveCurrent(source.draftSet().target(), identity);
        return compatibility.analyze(source, baseline, current.contract());
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

    /**
     * Projects the server-authoritative Contract coordinate for one catalog operator.
     *
     * <p>The projection is tenant- and environment-policy bound. Namespace-restricted operators
     * fail closed because the standalone Scenario scope does not currently carry a namespace
     * coordinate.</p>
     *
     * @param operatorRef stable catalog operator reference
     * @param identity verified authoring workload identity
     * @return exact Contract projection and canonical fingerprint
     */
    public ScenarioContractProjection projectOperatorContract(
            String operatorRef,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String ref = requireOperatorRef(operatorRef, identity);
        OperatorDefinition operator = findOperator(ref, identity);
        requireOperatorScope(operator, identity);
        ContractDraft contract = contracts.project(operator);
        return new ScenarioContractProjection(
                "",
                scope(identity),
                contract,
                contract.fingerprint(objectMapper));
    }

    private ResolvedTarget resolveCurrent(
            ContractDraft.Target target,
            IntegrationRequestContext identity) {
        if (target.kind() == ContractDraft.TargetKind.OPERATOR) {
            OperatorDefinition operator = findOperator(target.id(), identity);
            requireOperatorScope(operator, identity);
            return new ResolvedTarget(contracts.project(operator), null);
        }
        GraphDraft graph = graphDrafts.find(target.id())
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.TARGET_NOT_FOUND",
                        "The exact graph draft target was not found.",
                        identity.correlationId(), Map.of("targetId", target.id()))));
        requireGraphScope(graph, identity);
        String targetFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES);
        ContractDraft contract = contracts.project(graph, targetFingerprint);
        return new ResolvedTarget(contract, graph);
    }

    private OperatorDefinition findOperator(
            String operatorRef,
            IntegrationRequestContext identity) {
        return operators.find(operatorRef)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.SCENARIO.TARGET_NOT_FOUND",
                        "The exact operator target was not found.",
                        identity.correlationId(), Map.of("targetId", operatorRef))));
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

    private static void requireOperatorScope(
            OperatorDefinition operator,
            IntegrationRequestContext identity) {
        if (!OperatorScenarioScope.allows(operator, identity)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SCENARIO.TARGET_SCOPE_MISMATCH",
                    "Operator target is outside the verified Scenario authoring scope.",
                    identity.correlationId(), Map.of("operatorRef", operator.operatorRef())));
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

    private static String requireOperatorRef(
            String value,
            IntegrationRequestContext identity) {
        String ref = value == null ? "" : value.trim();
        if (ref.isBlank() || ref.length() > 512
                || !ref.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw badRequest(identity, "RG.SCENARIO.OPERATOR_REF_INVALID",
                    "Operator ref must be a bounded catalog identifier.", Map.of());
        }
        return ref;
    }

    private static void validatePageQuery(
            ScenarioTablePageQuery query,
            IntegrationRequestContext identity) {
        if (query == null || !ScenarioTablePageQuery.SCHEMA_VERSION.equals(query.schemaVersion())
                || query.expectedRevision() <= 0
                || !query.expectedDraftFingerprint().matches("sha256:[0-9a-f]{64}")
                || query.query().length() > 200
                || query.caseTypes().size() > ScenarioDraftSet.CaseType.values().length
                || query.cursor().length() > 4096
                || query.limit() < 1 || query.limit() > 200) {
            throw badRequest(identity, "RG.SCENARIO.TABLE_QUERY_INVALID",
                    "Matrix query requires an exact source and bounded filters, cursor, and page size.",
                    Map.of("maximumLimit", 200));
        }
    }

    private static void validateBulkCommand(
            ScenarioBulkEditCommand command,
            IntegrationRequestContext identity) {
        if (command == null
                || !ScenarioBulkEditCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.commandId().isBlank() || command.commandId().length() > 128
                || !command.commandId().matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                || command.expectedRevision() <= 0
                || !command.expectedDraftFingerprint().matches("sha256:[0-9a-f]{64}")
                || command.atomicity() != ScenarioBulkEditCommand.Atomicity.ALL_OR_NOTHING
                || command.edits().isEmpty() || command.edits().size() > 5000) {
            throw badRequest(identity, "RG.SCENARIO.BULK_COMMAND_INVALID",
                    "Bulk edit requires one exact source and 1..5000 all-or-nothing cell edits.",
                    Map.of("maximumEdits", 5000));
        }
        Set<String> coordinates = new java.util.HashSet<>();
        for (ScenarioBulkEditCommand.CellEdit edit : command.edits()) {
            String coordinate = edit.caseId() + "\u001f" + edit.field() + "\u001f" + edit.path();
            if (edit.caseId().isBlank() || edit.caseId().length() > 255
                    || !edit.expectedCaseFingerprint().matches("sha256:[0-9a-f]{64}")
                    || edit.field() == null || edit.operation() == null
                    || (edit.field() != ScenarioBulkEditCommand.Field.GIVEN_PATH
                    && (!edit.path().isBlank()
                    || edit.operation() != ScenarioBulkEditCommand.Operation.SET))
                    || !coordinates.add(coordinate)) {
                throw badRequest(identity, "RG.SCENARIO.BULK_EDIT_INVALID",
                        "Each bulk cell edit must be unique, source-bound, and valid for its field.",
                        Map.of());
            }
        }
    }

    private ScenarioDraftSet.ScenarioDraft applyCellEdit(
            ScenarioDraftSet.ScenarioDraft source,
            ScenarioBulkEditCommand.CellEdit edit,
            IntegrationRequestContext identity) {
        return switch (edit.field()) {
            case NAME -> withName(source, edit.value(), identity);
            case CASE_TYPE -> withCaseType(source, edit.value(), identity);
            case TAGS -> withTags(source, edit.value(), identity);
            case GIVEN_PATH -> withGivenPath(source, edit, identity);
        };
    }

    private static ScenarioDraftSet.ScenarioDraft withName(
            ScenarioDraftSet.ScenarioDraft source,
            Object value,
            IntegrationRequestContext identity) {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 512) {
            throw badRequest(identity, "RG.SCENARIO.BULK_VALUE_INVALID",
                    "Scenario name must be a non-blank string of at most 512 characters.", Map.of());
        }
        return copy(source, name.trim(), source.caseType(), source.tags(), source.given());
    }

    private static ScenarioDraftSet.ScenarioDraft withCaseType(
            ScenarioDraftSet.ScenarioDraft source,
            Object value,
            IntegrationRequestContext identity) {
        try {
            ScenarioDraftSet.CaseType caseType = ScenarioDraftSet.CaseType.valueOf(
                    String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            return copy(source, source.name(), caseType, source.tags(), source.given());
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.SCENARIO.BULK_VALUE_INVALID",
                    "Scenario case type is not supported.", Map.of());
        }
    }

    private static ScenarioDraftSet.ScenarioDraft withTags(
            ScenarioDraftSet.ScenarioDraft source,
            Object value,
            IntegrationRequestContext identity) {
        if (!(value instanceof List<?> values) || values.size() > 64) {
            throw badRequest(identity, "RG.SCENARIO.BULK_VALUE_INVALID",
                    "Scenario tags must be an array containing at most 64 strings.", Map.of());
        }
        List<String> tags = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String tag) || tag.isBlank() || tag.length() > 128) {
                throw badRequest(identity, "RG.SCENARIO.BULK_VALUE_INVALID",
                        "Every Scenario tag must be a non-blank string of at most 128 characters.",
                        Map.of());
            }
            tags.add(tag.trim());
        }
        return copy(source, source.name(), source.caseType(), tags, source.given());
    }

    private ScenarioDraftSet.ScenarioDraft withGivenPath(
            ScenarioDraftSet.ScenarioDraft source,
            ScenarioBulkEditCommand.CellEdit edit,
            IntegrationRequestContext identity) {
        List<String> path = pointerSegments(edit.path(), identity);
        JsonNode input = objectMapper.valueToTree(source.given().input());
        if (!(input instanceof ObjectNode root)) {
            throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                    "Matrix Given-path edits require an object input.", Map.of("path", edit.path()));
        }
        ObjectNode parent = root;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            JsonNode child = parent.get(segment);
            if (child == null || child.isNull()) {
                ObjectNode created = objectMapper.createObjectNode();
                parent.set(segment, created);
                parent = created;
            } else if (child instanceof ObjectNode object) {
                parent = object;
            } else {
                throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                        "Given path crosses a non-object value.", Map.of("path", edit.path()));
            }
        }
        String leaf = path.getLast();
        if (edit.operation() == ScenarioBulkEditCommand.Operation.REMOVE) {
            if (!parent.has(leaf)) {
                throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                        "Given path does not exist and cannot be removed.", Map.of("path", edit.path()));
            }
            parent.remove(leaf);
        } else {
            parent.set(leaf, objectMapper.valueToTree(edit.value()));
        }
        ScenarioDraftSet.Given given = new ScenarioDraftSet.Given(
                objectMapper.convertValue(root, Object.class),
                ScenarioDraftSet.ValueProvenance.AUTHORED);
        return copy(source, source.name(), source.caseType(), source.tags(), given);
    }

    private static List<String> pointerSegments(
            String pointer,
            IntegrationRequestContext identity) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() > 2048) {
            throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                    "Given path must be a bounded JSON Pointer below the input root.", Map.of());
        }
        String[] encoded = pointer.substring(1).split("/", -1);
        if (encoded.length == 0 || encoded.length > 64) {
            throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                    "Given path depth must be between 1 and 64.", Map.of());
        }
        List<String> result = new ArrayList<>(encoded.length);
        for (String segment : encoded) {
            if (segment.isBlank() || segment.matches(".*~(?![01]).*")) {
                throw badRequest(identity, "RG.SCENARIO.BULK_PATH_INVALID",
                        "Given path contains an invalid JSON Pointer segment.", Map.of());
            }
            result.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    private static ScenarioDraftSet.ScenarioDraft copy(
            ScenarioDraftSet.ScenarioDraft source,
            String name,
            ScenarioDraftSet.CaseType caseType,
            List<String> tags,
            ScenarioDraftSet.Given given) {
        return new ScenarioDraftSet.ScenarioDraft(
                source.scenarioId(), name, source.description(), caseType, tags,
                given, source.dependencies(), source.then());
    }

    private String caseFingerprint(ScenarioDraftSet.ScenarioDraft scenario) {
        return VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, scenario, MAX_TARGET_BYTES);
    }

    private List<Map<String, Object>> caseConflicts(
            ScenarioDraftSet current,
            ScenarioBulkEditCommand command) {
        Map<String, ScenarioDraftSet.ScenarioDraft> currentById = new LinkedHashMap<>();
        current.scenarios().forEach(scenario -> currentById.put(scenario.scenarioId(), scenario));
        Map<String, String> expected = new LinkedHashMap<>();
        command.edits().forEach(edit -> expected.putIfAbsent(
                edit.caseId(), edit.expectedCaseFingerprint()));
        return expected.entrySet().stream().map(entry -> {
            ScenarioDraftSet.ScenarioDraft scenario = currentById.get(entry.getKey());
            String fingerprint = scenario == null ? "" : caseFingerprint(scenario);
            String status = scenario == null ? "DELETED"
                    : fingerprint.equals(entry.getValue()) ? "UNCHANGED" : "CHANGED";
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("caseId", entry.getKey());
            conflict.put("status", status);
            conflict.put("currentCaseFingerprint", fingerprint);
            return Map.copyOf(conflict);
        }).toList();
    }

    private static IntegrationProblemException sourceConflict(
            IntegrationRequestContext identity,
            StoredScenarioDraftSet current,
            String code,
            String title,
            List<Map<String, Object>> caseConflicts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentRevision", current.revision());
        details.put("currentDraftFingerprint", current.fingerprint());
        details.put("caseConflicts", List.copyOf(caseConflicts));
        return new IntegrationProblemException(IntegrationProblem.retryableConflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException sourceConflict(
            IntegrationRequestContext identity,
            ScenarioTableHead current,
            String code,
            String title,
            List<Map<String, Object>> caseConflicts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentRevision", current.revision());
        details.put("currentDraftFingerprint", current.draftFingerprint());
        details.put("caseConflicts", List.copyOf(caseConflicts));
        return new IntegrationProblemException(IntegrationProblem.retryableConflict(
                code, title, identity.correlationId(), details));
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

    private record ResolvedTarget(ContractDraft contract, GraphDraft graph) {
    }
}
