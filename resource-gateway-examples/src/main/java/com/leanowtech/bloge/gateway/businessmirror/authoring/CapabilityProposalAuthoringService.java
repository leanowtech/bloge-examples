package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
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

/** Authenticated command/query boundary for durable Capability Proposal authoring. */
public class CapabilityProposalAuthoringService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    private final CapabilityProposalDraftRepository drafts;
    private final CapabilityProposalSaveCoordinator saves;
    private final ObjectMapper mapper;

    public CapabilityProposalAuthoringService(
            CapabilityProposalDraftRepository drafts,
            CapabilityProposalSaveCoordinator saves,
            ObjectMapper mapper) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.saves = Objects.requireNonNull(saves, "saves");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates revision one and durably binds the exact response to an idempotency key. */
    @Transactional
    public CapabilityProposalSaveCoordinator.Outcome create(
            CapabilityProposalDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        return save(candidate == null ? "" : candidate.proposalId(), 0, candidate,
                idempotencyKey, identity, CapabilityProposalSaveCommand.Operation.CREATE);
    }

    /** Stores one optimistic revision and exactly replays an identical retry. */
    @Transactional
    public CapabilityProposalSaveCoordinator.Outcome save(
            String proposalId,
            long expectedRevision,
            CapabilityProposalDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        return save(proposalId, expectedRevision, candidate, idempotencyKey, identity,
                CapabilityProposalSaveCommand.Operation.SAVE);
    }

    public StoredCapabilityProposalDraft find(
            String proposalId, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(proposalId, identity);
        return drafts.find(scope(identity), id).orElseThrow(() -> notFound(identity, id, 0));
    }

    public StoredCapabilityProposalDraft findRevision(
            String proposalId, long revision, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(proposalId, identity);
        if (revision < 1) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_REVISION_INVALID",
                    "A positive Capability Proposal revision is required.", Map.of());
        }
        return drafts.findRevision(scope(identity), id, revision)
                .orElseThrow(() -> notFound(identity, id, revision));
    }

    public List<StoredCapabilityProposalDraft> revisions(
            String proposalId, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(proposalId, identity);
        if (drafts.find(scope(identity), id).isEmpty()) {
            throw notFound(identity, id, 0);
        }
        return drafts.revisions(scope(identity), id);
    }

    public CapabilityProposalPage list(
            String afterProposalId, int limit, IntegrationRequestContext identity) {
        requireIdentity(identity);
        if (limit < 1 || limit > 200) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_PAGE_INVALID",
                    "Capability Proposal page limit must be between 1 and 200.",
                    Map.of("limit", limit));
        }
        String cursor = normalized(afterProposalId);
        if (!cursor.isBlank() && !IDENTIFIER.matcher(cursor).matches()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_CURSOR_INVALID",
                    "Capability Proposal page cursor is invalid.", Map.of());
        }
        List<StoredCapabilityProposalDraft> values =
                drafts.list(scope(identity), cursor, limit + 1);
        boolean more = values.size() > limit;
        List<StoredCapabilityProposalDraft> items = more
                ? List.copyOf(values.subList(0, limit)) : List.copyOf(values);
        String next = more ? items.getLast().proposalId() : "";
        return new CapabilityProposalPage(CapabilityProposalPage.SCHEMA_VERSION, items, next);
    }

    private CapabilityProposalSaveCoordinator.Outcome save(
            String proposalId,
            long expectedRevision,
            CapabilityProposalDraft candidate,
            String idempotencyKey,
            IntegrationRequestContext identity,
            CapabilityProposalSaveCommand.Operation operation) {
        requireIdentity(identity);
        String id = requireId(proposalId, identity);
        if (candidate == null || expectedRevision < 0 || candidate.revision() != expectedRevision
                || !id.equals(candidate.proposalId())) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_IDENTITY_INVALID",
                    "Path id, body id, and expected revision must identify one Capability Proposal.",
                    Map.of("expectedRevision", Math.max(0, expectedRevision)));
        }
        if ((operation == CapabilityProposalSaveCommand.Operation.CREATE)
                != (expectedRevision == 0)) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_OPERATION_INVALID",
                    "Create requires revision zero; save requires a positive expected revision.",
                    Map.of());
        }
        CapabilitySnapshot.Scope expectedScope = scope(identity);
        if (!expectedScope.equals(candidate.scope())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.PROPOSAL_SCOPE_MISMATCH",
                    "Capability Proposal Scope must match the verified workload identity.",
                    identity.correlationId(), Map.of()));
        }
        List<VisualDiagnostic> secrets = VisualSecretGuard.detectRawSecrets(
                mapper.convertValue(candidate, Object.class), "/");
        if (!secrets.isEmpty()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_RAW_SECRET_FORBIDDEN",
                    "Raw Secret material must not be stored in Capability Proposals.",
                    Map.of("paths", secrets.stream().map(VisualDiagnostic::target)
                            .distinct().sorted().toList()));
        }
        CapabilityProposalSaveCommand command = new CapabilityProposalSaveCommand(
                CapabilityProposalSaveCommand.SCHEMA_VERSION, operation, expectedScope,
                id, expectedRevision, candidate, identity.actorId());
        try {
            return saves.execute(idempotencyKey, command, () -> drafts.saveIfRevision(
                            expectedRevision, candidate, identity.actorId())
                    .orElseThrow(() -> revisionConflict(identity, id)));
        } catch (CapabilityProposalSaveCoordinator.InvalidIdempotencyKeyException failure) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID",
                    failure.getMessage(), Map.of());
        } catch (CapabilityProposalSaveCoordinator.IdempotencyConflictException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key is bound to different Capability Proposal material.",
                    identity.correlationId(), Map.of()));
        }
    }

    private IntegrationProblemException revisionConflict(
            IntegrationRequestContext identity, String proposalId) {
        long current = drafts.find(scope(identity), proposalId)
                .map(StoredCapabilityProposalDraft::revision).orElse(0L);
        return new IntegrationProblemException(IntegrationProblem.retryableConflict(
                "RG.BUSINESS_MIRROR.PROPOSAL_REVISION_CONFLICT",
                "Capability Proposal changed after it was loaded.", identity.correlationId(),
                Map.of("currentRevision", current)));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String proposalId, long revision) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.PROPOSAL_NOT_FOUND",
                "Capability Proposal was not found in the authorized enterprise Scope.",
                identity.correlationId(), revision > 0
                        ? Map.of("proposalId", proposalId, "revision", revision)
                        : Map.of("proposalId", proposalId)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title,
            Map<String, Object> details) {
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
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_ID_INVALID",
                    "Capability Proposal id is invalid.", Map.of());
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
