package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Components;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.DeepLinks;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.DefinitionSummary;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical, scope-isolated Workspace BFF query that never reads Fixture material. */
public final class CorrectnessWorkspaceQuery {

    private static final int MAX_CANONICAL_BYTES = 4 * 1_048_576;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final List<String> BASE_CAPABILITIES = List.of(
            "CORRECTNESS_DEFINITION_READ_V1", "CORRECTNESS_WORKSPACE_V1");

    private final CorrectnessDefinitionRepository definitions;
    private final CorrectnessWorkspaceComponentSource components;
    private final ObjectMapper mapper;

    public CorrectnessWorkspaceQuery(
            CorrectnessDefinitionRepository definitions,
            CorrectnessWorkspaceComponentSource components,
            ObjectMapper mapper
    ) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.components = Objects.requireNonNull(components, "components");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public CorrectnessWorkspaceProjection get(
            TargetKind targetKind,
            String targetId,
            String targetFingerprint,
            String definitionId,
            String caseCursor,
            int caseLimit,
            IntegrationRequestContext identity
    ) {
        EnterpriseScope scope = requireScope(identity);
        TargetKind kind = Objects.requireNonNull(targetKind, "targetKind");
        String id = required(targetId, "targetId", identity);
        String fingerprint = exactFingerprint(targetFingerprint, identity);
        String requestedDefinitionId = normalized(definitionId);
        String cursor = normalized(caseCursor);
        if (cursor.length() > MAX_CURSOR_LENGTH || caseLimit < 1 || caseLimit > 100) {
            throw badRequest(identity, "RG.CORRECTNESS.PAGE_INVALID",
                    "Correctness case page requires a limit from 1 to 100 and a bounded cursor.",
                    Map.of("caseLimit", caseLimit));
        }

        StoredCorrectnessDefinition stored = resolveDefinition(
                scope, kind, id, fingerprint, requestedDefinitionId, identity);
        CorrectnessDefinition definition = stored.definition();
        if (definition.target().kind() != kind || !definition.target().id().equals(id)
                || !definition.target().fingerprint().equals(fingerprint)) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.CORRECTNESS.REFERENCE_DRIFT",
                    "The requested target does not match the current Definition coordinate.",
                    identity.correlationId(), Map.of("definitionId", definition.definitionId())));
        }

        ExactAssetRef definitionRef = new ExactAssetRef(
                "DEFINITION", definition.definitionId(), definition.revision(),
                stored.definitionFingerprint());
        String pageQueryFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                Map.of(
                        "scope", scope,
                        "definitionRef", definitionRef,
                        "target", definition.target(),
                        "cursor", cursor,
                        "limit", caseLimit),
                MAX_CANONICAL_BYTES);
        Components snapshot = components.load(
                new Coordinate(
                        scope, definitionRef, definition.target(), definition.activeInventoryRef()),
                new PageRequest(cursor, caseLimit, pageQueryFingerprint));
        validateSnapshot(snapshot, pageQueryFingerprint, caseLimit, identity);

        List<String> capabilities = new ArrayList<>(BASE_CAPABILITIES);
        capabilities.addAll(snapshot.capabilities());
        DefinitionSummary definitionSummary = new DefinitionSummary(
                definitionRef, definition.title(), definition.businessIntent(),
                definition.successCriteria(), definition.riskLevel(), definition.owner(),
                definition.lifecycle().name());
        DeepLinks deepLinks = deepLinks(kind, id, definition.definitionId(), snapshot.lastRun());
        return new CorrectnessWorkspaceProjection(
                "", pageQueryFingerprint, definition.target(), definitionSummary,
                snapshot.coverage(), snapshot.oracleAssertions(), snapshot.cases(),
                snapshot.fixtures(), snapshot.reviews(), snapshot.lastPublication(),
                snapshot.lastRun(), snapshot.verdict(),
                snapshot.staleReasons(), capabilities, snapshot.commandPolicy(), deepLinks);
    }

    private StoredCorrectnessDefinition resolveDefinition(
            EnterpriseScope scope,
            TargetKind kind,
            String targetId,
            String targetFingerprint,
            String definitionId,
            IntegrationRequestContext identity
    ) {
        if (!definitionId.isEmpty()) {
            return definitions.findHead(scope, definitionId)
                    .orElseThrow(() -> notFound(identity));
        }
        List<StoredCorrectnessDefinition> candidates = definitions.findHeadCandidatesByTarget(
                scope, kind, targetId, targetFingerprint);
        if (candidates.isEmpty()) throw notFound(identity);
        if (candidates.size() > 1) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.CORRECTNESS.DEFINITION_AMBIGUOUS",
                    "More than one Definition matches the exact target; select one explicitly.",
                    identity.correlationId(), Map.of(
                            "definitionIds", candidates.stream()
                                    .map(value -> value.definition().definitionId()).sorted().toList())));
        }
        return candidates.getFirst();
    }

    private static void validateSnapshot(
            Components snapshot,
            String pageQueryFingerprint,
            int caseLimit,
            IntegrationRequestContext identity
    ) {
        if (snapshot == null || snapshot.cases().rows().size() > caseLimit
                || !snapshot.cases().queryFingerprint().equals(pageQueryFingerprint)
                || snapshot.cases().rows().stream().map(row ->
                        row.scenarioDraftSetRef().id() + "\u0000" + row.caseId())
                        .distinct().count()
                != snapshot.cases().rows().size()) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.CORRECTNESS.PROJECTION_INVALID",
                    "The correctness read model returned an invalid or unbounded projection.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static EnterpriseScope requireScope(IntegrationRequestContext identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Verified integration identity is required");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        requireField(missing, "tenantId", identity.tenantId());
        requireField(missing, "organizationId", identity.organizationId());
        requireField(missing, "projectId", identity.projectId());
        requireField(missing, "environmentId", identity.environmentId());
        requireField(missing, "region", identity.region());
        requireField(missing, "actorId", identity.actorId());
        requireField(missing, "purpose", identity.purpose());
        if (!missing.isEmpty()) {
            throw badRequest(identity, "RG.CORRECTNESS.CONTEXT_REQUIRED",
                    "A complete verified enterprise scope is required.", missing);
        }
        return new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String required(
            String value,
            String field,
            IntegrationRequestContext identity
    ) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw badRequest(identity, "RG.CORRECTNESS.TARGET_INVALID",
                    "An exact correctness target is required.", Map.of(field, "required"));
        }
        return normalized;
    }

    private static String exactFingerprint(String value, IntegrationRequestContext identity) {
        String normalized = normalized(value);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw badRequest(identity, "RG.CORRECTNESS.TARGET_INVALID",
                    "The target fingerprint must be an exact lowercase SHA-256 coordinate.",
                    Map.of("targetFingerprint", "invalid"));
        }
        return normalized;
    }

    private static DeepLinks deepLinks(
            TargetKind kind,
            String targetId,
            String definitionId,
            CorrectnessWorkspaceProjection.RunSummary lastRun
    ) {
        String root = "/author/correctness/" + kind.name().toLowerCase() + "/" + encoded(targetId);
        return new DeepLinks(
                root,
                root + "/definition/" + encoded(definitionId),
                root + "/cases",
                root + "/fixtures",
                lastRun == null ? "" : root + "/runs/" + encoded(lastRun.runId()));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.CORRECTNESS.DEFINITION_NOT_FOUND",
                "A Correctness Definition was not found in the authorized exact scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details
    ) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity == null ? "" : identity.correlationId(), details));
    }

    private static void requireField(Map<String, Object> missing, String field, String value) {
        if (normalized(value).isEmpty()) missing.put(field, "required");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
