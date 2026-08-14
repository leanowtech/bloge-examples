package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authenticated command/query boundary for durable Domain Capability Package authoring. */
public class DomainCapabilityPackageAuthoringService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    private final DomainCapabilityPackageDraftRepository drafts;
    private final DomainCapabilityPackageSaveCoordinator saves;
    private final ObjectMapper mapper;

    public DomainCapabilityPackageAuthoringService(
            DomainCapabilityPackageDraftRepository drafts,
            DomainCapabilityPackageSaveCoordinator saves,
            ObjectMapper mapper) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.saves = Objects.requireNonNull(saves, "saves");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates revision one and durably binds the exact response to the required idempotency key. */
    @Transactional
    public DomainCapabilityPackageSaveCoordinator.Outcome create(
            DomainCapabilityPackageDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        return save(candidate == null ? "" : candidate.packageId(), 0, candidate,
                idempotencyKey, identity, DomainCapabilityPackageSaveCommand.Operation.CREATE);
    }

    /** Stores one optimistic revision and returns the original exact response on an identical retry. */
    @Transactional
    public DomainCapabilityPackageSaveCoordinator.Outcome save(
            String packageId,
            long expectedRevision,
            DomainCapabilityPackageDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        return save(packageId, expectedRevision, candidate, idempotencyKey, identity,
                DomainCapabilityPackageSaveCommand.Operation.SAVE);
    }

    public StoredDomainCapabilityPackageDraft find(
            String packageId, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        return drafts.find(scope(identity), id).orElseThrow(() -> notFound(identity, id, 0));
    }

    public StoredDomainCapabilityPackageDraft findRevision(
            String packageId, long revision, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        if (revision < 1) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_REVISION_INVALID",
                    "A positive Package revision is required.", Map.of());
        }
        return drafts.findRevision(scope(identity), id, revision)
                .orElseThrow(() -> notFound(identity, id, revision));
    }

    public List<StoredDomainCapabilityPackageDraft> revisions(
            String packageId, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        if (drafts.find(scope(identity), id).isEmpty()) {
            throw notFound(identity, id, 0);
        }
        return drafts.revisions(scope(identity), id);
    }

    public DomainCapabilityPackagePage list(
            String afterPackageId, int limit, IntegrationRequestContext identity) {
        requireIdentity(identity);
        if (limit < 1 || limit > 200) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_PAGE_INVALID",
                    "Package page limit must be between 1 and 200.", Map.of("limit", limit));
        }
        String cursor = normalized(afterPackageId);
        if (!cursor.isBlank() && !IDENTIFIER.matcher(cursor).matches()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_CURSOR_INVALID",
                    "Package page cursor is invalid.", Map.of());
        }
        List<StoredDomainCapabilityPackageDraft> values =
                drafts.list(scope(identity), cursor, limit + 1);
        boolean more = values.size() > limit;
        List<StoredDomainCapabilityPackageDraft> items = more
                ? List.copyOf(values.subList(0, limit)) : List.copyOf(values);
        String next = more ? items.getLast().packageId() : "";
        return new DomainCapabilityPackagePage(
                DomainCapabilityPackagePage.SCHEMA_VERSION, items, next);
    }

    private DomainCapabilityPackageSaveCoordinator.Outcome save(
            String packageId,
            long expectedRevision,
            DomainCapabilityPackageDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity,
            DomainCapabilityPackageSaveCommand.Operation operation) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        if (candidate == null || expectedRevision < 0 || candidate.revision() != expectedRevision
                || !id.equals(candidate.packageId())) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_IDENTITY_INVALID",
                    "Path id, body id, and expected revision must identify one Package draft.",
                    Map.of("expectedRevision", Math.max(0, expectedRevision)));
        }
        if ((operation == DomainCapabilityPackageSaveCommand.Operation.CREATE) != (expectedRevision == 0)) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_OPERATION_INVALID",
                    "Create requires revision zero; save requires a positive expected revision.", Map.of());
        }
        CapabilitySnapshot.Scope expectedScope = scope(identity);
        if (!expectedScope.equals(candidate.scope())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.PACKAGE_SCOPE_MISMATCH",
                    "Package enterprise scope must match the verified workload identity.",
                    identity.correlationId(), Map.of()));
        }
        List<VisualDiagnostic> secrets = VisualSecretGuard.detectRawSecrets(
                mapper.convertValue(candidate, Object.class), "/");
        if (!secrets.isEmpty()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_RAW_SECRET_FORBIDDEN",
                    "Raw secret material must not be stored in Package drafts.",
                    Map.of("paths", secrets.stream().map(VisualDiagnostic::target)
                            .distinct().sorted().toList()));
        }
        DomainCapabilityPackageSaveCommand command = new DomainCapabilityPackageSaveCommand(
                DomainCapabilityPackageSaveCommand.SCHEMA_VERSION, operation, expectedScope,
                id, expectedRevision, candidate, identity.actorId());
        try {
            return saves.execute(idempotencyKey, command, () -> drafts.saveIfRevision(
                            expectedRevision, candidate, identity.actorId())
                    .orElseThrow(() -> revisionConflict(identity, id)));
        } catch (DomainCapabilityPackageSaveCoordinator.InvalidIdempotencyKeyException failure) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID",
                    failure.getMessage(), Map.of());
        } catch (DomainCapabilityPackageSaveCoordinator.IdempotencyConflictException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key is already bound to different Package command material.",
                    identity.correlationId(), Map.of()));
        }
    }

    private IntegrationProblemException revisionConflict(
            IntegrationRequestContext identity, String packageId) {
        long current = drafts.find(scope(identity), packageId)
                .map(StoredDomainCapabilityPackageDraft::revision).orElse(0L);
        return new IntegrationProblemException(IntegrationProblem.retryableConflict(
                "RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT",
                "Package draft changed after it was loaded.", identity.correlationId(),
                Map.of("currentRevision", current)));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String packageId, long revision) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.PACKAGE_NOT_FOUND",
                "Package draft was not found in the authorized enterprise scope.",
                identity.correlationId(), revision > 0
                        ? Map.of("packageId", packageId, "revision", revision)
                        : Map.of("packageId", packageId)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title, Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String requireId(String value, IntegrationRequestContext identity) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_ID_INVALID",
                    "Package id is invalid.", Map.of());
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
