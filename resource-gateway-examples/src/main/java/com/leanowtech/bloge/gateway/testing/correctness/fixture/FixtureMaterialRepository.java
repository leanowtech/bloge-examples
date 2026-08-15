package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.time.Instant;
import java.util.Optional;

/** Encrypted material store with mandatory payload-free access audit. */
public interface FixtureMaterialRepository {

    Optional<StoredFixtureMaterial> saveIfRevision(
            long expectedRevision,
            StoredFixtureMaterial candidate,
            AccessAudit writeAudit);

    Optional<StoredFixtureMaterial> find(
            EnterpriseScope scope,
            String fixtureAssetId,
            long revision);

    long latestRevision(EnterpriseScope scope, String fixtureAssetId);

    void appendAccessAudit(AccessAudit audit);

    int expireDue(Instant observedAt, int limit);

    record AccessAudit(
            String accessId,
            EnterpriseScope scope,
            ExactAssetRef materialRef,
            String actorId,
            String purpose,
            String action,
            String outcome,
            String correlationId,
            Instant occurredAt
    ) {
        public AccessAudit {
            accessId = required(accessId, "accessId");
            if (scope == null || materialRef == null || occurredAt == null
                    || !"FIXTURE_MATERIAL".equals(materialRef.kind())) {
                throw new IllegalArgumentException("Complete Fixture material audit coordinate is required");
            }
            actorId = required(actorId, "actorId");
            purpose = required(purpose, "purpose");
            action = required(action, "action");
            outcome = required(outcome, "outcome");
            correlationId = required(correlationId, "correlationId");
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }
    }
}
