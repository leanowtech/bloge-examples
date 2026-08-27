package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Independently issued, single-purpose proof that one exact mutant is equivalent. */
public record WorldMutationEquivalenceReceipt(
        String receiptId,
        String tenantId,
        String planFingerprint,
        String mutantId,
        String baselineFragmentFingerprint,
        String purpose,
        String mutantSourceFingerprint,
        String mutantGraphFingerprint,
        String mutantTargetFingerprint,
        Source source,
        String authorityId,
        String reasonCode,
        String receiptFingerprint
) {
    public enum Source { INDEPENDENT_SEMANTIC_PROOF, HUMAN_REVIEW }
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public WorldMutationEquivalenceReceipt {
        receiptId = text(receiptId);
        tenantId = text(tenantId);
        planFingerprint = fingerprint(planFingerprint);
        mutantId = text(mutantId);
        baselineFragmentFingerprint = fingerprint(baselineFragmentFingerprint);
        purpose = code(purpose);
        mutantSourceFingerprint = fingerprint(mutantSourceFingerprint);
        mutantGraphFingerprint = fingerprint(mutantGraphFingerprint);
        mutantTargetFingerprint = fingerprint(mutantTargetFingerprint);
        source = Objects.requireNonNull(source, "source");
        authorityId = text(authorityId);
        reasonCode = code(reasonCode);
        String expected = ProtocolFingerprint.of(MAPPER, material(receiptId, tenantId, planFingerprint,
                mutantId, baselineFragmentFingerprint, purpose, mutantSourceFingerprint,
                mutantGraphFingerprint, mutantTargetFingerprint, source, authorityId, reasonCode));
        if (receiptFingerprint == null || receiptFingerprint.isBlank()) {
            receiptFingerprint = expected;
        } else if (!expected.equals(receiptFingerprint)) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
    }

    public static WorldMutationEquivalenceReceipt semantic(String receiptId, String tenantId,
                                                           String planFingerprint, String mutantId,
                                                           String baselineFragmentFingerprint,
                                                           String purpose, String mutantSourceFingerprint,
                                                           String mutantGraphFingerprint,
                                                           String mutantTargetFingerprint,
                                                           String authorityId, String reasonCode) {
        return new WorldMutationEquivalenceReceipt(receiptId, tenantId, planFingerprint, mutantId,
                baselineFragmentFingerprint, purpose, mutantSourceFingerprint, mutantGraphFingerprint,
                mutantTargetFingerprint, Source.INDEPENDENT_SEMANTIC_PROOF, authorityId, reasonCode, "");
    }

    public static WorldMutationEquivalenceReceipt human(String receiptId, String tenantId,
                                                        String planFingerprint, String mutantId,
                                                        String baselineFragmentFingerprint,
                                                        String purpose, String mutantSourceFingerprint,
                                                        String mutantGraphFingerprint,
                                                        String mutantTargetFingerprint,
                                                        String reviewerId, String reasonCode) {
        return new WorldMutationEquivalenceReceipt(receiptId, tenantId, planFingerprint, mutantId,
                baselineFragmentFingerprint, purpose, mutantSourceFingerprint, mutantGraphFingerprint,
                mutantTargetFingerprint, Source.HUMAN_REVIEW, reviewerId, reasonCode, "");
    }

    public void verifyFor(String tenant, WorldMutationPlan plan, WorldMutationPlan.PlannedMutant mutant,
                          String expectedPurpose) {
        if (!tenantId.equals(tenant) || !tenantId.equals(plan.tenantId())
                || !planFingerprint.equals(plan.planFingerprint())
                || !mutantId.equals(mutant.mutantId())
                || !baselineFragmentFingerprint.equals(mutant.baselineFragmentFingerprint())
                || !purpose.equals(expectedPurpose)
                || !mutantSourceFingerprint.equals(mutant.mutantSourceFingerprint())
                || !mutantGraphFingerprint.equals(mutant.mutantGraphFingerprint())
                || !mutantTargetFingerprint.equals(mutant.mutantTargetFingerprint())) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
    }

    private static Map<String, Object> material(Object... values) {
        String[] names = {"receiptId", "tenantId", "planFingerprint", "mutantId",
                "baselineFragmentFingerprint", "purpose", "mutantSourceFingerprint",
                "mutantGraphFingerprint", "mutantTargetFingerprint", "source", "authorityId",
                "reasonCode"};
        Map<String, Object> material = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) material.put(names[i], values[i]);
        return material;
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.contains("\n") || value.contains("\r")) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
        return value.trim();
    }

    private static String code(String value) {
        String normalized = text(value);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
        return normalized;
    }

    private static String fingerprint(String value) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new WorldMutationException(WorldMutationException.Code.EQUIVALENCE_RECEIPT_INVALID);
        }
        return value;
    }
}
