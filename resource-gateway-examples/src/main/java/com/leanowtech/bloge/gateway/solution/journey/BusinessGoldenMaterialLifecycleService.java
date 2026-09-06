package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Renews protected Business GOLDEN material while its case remains ACTIVE and applies the retired
 * recovery grace period after its lifecycle changes to RETIRED.
 *
 * <p>The worker reads a bounded metadata page. Every case-set update uses its exact revision CAS;
 * an immutable vault successor created before a lost CAS remains non-authoritative and can be
 * recovered by the next retry. Reports contain counts only and never case material or references.</p>
 */
@Service
public final class BusinessGoldenMaterialLifecycleService {
    private static final int DEFAULT_BATCH = 1_000;
    private static final int RENEWAL_WINDOW_DAYS = 30;
    private final AgentTddStateRepository states;
    private final BusinessGoldenMaterialStore materials;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Creates the production lifecycle worker. */
    @Autowired
    public BusinessGoldenMaterialLifecycleService(
            AgentTddStateRepository states,
            BusinessGoldenMaterialStore materials,
            ObjectMapper mapper) {
        this(states, materials, mapper, Clock.systemUTC());
    }

    /** Creates a deterministic worker for fixed-clock lifecycle tests. */
    BusinessGoldenMaterialLifecycleService(
            AgentTddStateRepository states,
            BusinessGoldenMaterialStore materials,
            ObjectMapper mapper,
            Clock clock) {
        this.states = Objects.requireNonNull(states, "states");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs the bounded production renewal pass without exposing asset coordinates. */
    @Scheduled(
            initialDelayString = "${gateway.agent-tdd.business-golden-retention.initial-delay-ms:60000}",
            fixedDelayString = "${gateway.agent-tdd.business-golden-retention.fixed-delay-ms:21600000}")
    public void scheduledRenewal() {
        reconcile(DEFAULT_BATCH);
    }

    /** Renews due ACTIVE rows and gives newly RETIRED rows a 30-day recovery receipt. */
    public LifecycleReport reconcile(int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be 1..10000");
        int scanned = 0;
        int renewed = 0;
        int retired = 0;
        int conflicts = 0;
        int failed = 0;
        for (AgentTddStoredAsset asset : states.listByKind(AgentTddMutationService.CASE_SET, limit)) {
            scanned++;
            try {
                Transition transition = transition(asset);
                if (!transition.changed()) continue;
                states.saveIfRevision(asset.scopeKey(), asset.kind(), asset.assetRef(),
                        asset.revision(), transition.data());
                renewed += transition.renewed();
                retired += transition.retired();
            } catch (AgentTddToolException failure) {
                if ("GATE_REJECTED".equals(failure.code())) conflicts++;
                else failed++;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                failed++;
            }
        }
        return new LifecycleReport(scanned, renewed, retired, conflicts, failed);
    }

    private Transition transition(AgentTddStoredAsset asset) throws Exception {
        ObjectNode data = (ObjectNode) asset.data().deepCopy();
        ArrayNode rows = data.putArray("rows");
        int renewed = 0;
        int retired = 0;
        IntegrationRequestContext identity = platformIdentity(asset.scopeKey());
        for (JsonNode original : asset.data().path("rows")) {
            ObjectNode row = (ObjectNode) original.deepCopy();
            JsonNode receiptNode = row.path("materialReceipt");
            String lifecycle = row.path("lifecycle").asText();
            if (receiptNode.isObject() && "ACTIVE".equals(lifecycle) && renewalDue(receiptNode)) {
                row.set("materialReceipt", materials.renew(receiptNode, identity));
                renewed++;
            } else if (receiptNode.isObject() && "RETIRED".equals(lifecycle)
                    && !retiredReceipt(receiptNode)) {
                row.set("materialReceipt", materials.retire(receiptNode, identity));
                retired++;
            }
            rows.add(row);
        }
        return new Transition(renewed + retired > 0, data, renewed, retired);
    }

    private boolean renewalDue(JsonNode receiptNode) throws Exception {
        Receipt receipt = mapper.treeToValue(receiptNode, Receipt.class);
        Instant threshold = clock.instant().plus(RENEWAL_WINDOW_DAYS, ChronoUnit.DAYS);
        return !receipt.retention().expiresAt().isAfter(threshold);
    }

    private boolean retiredReceipt(JsonNode receiptNode) throws Exception {
        Receipt receipt = mapper.treeToValue(receiptNode, Receipt.class);
        return "rg.businessGolden.retired".equals(receipt.retention().policyVersion())
                && receipt.retention().retentionDays() == 30;
    }

    private static IntegrationRequestContext platformIdentity(String scopeKey) {
        String[] parts = scopeKey == null ? new String[0] : scopeKey.split("\\|", -1);
        if (parts.length != 5) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Business GOLDEN scope cannot be reconstructed.");
        }
        return new IntegrationRequestContext(parts[0], parts[1], parts[2], parts[3], parts[4],
                "PLATFORM", "rg-business-golden-lifecycle", "",
                "AGENT_TDD_GOLDEN_LIFECYCLE", "golden-lifecycle", java.util.Set.of(), "RESTRICTED", "");
    }

    /** Payload-free outcome of one bounded lifecycle pass. */
    public record LifecycleReport(int scanned, int renewed, int retired, int conflicts, int failed) {
        /** Rejects impossible aggregate counts. */
        public LifecycleReport {
            if (scanned < 0 || renewed < 0 || retired < 0 || conflicts < 0 || failed < 0) {
                throw new IllegalArgumentException("Lifecycle report counts must be non-negative");
            }
        }
    }

    private record Transition(boolean changed, ObjectNode data, int renewed, int retired) { }
}
