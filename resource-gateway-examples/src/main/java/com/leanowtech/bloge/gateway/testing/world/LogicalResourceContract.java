package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, provider- and API-version-independent contract for one business resource capability.
 * Concrete implementation identity belongs to {@link LogicalResourceBinding}, never to this aggregate.
 */
public final class LogicalResourceContract {
    private final String contractId;
    private final SchemaEnvelope inputShape;
    private final SchemaEnvelope outputShape;
    private final ResponseSemantics semantics;
    private final String contractFingerprint;

    /**
     * Creates a contract and derives its canonical fingerprint from normalized structure and semantics.
     * Missing schemas or semantics fail closed; no caller-supplied fingerprint is trusted.
     */
    public LogicalResourceContract(String contractId,
                                   SchemaEnvelope inputShape,
                                   SchemaEnvelope outputShape,
                                   ResponseSemantics semantics) {
        if (contractId == null || contractId.isBlank() || contractId.length() > 256 || semantics == null) {
            throw LogicalResourceContractException.invalid();
        }
        this.contractId = contractId.trim();
        this.inputShape = LogicalResourceContractCanonicalizer.canonicalSchema(inputShape);
        this.outputShape = LogicalResourceContractCanonicalizer.canonicalSchema(outputShape);
        this.semantics = semantics;
        this.contractFingerprint = VisualBundleFingerprint.fromMaterial(fingerprintMaterial());
    }

    public String contractId() {
        return contractId;
    }

    public SchemaEnvelope inputShape() {
        return LogicalResourceContractCanonicalizer.copy(inputShape);
    }

    public SchemaEnvelope outputShape() {
        return LogicalResourceContractCanonicalizer.copy(outputShape);
    }

    public ResponseSemantics semantics() {
        return semantics;
    }

    public String contractFingerprint() {
        return contractFingerprint;
    }

    SchemaEnvelope internalInputShape() {
        return inputShape;
    }

    SchemaEnvelope internalOutputShape() {
        return outputShape;
    }

    private Map<String, Object> fingerprintMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("contractId", contractId);
        material.put("inputShape", schemaMaterial(inputShape));
        material.put("outputShape", schemaMaterial(outputShape));
        material.put("semantics", Map.of(
                "successCondition", Map.of(
                        "knowledge", semantics.successCondition().knowledge().name(),
                        "expression", semantics.successCondition().expression()),
                "errorClassification", Map.of(
                        "knowledge", semantics.errorClassification().knowledge().name(),
                        "categories", semantics.errorClassification().categories()),
                "idempotency", semantics.idempotency().name(),
                "retryability", semantics.retryability().name()));
        return material;
    }

    private static Map<String, Object> schemaMaterial(SchemaEnvelope schema) {
        return Map.of("format", schema.format(), "version", schema.version(), "schema", schema.schema());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LogicalResourceContract contract
                && contractFingerprint.equals(contract.contractFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contractFingerprint);
    }
}
