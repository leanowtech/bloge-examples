package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** AAD and immutable-record verification for encrypted Fixture material. */
final class FixtureMaterialIntegrity {

    private FixtureMaterialIntegrity() {
    }

    static StoredFixtureMaterial attach(ObjectMapper mapper, StoredFixtureMaterial value) {
        StoredFixtureMaterial detached = detached(mapper, value);
        validate(detached);
        return detached.withRecordFingerprint(recordFingerprint(mapper, detached));
    }

    static StoredFixtureMaterial verify(ObjectMapper mapper, StoredFixtureMaterial value) {
        StoredFixtureMaterial detached = detached(mapper, value);
        validate(detached);
        if (!Objects.equals(detached.recordFingerprint(), recordFingerprint(mapper, detached))) {
            throw new FixtureMaterialCommandException(
                    503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Protected Fixture material integrity verification failed");
        }
        return detached;
    }

    static String associatedData(EnterpriseScope scope, Receipt receipt) {
        ExactAssetRef ref = receipt.materialRef();
        return String.join("\n",
                "bloge.fixtureMaterial.aad.v2",
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), ref.kind(), ref.id(),
                Long.toString(ref.revision()), ref.fingerprint(), receipt.classification(),
                receipt.retention().expiresAt().toString());
    }

    private static String recordFingerprint(ObjectMapper mapper, StoredFixtureMaterial value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", value.schemaVersion());
        material.put("scope", value.scope());
        material.put("receipt", value.receipt());
        material.put("state", value.state());
        material.put("payloadAvailable", value.payloadAvailable());
        material.put("protectedPayload", value.protectedPayload());
        return ProtocolFingerprint.ofBounded(mapper, material, 4 * 1024 * 1024);
    }

    private static void validate(StoredFixtureMaterial value) {
        boolean available = StoredFixtureMaterial.AVAILABLE.equals(value.state());
        boolean expired = StoredFixtureMaterial.EXPIRED.equals(value.state());
        if (!StoredFixtureMaterial.SCHEMA_VERSION.equals(value.schemaVersion())
                || value.scope() == null || value.receipt() == null
                || !"FIXTURE_MATERIAL".equals(value.receipt().materialRef().kind())
                || !value.receipt().fixtureAssetId().equals(value.receipt().materialRef().id())
                || !value.receipt().payloadFingerprint().equals(
                        value.receipt().materialRef().fingerprint())
                || (!available && !expired)
                || available != value.payloadAvailable()
                || available == value.protectedPayload().isBlank()
                || expired && !value.protectedPayload().isBlank()) {
            throw new FixtureMaterialCommandException(
                    503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Protected Fixture material envelope is invalid");
        }
    }

    private static StoredFixtureMaterial detached(ObjectMapper mapper, StoredFixtureMaterial value) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(value), StoredFixtureMaterial.class);
        } catch (IOException | IllegalArgumentException failure) {
            throw new FixtureMaterialCommandException(
                    503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Protected Fixture material envelope could not be verified");
        }
    }
}
