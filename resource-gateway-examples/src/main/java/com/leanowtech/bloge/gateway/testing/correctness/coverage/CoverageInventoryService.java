package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageDerivationSource.DerivationSnapshot;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageImpactProposal.ChangeKind;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageImpactProposal.ObligationChange;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application boundary for draft save, reviewed freeze and non-mutating impact analysis. */
public final class CoverageInventoryService {

    private final CoverageInventoryRepository inventories;
    private final CoverageReviewAuthorizer authorizer;
    private final CoverageDerivationSource derivationSource;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CoverageInventoryService(
            CoverageInventoryRepository inventories,
            CoverageReviewAuthorizer authorizer,
            CoverageDerivationSource derivationSource,
            ObjectMapper mapper
    ) {
        this(inventories, authorizer, derivationSource, mapper, Clock.systemUTC());
    }

    public CoverageInventoryService(
            CoverageInventoryRepository inventories,
            CoverageReviewAuthorizer authorizer,
            CoverageDerivationSource derivationSource,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.authorizer = authorizer == null ? CoverageReviewAuthorizer.denyAll() : authorizer;
        this.derivationSource = Objects.requireNonNull(derivationSource, "derivationSource");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredCoverageInventory saveDraft(
            long expectedRevision,
            CoverageInventory candidate,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (candidate == null || candidate.lifecycle() != InventoryLifecycle.DRAFT
                || candidate.freezeReview().status()
                        != com.leanowtech.bloge.gateway.testing.correctness.domain
                                .CorrectnessProtocol.ReviewStatus.PENDING) {
            throw failure("RG.CORRECTNESS.INVENTORY_DRAFT_INVALID",
                    "Draft save requires a DRAFT Inventory with a pending freeze review.");
        }
        StoredCoverageInventory current = inventories.findHead(
                candidate.scope(), candidate.inventoryId()).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.inventory().revision() != expectedRevision)) {
            throw conflict();
        }
        if (current != null) {
            if (current.inventory().lifecycle() != InventoryLifecycle.DRAFT) {
                throw failure("RG.CORRECTNESS.INVENTORY_IMMUTABLE",
                        "A frozen or superseded Inventory cannot be edited as a draft.");
            }
            if (!current.inventory().target().equals(candidate.target())) {
                throw failure("RG.CORRECTNESS.REFERENCE_DRIFT",
                        "Normal draft save cannot change the exact Inventory target.");
            }
        }
        return inventories.saveIfRevision(expectedRevision, candidate, actor)
                .orElseThrow(CoverageInventoryService::conflict);
    }

    public FreezeResult freeze(
            EnterpriseScope scope,
            String inventoryId,
            long expectedRevision,
            ReviewRecord approval,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (scope == null || inventoryId == null || inventoryId.isBlank()
                || expectedRevision < 1 || approval == null || !approval.approved()
                || approval.reviewer() == null
                || !approval.reviewer().id().equals(actor.id())) {
            throw failure("RG.CORRECTNESS.FREEZE_REVIEW_INVALID",
                    "Freeze requires an approved review performed by the command actor.");
        }
        StoredCoverageInventory stored = inventories.findHead(scope, inventoryId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.INVENTORY_NOT_FOUND",
                        "Coverage Inventory was not found in the authorized scope."));
        CoverageInventory current = stored.inventory();
        if (current.revision() != expectedRevision) throw conflict();
        if (current.lifecycle() != InventoryLifecycle.DRAFT) {
            throw failure("RG.CORRECTNESS.INVENTORY_IMMUTABLE",
                    "Only a DRAFT Inventory can be frozen.");
        }
        if (!authorizer.mayFreeze(scope, current, actor)) {
            throw failure("RG.CORRECTNESS.FREEZE_FORBIDDEN",
                    "The actor is not authorized to freeze this denominator.");
        }
        validateFreeze(current, approval, actor);

        CoverageInventory frozen = new CoverageInventory(
                current.schemaVersion(), current.inventoryId(), current.revision(),
                current.scope(), current.target(), InventoryLifecycle.FROZEN,
                current.obligations(), current.derivationSources(), approval, current.metadata());
        StoredCoverageInventory result = inventories.saveIfRevision(
                expectedRevision, frozen, actor).orElseThrow(CoverageInventoryService::conflict);
        int waived = (int) result.inventory().obligations().stream()
                .filter(value -> value.lifecycle() == ObligationLifecycle.WAIVED).count();
        int retired = (int) result.inventory().obligations().stream()
                .filter(value -> value.lifecycle() == ObligationLifecycle.RETIRED).count();
        return new FreezeResult(result, result.inventory().obligations().size(), waived, retired);
    }

    public CoverageImpactProposal proposeImpact(
            EnterpriseScope scope,
            String inventoryId,
            ExactTargetRef requestedTarget
    ) {
        if (scope == null || inventoryId == null || inventoryId.isBlank()
                || requestedTarget == null) {
            throw failure("RG.CORRECTNESS.IMPACT_REQUEST_INVALID",
                    "Impact analysis requires an exact Inventory and target coordinate.");
        }
        StoredCoverageInventory stored = inventories.findHead(scope, inventoryId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.INVENTORY_NOT_FOUND",
                        "Coverage Inventory was not found in the authorized scope."));
        CoverageInventory current = stored.inventory();
        if (current.lifecycle() != InventoryLifecycle.FROZEN) {
            throw failure("RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN",
                    "Impact analysis requires a frozen denominator baseline.");
        }
        if (current.target().kind() != requestedTarget.kind()
                || !current.target().id().equals(requestedTarget.id())) {
            throw failure("RG.CORRECTNESS.IMPACT_TARGET_MISMATCH",
                    "Impact analysis cannot switch the target identity.");
        }
        DerivationSnapshot derived = derivationSource.derive(scope, requestedTarget);
        if (derived == null || !scope.equals(derived.scope())
                || !requestedTarget.equals(derived.target())) {
            throw failure("RG.CORRECTNESS.DERIVATION_SOURCE_INVALID",
                    "The derivation source did not close the requested scope and target.");
        }

        List<ObligationChange> changes = changes(current.obligations(), derived.proposedObligations());
        ExactAssetRef inventoryRef = new ExactAssetRef(
                "INVENTORY", current.inventoryId(), current.revision(),
                stored.inventoryFingerprint());
        boolean targetDrifted = !current.target().equals(derived.target());
        boolean sourcesDrifted = !current.derivationSources().equals(derived.sources());
        Map<String, Object> fingerprintSeed = new LinkedHashMap<>();
        fingerprintSeed.put("scope", scope);
        fingerprintSeed.put("currentInventoryRef", inventoryRef);
        fingerprintSeed.put("currentTarget", current.target());
        fingerprintSeed.put("proposedTarget", derived.target());
        fingerprintSeed.put("currentSources", current.derivationSources());
        fingerprintSeed.put("proposedSources", derived.sources());
        fingerprintSeed.put("changes", changes);
        String proposalFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                mapper, fingerprintSeed);
        return new CoverageImpactProposal(
                "", proposalFingerprint, scope, inventoryRef, current.target(), derived.target(),
                current.derivationSources(), derived.sources(), changes, targetDrifted,
                sourcesDrifted, clock.instant());
    }

    private void validateFreeze(
            CoverageInventory inventory,
            ReviewRecord approval,
            PrincipalRef actor
    ) {
        Instant now = clock.instant();
        if (inventory.obligations().isEmpty()
                || inventory.obligations().stream().allMatch(
                        value -> value.lifecycle() == ObligationLifecycle.RETIRED)) {
            throw failure("RG.CORRECTNESS.DENOMINATOR_EMPTY",
                    "A frozen denominator requires at least one active obligation.");
        }
        if (inventory.obligations().stream().anyMatch(
                value -> value.lifecycle() == ObligationLifecycle.PROPOSED)) {
            throw failure("RG.CORRECTNESS.OBLIGATION_REVIEW_REQUIRED",
                    "Every proposed obligation must be frozen, waived, or retired before freeze.");
        }
        if (inventory.derivationSources().isEmpty()) {
            throw failure("RG.CORRECTNESS.DERIVATION_SOURCE_REQUIRED",
                    "A frozen denominator requires exact derivation source snapshots.");
        }
        if (approval.reviewedAt().isAfter(now)) {
            throw failure("RG.CORRECTNESS.FREEZE_REVIEW_INVALID",
                    "Freeze review time cannot be in the future.");
        }
        boolean expiredWaiver = inventory.obligations().stream()
                .filter(value -> value.lifecycle() == ObligationLifecycle.WAIVED)
                .anyMatch(value -> value.waiver() == null
                        || !value.waiver().expiresAt().isAfter(now));
        if (expiredWaiver) {
            throw failure("RG.CORRECTNESS.WAIVER_EXPIRED",
                    "Expired or incomplete waivers cannot enter a frozen denominator.");
        }
        boolean highRisk = inventory.obligations().stream().anyMatch(value ->
                value.risk() == RiskLevel.HIGH || value.risk() == RiskLevel.CRITICAL);
        if (highRisk && inventory.metadata().createdBy().id().equals(actor.id())) {
            throw failure("RG.CORRECTNESS.FOUR_EYES_REQUIRED",
                    "High-risk denominator freeze requires a reviewer other than the author.");
        }
    }

    private List<ObligationChange> changes(
            List<CoverageObligation> current,
            List<CoverageObligation> proposed
    ) {
        Map<String, CoverageObligation> currentById = byId(current);
        Map<String, CoverageObligation> proposedById = byId(proposed);
        List<String> ids = new ArrayList<>();
        ids.addAll(currentById.keySet());
        proposedById.keySet().stream().filter(id -> !currentById.containsKey(id)).forEach(ids::add);
        ids.sort(String::compareTo);

        List<ObligationChange> result = new ArrayList<>();
        for (String id : ids) {
            CoverageObligation previous = currentById.get(id);
            CoverageObligation next = proposedById.get(id);
            String previousFingerprint = previous == null ? ""
                    : CorrectnessProtocolFingerprint.obligationFingerprint(mapper, previous);
            String proposedFingerprint = next == null ? ""
                    : CorrectnessProtocolFingerprint.obligationFingerprint(mapper, next);
            ChangeKind kind;
            if (previous == null) {
                kind = ChangeKind.ADDED;
            } else if (next == null) {
                kind = ChangeKind.REMOVAL_PROPOSED;
            } else if (comparisonFingerprint(previous).equals(comparisonFingerprint(next))) {
                kind = ChangeKind.UNCHANGED;
            } else {
                kind = ChangeKind.MODIFIED;
            }
            result.add(new ObligationChange(
                    id, kind, previousFingerprint, proposedFingerprint, previous, next));
        }
        return List.copyOf(result);
    }

    private String comparisonFingerprint(CoverageObligation value) {
        CoverageObligation proposedForm = new CoverageObligation(
                value.obligationId(), value.dimension(), value.title(), value.statement(),
                value.risk(), value.owner(), value.source(), ObligationLifecycle.PROPOSED,
                null, value.tags());
        return CorrectnessProtocolFingerprint.obligationFingerprint(mapper, proposedForm);
    }

    private static Map<String, CoverageObligation> byId(List<CoverageObligation> values) {
        Map<String, CoverageObligation> result = new LinkedHashMap<>();
        for (CoverageObligation value : values == null ? List.<CoverageObligation>of() : values) {
            if (result.put(value.obligationId(), value) != null) {
                throw failure("RG.CORRECTNESS.OBLIGATION_DUPLICATE",
                        "Coverage obligation ids must remain unique.");
            }
        }
        return result;
    }

    private static void requireActor(PrincipalRef actor) {
        if (actor == null) {
            throw failure("RG.CORRECTNESS.ACTOR_REQUIRED",
                    "A verified correctness authoring actor is required.");
        }
    }

    private static CoverageCommandException conflict() {
        return failure("RG.CORRECTNESS.REVISION_CONFLICT",
                "The Coverage Inventory changed; reload the exact head and retry.");
    }

    private static CoverageCommandException failure(String code, String message) {
        return new CoverageCommandException(code, message);
    }

    public record FreezeResult(
            StoredCoverageInventory stored,
            int obligationCount,
            int waivedCount,
            int retiredCount
    ) {
        public FreezeResult {
            if (stored == null || stored.inventory().lifecycle() != InventoryLifecycle.FROZEN
                    || obligationCount < 1 || waivedCount < 0 || retiredCount < 0
                    || waivedCount + retiredCount > obligationCount) {
                throw new IllegalArgumentException("Valid frozen Inventory result is required");
            }
        }
    }
}
