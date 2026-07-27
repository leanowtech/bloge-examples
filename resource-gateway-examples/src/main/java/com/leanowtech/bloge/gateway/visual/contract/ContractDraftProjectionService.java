package com.leanowtech.bloge.gateway.visual.contract;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects existing visual graph contracts into the business-facing Contract draft model.
 *
 * <p>The projection never infers execution semantics from absence. Graph semantics that are not
 * declared by the existing draft remain UNKNOWN so later compatibility and governance workflows
 * cannot mistake an optimistic default for an authoritative contract.</p>
 */
@Service
public class ContractDraftProjectionService {

    /**
     * Projects one graph draft without mutating its existing v1 wire representation.
     *
     * @param draft graph draft carrying current input and output schemas
     * @param targetFingerprint exact graph dependency fingerprint, blank when not yet compiled
     * @return authoring contract projection
     */
    public ContractDraft project(GraphDraft draft, String targetFingerprint) {
        if (draft == null) {
            throw new IllegalArgumentException("Graph draft is required for contract projection");
        }
        String targetId = draft.draftId().isBlank() ? draft.graphName() : draft.draftId();
        ContractDraft.Confidence confidence = schemaIsOpaque(draft)
                ? ContractDraft.Confidence.OPAQUE
                : ContractDraft.Confidence.EXACT;
        GraphContractSemantics semantics = GraphContractSemantics
                .fromVisualLayout(draft.visualLayout())
                .orElse(null);
        return new ContractDraft(
                ContractDraft.SCHEMA_VERSION,
                new ContractDraft.Target(ContractDraft.TargetKind.GRAPH, targetId, draft.revision(),
                        targetFingerprint),
                draft.inputSchema(),
                draft.outputSchema(),
                semantics == null ? List.of() : semantics.errorContract(),
                semantics == null
                        ? ContractDraft.ExecutionSemantics.unknown()
                        : semantics.executionSemantics(),
                semantics == null ? List.of() : semantics.invariants(),
                semantics == null
                        ? ContractDraft.CompatibilityPolicy.strict()
                        : semantics.compatibilityPolicy(),
                semantics == null ? Map.of() : semantics.fieldMetadata(),
                ContractDraft.Source.AUTHORED,
                confidence
        );
    }

    /**
     * Projects one catalog operator into the same business-facing Contract model used by graphs.
     *
     * <p>Each named port becomes one property in the input or output object envelope. Runtime
     * behavior comes only from declared operator capabilities; no optimistic effect or
     * idempotency is inferred.</p>
     *
     * @param operator authoritative catalog operator definition
     * @return exact operator Contract projection
     */
    public ContractDraft project(OperatorDefinition operator) {
        if (operator == null) {
            throw new IllegalArgumentException("Operator definition is required for contract projection");
        }
        return new ContractDraft(
                ContractDraft.SCHEMA_VERSION,
                new ContractDraft.Target(
                        ContractDraft.TargetKind.OPERATOR,
                        operator.operatorRef(),
                        0,
                        operator.fingerprint()),
                portSchema(operator.ports().inputs()),
                portSchema(operator.ports().outputs()),
                List.of(),
                executionSemantics(operator.capabilities()),
                List.of(),
                ContractDraft.CompatibilityPolicy.strict(),
                Map.of(),
                ContractDraft.Source.AUTHORED,
                portsAreOpaque(operator) ? ContractDraft.Confidence.OPAQUE : ContractDraft.Confidence.EXACT
        );
    }

    private static SchemaEnvelope portSchema(List<OperatorDefinition.Port> ports) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = ports.stream()
                .filter(OperatorDefinition.Port::required)
                .map(OperatorDefinition.Port::name)
                .toList();
        ports.forEach(port -> properties.put(port.name(), port.schema().schema()));
        return SchemaEnvelope.object(properties, required);
    }

    private static ContractDraft.ExecutionSemantics executionSemantics(
            OperatorDefinition.Capabilities capabilities) {
        ContractDraft.Effect effect = switch (capabilities.effect()) {
            case "PURE" -> ContractDraft.Effect.PURE;
            case "READ", "READ_ONLY" -> ContractDraft.Effect.READ;
            case "WRITE", "WRITE_EXTERNAL" -> ContractDraft.Effect.WRITE;
            default -> ContractDraft.Effect.UNKNOWN;
        };
        ContractDraft.SideEffectProtocol sideEffectProtocol = effect == ContractDraft.Effect.WRITE
                ? sideEffectProtocol(capabilities.sideEffectProtocol())
                : null;
        return new ContractDraft.ExecutionSemantics(
                effect,
                capabilities.idempotency(),
                capabilities.streaming(),
                capabilities.durable(),
                sideEffectProtocol);
    }

    private static ContractDraft.SideEffectProtocol sideEffectProtocol(
            OperatorDefinition.SideEffectProtocol protocol) {
        return new ContractDraft.SideEffectProtocol(
                protocol.schemaVersion() + ":" + protocol.mode(),
                protocol.reconcilerRef(),
                false,
                Map.of(
                        "commitReceiptRequired", protocol.commitReceiptRequired(),
                        "reconciliationRequired", protocol.reconciliationRequired(),
                        "idempotencyKeySource", protocol.idempotencyKeySource(),
                        "reconciliationLookupSource", protocol.reconciliationLookupSource(),
                        "commitReceiptSource", protocol.commitReceiptSource()));
    }

    private static boolean portsAreOpaque(OperatorDefinition operator) {
        return java.util.stream.Stream.concat(
                        operator.ports().inputs().stream(),
                        operator.ports().outputs().stream())
                .anyMatch(port -> schemaIsOpaque(port.schema()));
    }

    private static boolean schemaIsOpaque(SchemaEnvelope envelope) {
        Map<String, Object> schema = envelope.schema();
        return schema.isEmpty()
                || "opaque".equals(schema.get("type"))
                || (schema.size() == 2
                && "object".equals(schema.get("type"))
                && Boolean.TRUE.equals(schema.get("additionalProperties")));
    }

    private static boolean schemaIsOpaque(GraphDraft draft) {
        return draft.inputSchema().schema().isEmpty()
                || draft.outputSchema().schema().isEmpty()
                || "opaque".equals(draft.inputSchema().schema().get("type"))
                || "opaque".equals(draft.outputSchema().schema().get("type"));
    }
}
