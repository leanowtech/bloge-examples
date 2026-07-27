package com.leanowtech.bloge.gateway.visual.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualAuthoringJsonValue;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Business-facing projection of the complete contract promised by a graph or operator.
 *
 * <p>The draft deliberately keeps JSON Schema structural data separate from field metadata and
 * execution semantics. It is an authoring model, not a replacement for the immutable contracts
 * consumed by ANEKE or the Resource Gateway testing control plane.</p>
 *
 * @param schemaVersion contract-draft protocol version
 * @param target exact graph or operator target
 * @param inputSchema accepted input structure
 * @param outputSchema promised output structure
 * @param errorContract declared stable error variants
 * @param executionSemantics effect, idempotency, durability, and side-effect declarations
 * @param invariants target-level preconditions and postconditions
 * @param compatibilityPolicy policy applied when the contract changes
 * @param fieldMetadata JSON-Pointer keyed field annotations kept outside JSON Schema
 * @param source origin of the projection
 * @param confidence confidence of the projection
 */
public record ContractDraft(
        String schemaVersion,
        Target target,
        SchemaEnvelope inputSchema,
        SchemaEnvelope outputSchema,
        List<ErrorVariant> errorContract,
        ExecutionSemantics executionSemantics,
        List<ContractInvariant> invariants,
        CompatibilityPolicy compatibilityPolicy,
        Map<String, FieldMetadata> fieldMetadata,
        Source source,
        Confidence confidence
) {
    /** Current mutable contract-draft protocol version. */
    public static final String SCHEMA_VERSION = "bloge.contractDraft.v1";
    private static final int MAXIMUM_FINGERPRINT_BYTES = 2 * 1024 * 1024;

    /** Normalizes defaults and freezes all collection values. */
    public ContractDraft {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        target = target == null ? Target.unknown() : target;
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        outputSchema = outputSchema == null ? SchemaEnvelope.opaque() : outputSchema;
        errorContract = errorContract == null ? List.of() : List.copyOf(errorContract);
        executionSemantics = executionSemantics == null ? ExecutionSemantics.unknown() : executionSemantics;
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
        compatibilityPolicy = compatibilityPolicy == null ? CompatibilityPolicy.strict() : compatibilityPolicy;
        fieldMetadata = immutableMetadata(fieldMetadata);
        source = source == null ? Source.AUTHORED : source;
        confidence = confidence == null ? Confidence.OPAQUE : confidence;
    }

    /**
     * Computes the exact fingerprint used by Scenario drafts to detect stale contracts.
     *
     * @param mapper application JSON mapper
     * @return canonical SHA-256 contract fingerprint
     */
    public String fingerprint(ObjectMapper mapper) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, this, MAXIMUM_FINGERPRINT_BYTES);
    }

    /** Supported contract targets. */
    public enum TargetKind {
        GRAPH,
        OPERATOR
    }

    /** Origin of the contract projection. */
    public enum Source {
        AUTHORED,
        DSL,
        IMPORTED,
        INFERRED
    }

    /** Precision available for the projected contract. */
    public enum Confidence {
        EXACT,
        INFERRED,
        OPAQUE
    }

    /** Declared target effect. UNKNOWN is mandatory when the source has no authoritative value. */
    public enum Effect {
        PURE,
        READ,
        WRITE,
        UNKNOWN
    }

    /**
     * Exact graph or operator identity.
     *
     * @param kind target kind
     * @param id stable target id
     * @param revision mutable-authoring revision, zero when unavailable
     * @param fingerprint exact target dependency fingerprint
     */
    public record Target(TargetKind kind, String id, long revision, String fingerprint) {
        /** Normalizes target identity without manufacturing a missing fingerprint. */
        public Target {
            kind = kind == null ? TargetKind.GRAPH : kind;
            id = trimmed(id);
            revision = Math.max(0, revision);
            fingerprint = trimmed(fingerprint);
        }

        /** @return an intentionally incomplete graph target for an empty authoring state */
        public static Target unknown() {
            return new Target(TargetKind.GRAPH, "", 0, "");
        }
    }

    /**
     * Stable error variant promised by the target.
     *
     * @param code stable machine-readable code
     * @param type normalized error type
     * @param description author-facing meaning
     * @param retryable whether the caller may retry the same logical request
     */
    public record ErrorVariant(String code, String type, String description, boolean retryable) {
        /** Normalizes textual fields. */
        public ErrorVariant {
            code = trimmed(code);
            type = trimmed(type);
            description = trimmed(description);
        }
    }

    /**
     * Complete execution-level contract semantics.
     *
     * @param effect observable target effect
     * @param idempotency stable policy label or expression
     * @param streaming null when unknown, otherwise streaming capability
     * @param durable null when unknown, otherwise durable-execution capability
     * @param sideEffectProtocol write reconciliation protocol when applicable
     */
    public record ExecutionSemantics(
            Effect effect,
            String idempotency,
            Boolean streaming,
            Boolean durable,
            SideEffectProtocol sideEffectProtocol
    ) {
        /** Preserves unknown values rather than inventing optimistic defaults. */
        public ExecutionSemantics {
            effect = effect == null ? Effect.UNKNOWN : effect;
            idempotency = defaulted(idempotency, "UNKNOWN");
        }

        /** @return execution semantics with no authoritative declaration */
        public static ExecutionSemantics unknown() {
            return new ExecutionSemantics(Effect.UNKNOWN, "UNKNOWN", null, null, null);
        }
    }

    /**
     * Governed declaration for externally observable writes.
     *
     * @param protocol stable protocol name
     * @param reconcilerRef reconciliation capability reference
     * @param reversible whether a compensating action is declared
     * @param metadata bounded protocol annotations
     */
    public record SideEffectProtocol(
            String protocol,
            String reconcilerRef,
            boolean reversible,
            Map<String, Object> metadata
    ) {
        /** Freezes protocol metadata. */
        public SideEffectProtocol {
            protocol = trimmed(protocol);
            reconcilerRef = trimmed(reconcilerRef);
            metadata = VisualAuthoringJsonValue.freezeMap(metadata);
        }
    }

    /**
     * Target-level contract declaration independent of one Scenario assertion.
     *
     * @param invariantId stable declaration id
     * @param phase PRECONDITION or POSTCONDITION
     * @param expression BLOGE or policy expression
     * @param description author-facing purpose
     * @param severity ERROR or WARNING
     */
    public record ContractInvariant(
            String invariantId,
            String phase,
            String expression,
            String description,
            String severity
    ) {
        /** Normalizes declaration identifiers. */
        public ContractInvariant {
            invariantId = trimmed(invariantId);
            phase = defaulted(phase, "POSTCONDITION").toUpperCase(Locale.ROOT);
            expression = trimmed(expression);
            description = trimmed(description);
            severity = defaulted(severity, "ERROR").toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Authoring policy used by compatibility and migration tooling.
     *
     * @param mode STRICT, BACKWARD, FORWARD, or NONE
     * @param unknownBlocksAutomaticMigration whether opaque changes fail closed
     */
    public record CompatibilityPolicy(String mode, boolean unknownBlocksAutomaticMigration) {
        /** Normalizes the policy label. */
        public CompatibilityPolicy {
            mode = defaulted(mode, "STRICT").toUpperCase(Locale.ROOT);
        }

        /** @return fail-closed compatibility policy */
        public static CompatibilityPolicy strict() {
            return new CompatibilityPolicy("STRICT", true);
        }
    }

    /**
     * Presentation and governance metadata for one JSON Pointer.
     *
     * @param displayName field label
     * @param description field purpose
     * @param classification payload classification
     * @param source metadata source
     * @param confidence metadata confidence
     * @param extensions bounded organization-specific annotations
     */
    public record FieldMetadata(
            String displayName,
            String description,
            String classification,
            Source source,
            Confidence confidence,
            Map<String, Object> extensions
    ) {
        /** Normalizes labels and freezes extension data. */
        public FieldMetadata {
            displayName = trimmed(displayName);
            description = trimmed(description);
            classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
            source = source == null ? Source.AUTHORED : source;
            confidence = confidence == null ? Confidence.OPAQUE : confidence;
            extensions = VisualAuthoringJsonValue.freezeMap(extensions);
        }
    }

    private static Map<String, FieldMetadata> immutableMetadata(Map<String, FieldMetadata> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, FieldMetadata> copy = new LinkedHashMap<>();
        source.forEach((path, metadata) -> copy.put(trimmed(path), metadata));
        return Collections.unmodifiableMap(copy);
    }

    private static String defaulted(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
