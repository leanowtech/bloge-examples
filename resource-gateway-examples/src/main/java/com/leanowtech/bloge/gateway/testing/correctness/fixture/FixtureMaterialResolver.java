package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;

/** Minimal-privilege compiler/runtime port for one exact Fixture material revision. */
public interface FixtureMaterialResolver {

    ResolvedFixtureMaterial resolve(
            EnterpriseScope scope,
            ExactAssetRef materialRef,
            MaterialAccessContext access);

    record MaterialAccessContext(
            String actorId,
            String purpose,
            String correlationId,
            String clearance
    ) {
        public MaterialAccessContext {
            actorId = required(actorId, "actorId");
            purpose = required(purpose, "purpose");
            correlationId = required(correlationId, "correlationId");
            clearance = required(clearance, "clearance");
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }
    }

    record ResolvedFixtureMaterial(
            ExactAssetRef materialRef,
            Receipt receipt,
            Object payload
    ) {
        public ResolvedFixtureMaterial {
            if (materialRef == null || receipt == null || payload == null
                    || !materialRef.equals(receipt.materialRef())) {
                throw new IllegalArgumentException("Resolved Fixture material is incomplete");
            }
        }
    }
}
