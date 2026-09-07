package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles Feature suite metadata with protected material and reclaims only expired safe orphans.
 *
 * <p>The state store and encrypted vault cannot share one transaction. This service therefore
 * treats every state-held receipt as a live reference, marks missing or mismatched material
 * fail-closed with an exact revision fence, and limits garbage collection to an unchanged expired
 * direct successor whose own reference and predecessor are absent from current suite metadata.
 * Current material, recoverable successors, roots, and concurrent candidates are retained.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness",
        name = "enabled",
        havingValue = "true")
public final class FeatureControlledSuiteReconciliationService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(FeatureControlledSuiteReconciliationService.class);
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_SCOPE_LIMIT = 10_000;
    private static final int DEFAULT_INVENTORY_LIMIT = 1_000;
    private final AgentTddStateRepository states;
    private final FeatureControlledMaterialStore materials;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the suite metadata/material reconciliation boundary. */
    @Autowired
    public FeatureControlledSuiteReconciliationService(
            AgentTddStateRepository states,
            FeatureControlledMaterialStore materials,
            ObjectMapper mapper) {
        this(states, materials, mapper, Clock.systemUTC());
    }

    /** Creates a deterministic reconciliation worker for fixed-clock tests. */
    FeatureControlledSuiteReconciliationService(
            AgentTddStateRepository states,
            FeatureControlledMaterialStore materials,
            ObjectMapper mapper,
            Clock clock) {
        this.states = Objects.requireNonNull(states, "states");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the configured bounded platform sweep when protected correctness material is enabled.
     *
     * <p>No business or scope coordinate is written to the log. A failed scope does not prevent
     * another scope from losing stale evidence or reclaiming an eligible orphan.</p>
     */
    @Scheduled(
            initialDelayString = "${gateway.agent-tdd.feature-controlled-suite.reconciliation-initial-delay-ms:60000}",
            fixedDelayString = "${gateway.agent-tdd.feature-controlled-suite.reconciliation-fixed-delay-ms:21600000}")
    public void scheduledReconciliation() {
        ScheduledReconciliationReport report = reconcileAll(
                DEFAULT_SCOPE_LIMIT, DEFAULT_INVENTORY_LIMIT);
        if (report.failedScopeCount() > 0) {
            LOGGER.warn("Feature suite reconciliation failed closed for {} scope(s)",
                    report.failedScopeCount());
        }
    }

    /**
     * Discovers current Feature-suite scopes and reconciles each scope at one shared observation time.
     *
     * @param scopeLimit bounded cross-scope suite inventory, from 1 through 10,000
     * @param inventoryLimit bounded protected-material inventory per scope, from 1 through 1,000
     * @return payload-free aggregate counts for operations tests and health instrumentation
     */
    public ScheduledReconciliationReport reconcileAll(int scopeLimit, int inventoryLimit) {
        if (scopeLimit < 1 || scopeLimit > 10_000) {
            throw new IllegalArgumentException("scopeLimit must be between 1 and 10000");
        }
        if (inventoryLimit < 1 || inventoryLimit > 1_000) {
            throw new IllegalArgumentException("inventoryLimit must be between 1 and 1000");
        }
        LinkedHashSet<String> scopeKeys = states.listByKind(
                        FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, scopeLimit).stream()
                .map(AgentTddStoredAsset::scopeKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Instant observedAt = clock.instant();
        int suiteMetadata = 0;
        int falseMetadata = 0;
        int markedFailedClosed = 0;
        int reclaimedMaterial = 0;
        int failedScopes = 0;
        for (String scopeKey : scopeKeys) {
            try {
                ReconciliationCounts counts = reconcile(
                        observedAt, inventoryLimit, platformIdentity(scopeKey)).counts();
                suiteMetadata += counts.suiteMetadataCount();
                falseMetadata += counts.falseMetadataCount();
                markedFailedClosed += counts.markedFailedClosedCount();
                reclaimedMaterial += counts.reclaimedMaterialCount();
            } catch (RuntimeException failure) {
                failedScopes++;
            }
        }
        return new ScheduledReconciliationReport(
                scopeKeys.size(), suiteMetadata, falseMetadata,
                reclaimedMaterial, markedFailedClosed, failedScopes);
    }

    /**
     * Checks one scope and conditionally tombstones expired direct-successor orphans.
     *
     * @param observedAt fixed sweep time used for every retention decision
     * @param inventoryLimit bounded vault inventory size, from 1 through 1000
     * @return payload-free aggregate counts and a canonical report fingerprint
     */
    public ReconciliationReport reconcile(
            Instant observedAt, int inventoryLimit, IntegrationRequestContext identity) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (inventoryLimit < 1 || inventoryLimit > 1000) {
            throw new IllegalArgumentException("inventoryLimit must be between 1 and 1000");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        List<AgentTddStoredAsset> suites = states.list(
                scope, FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE);
        Set<ExactAssetRef> referenced = new HashSet<>();
        List<String> issueFingerprints = new ArrayList<>();
        int healthy = 0;
        int falseMetadata = 0;
        int markedFailedClosed = 0;

        for (AgentTddStoredAsset suite : suites) {
            JsonNode receiptNode = suite.data().path("materialReceipt");
            exactRef(receiptNode).ifPresent(referenced::add);
            FeatureControlledMaterialStore.MaterialVerification verification = materials.verify(
                    receiptNode,
                    suite.assetRef(),
                    suite.data().path("definitionFingerprint").asText(),
                    suite.data().path("featureContractFingerprint").asText(),
                    identity);
            if (verification.current()) {
                healthy++;
                continue;
            }
            falseMetadata++;
            issueFingerprints.add(fingerprint(Map.of(
                    "status", verification.status(),
                    "receiptFingerprint", verification.receiptFingerprint(),
                    "materialRefFingerprint", verification.materialRefFingerprint())));
            if (markFailedClosed(scope, suite, verification)) markedFailedClosed++;
        }

        List<FeatureControlledMaterialStore.MaterialCandidate> inventory =
                materials.inventory(inventoryLimit, identity);
        int directSuccessorOrphans = 0;
        int recoverableSuccessors = 0;
        int reclaimed = 0;
        int retainedByFence = 0;
        List<String> reclaimedFingerprints = new ArrayList<>();
        for (FeatureControlledMaterialStore.MaterialCandidate candidate : inventory) {
            ExactAssetRef predecessor = directPredecessor(candidate.receipt());
            if (predecessor == null || referenced.contains(candidate.materialRef())) continue;
            directSuccessorOrphans++;
            if (referenced.contains(predecessor)
                    || candidate.receipt().retention().expiresAt().isAfter(observedAt)) {
                recoverableSuccessors++;
                continue;
            }
            if (materials.reclaimExpired(candidate, observedAt, identity)) {
                reclaimed++;
                reclaimedFingerprints.add(candidate.materialRefFingerprint());
            } else {
                retainedByFence++;
            }
        }

        ReconciliationCounts counts = new ReconciliationCounts(
                suites.size(), healthy, falseMetadata, markedFailedClosed,
                inventory.size(), inventory.size() == inventoryLimit,
                directSuccessorOrphans, recoverableSuccessors, reclaimed, retainedByFence);
        String reportFingerprint = fingerprint(Map.of(
                "counts", counts,
                "issues", issueFingerprints.stream().sorted().toList(),
                "reclaimed", reclaimedFingerprints.stream().sorted().toList()));
        return new ReconciliationReport(counts, reportFingerprint);
    }

    private boolean markFailedClosed(
            String scope,
            AgentTddStoredAsset observed,
            FeatureControlledMaterialStore.MaterialVerification verification) {
        return states.executeAtomically(() -> {
            AgentTddStoredAsset current;
            try {
                current = states.lockRevision(scope, observed.kind(), observed.assetRef(), observed.revision());
            } catch (RuntimeException concurrentChange) {
                return false;
            }
            JsonNode existing = current.data().path("materialReconciliation");
            if ("FAILED_CLOSED".equals(current.data().path("status").asText())
                    && verification.status().equals(existing.path("issueCode").asText())
                    && verification.receiptFingerprint().equals(
                    existing.path("receiptFingerprint").asText())) {
                return false;
            }
            ObjectNode next = current.data().deepCopy();
            next.put("status", "FAILED_CLOSED");
            next.put("evidenceFingerprint", "");
            next.remove("latestEvidence");
            ObjectNode issue = mapper.createObjectNode();
            issue.put("status", "FAILED_CLOSED");
            issue.put("issueCode", verification.status());
            issue.put("receiptFingerprint", verification.receiptFingerprint());
            issue.put("materialRefFingerprint", verification.materialRefFingerprint());
            next.set("materialReconciliation", issue);
            states.saveIfRevision(scope, current.kind(), current.assetRef(), current.revision(), next);
            return true;
        });
    }

    private java.util.Optional<ExactAssetRef> exactRef(JsonNode receiptNode) {
        try {
            return java.util.Optional.of(
                    mapper.treeToValue(receiptNode, Receipt.class).materialRef());
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return java.util.Optional.empty();
        }
    }

    private static ExactAssetRef directPredecessor(Receipt receipt) {
        if (receipt.lineageRefs().size() != 1) return null;
        ExactAssetRef predecessor = receipt.lineageRefs().getFirst();
        ExactAssetRef current = receipt.materialRef();
        boolean direct = "FIXTURE_MATERIAL".equals(predecessor.kind())
                && predecessor.id().equals(current.id())
                && predecessor.revision() + 1 == current.revision();
        return direct ? predecessor : null;
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static IntegrationRequestContext platformIdentity(String scopeKey) {
        String[] parts = scopeKey == null ? new String[0] : scopeKey.split("\\|", -1);
        if (parts.length != 5) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Feature suite scope cannot be reconstructed.");
        }
        return new IntegrationRequestContext(
                parts[0], parts[1], parts[2], parts[3], parts[4],
                "PLATFORM", "rg-feature-controlled-suite-reconciler", "",
                FeatureControlledMaterialStore.RECONCILE_PURPOSE,
                "feature-suite-reconciliation", Set.of(), "RESTRICTED", "");
    }

    /** Payload-free reconciliation counters suitable for health and operations surfaces. */
    public record ReconciliationCounts(
            int suiteMetadataCount,
            int healthyMetadataCount,
            int falseMetadataCount,
            int markedFailedClosedCount,
            int inventoriedMaterialCount,
            boolean inventoryTruncated,
            int directSuccessorOrphanCount,
            int recoverableSuccessorCount,
            int reclaimedMaterialCount,
            int retainedByFenceCount) { }

    /** Payload-free result whose fingerprint binds the aggregate findings of one sweep. */
    public record ReconciliationReport(
            ReconciliationCounts counts, String reportFingerprint) {
        /** Requires aggregate counts and a canonical, non-empty fingerprint. */
        public ReconciliationReport {
            Objects.requireNonNull(counts, "counts");
            reportFingerprint = reportFingerprint == null ? "" : reportFingerprint.trim();
            if (reportFingerprint.isBlank()) {
                throw new IllegalArgumentException("reportFingerprint is required");
            }
        }
    }

    /** Payload-free aggregate of one scheduled cross-scope reconciliation pass. */
    public record ScheduledReconciliationReport(
            int scopeCount,
            int suiteMetadataCount,
            int falseMetadataCount,
            int reclaimedMaterialCount,
            int markedFailedClosedCount,
            int failedScopeCount) {
        /** Rejects impossible aggregate counters. */
        public ScheduledReconciliationReport {
            if (scopeCount < 0 || suiteMetadataCount < 0 || falseMetadataCount < 0
                    || reclaimedMaterialCount < 0 || markedFailedClosedCount < 0
                    || failedScopeCount < 0) {
                throw new IllegalArgumentException("Scheduled reconciliation counts must be non-negative");
            }
        }
    }
}
