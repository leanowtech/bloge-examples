package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;

/**
 * Pure Stage 0 factory for the canonical Capability Studio Tool authoring target.
 *
 * <p>This class deliberately has no runtime, registry, persistence, or transport dependency. It
 * produces the same catalog shape as the real {@link CapabilityStudioFeatureToolOperator} and
 * provides the only deterministic retargeting operation used by the golden integration proof.</p>
 */
public final class CapabilityStudioGoldenGovernedTarget {

    public static final String OPERATOR_REF = "tool-cancellation-fee-dispute-handling";
    public static final String CONTRACT_ID = "contract-cancellation-fee-dispute-tool";

    private CapabilityStudioGoldenGovernedTarget() {
    }

    /** Creates the exact catalog operator and its projected Contract draft. */
    public static Target create(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }
        OperatorDefinition operator = operator();
        ContractDraft projected = new ContractDraftProjectionService().project(operator);
        ContractDraft contract = new ContractDraft(
                projected.schemaVersion(),
                new ContractDraft.Target(
                        ContractDraft.TargetKind.OPERATOR,
                        operator.operatorRef(),
                        1,
                        projected.target().fingerprint()),
                projected.inputSchema(),
                projected.outputSchema(),
                projected.errorContract(),
                projected.executionSemantics(),
                projected.invariants(),
                projected.compatibilityPolicy(),
                projected.fieldMetadata(),
                projected.source(),
                projected.confidence());
        return new Target(operator, contract, contract.fingerprint(mapper));
    }

    /**
     * Retargets only the canonical Tool contract references and the Dataset target reference.
     * API/Feature contract coordinates remain untouched, preserving the Dataset's dependency
     * closure while making the tested Tool coordinate exact.
     */
    public static CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection retarget(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            Target target) {
        if (dataset == null || target == null) {
            throw new IllegalArgumentException("Dataset and target are required");
        }
        CapabilityStudioScenarioDatasetProjector.ExactRef originalTarget = dataset.targetRef();
        CapabilityStudioScenarioDatasetProjector.ExactRef exactTarget = new
                CapabilityStudioScenarioDatasetProjector.ExactRef(
                originalTarget.kind(), originalTarget.id(), originalTarget.revision(),
                target.operator().fingerprint(), originalTarget.authority(), originalTarget.scope());
        List<CapabilityStudioScenarioDatasetProjector.ExactRef> contracts = dataset.contractRefs()
                .stream()
                .map(ref -> retargetContract(ref, target))
                .toList();
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = dataset.cases().stream()
                .map(dataCase -> new CapabilityStudioScenarioDatasetProjector.DataCase(
                        dataCase.caseRef(), dataCase.name(), dataCase.businessIntent(),
                        dataCase.category(), dataCase.lifecycle(), dataCase.qualityState(),
                        dataCase.owner(), dataCase.sourceRef(), dataCase.source(), dataCase.oracleRef(),
                        dataCase.oracle(), dataCase.applicableContractRefs().stream()
                                .map(ref -> retargetContract(ref, target))
                                .toList(), dataCase.behaviorProfiles()))
                .toList();
        return new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                dataset.schemaVersion(), dataset.datasetRef(), dataset.name(), dataset.description(),
                dataset.lifecycle(), dataset.classification(), dataset.owner(), exactTarget, contracts,
                cases, dataset.quality());
    }

    private static CapabilityStudioScenarioDatasetProjector.ExactRef retargetContract(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref, Target target) {
        if (!"CONTRACT".equals(ref.kind()) || !CONTRACT_ID.equals(ref.id())) {
            return ref;
        }
        return new CapabilityStudioScenarioDatasetProjector.ExactRef(
                ref.kind(), ref.id(), ref.revision(), target.contractFingerprint(),
                ref.authority(), ref.scope());
    }

    private static OperatorDefinition operator() {
        SchemaEnvelope stringSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA, "2020-12", java.util.Map.of("type", "string"));
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                OPERATOR_REF,
                "1.0.0",
                new OperatorDefinition.Display(
                        "Cancellation dispute handling",
                        "Controlled Capability Studio Tool over the cancellation dispute Feature graph",
                        List.of("capability-studio", "tool", "cancellation")),
                OperatorDefinition.Source.builtIn("java"),
                new OperatorDefinition.Ports(
                        List.of(
                                new OperatorDefinition.Port("orderId", stringSchema, true,
                                        "Demo order id"),
                                new OperatorDefinition.Port("caseId", stringSchema, true,
                                        "Golden Dataset case id")),
                        List.of(new OperatorDefinition.Port(
                                "result", SchemaEnvelope.opaque(), true, "Tool result"))),
                SchemaEnvelope.object(java.util.Map.of(), List.of()),
                new OperatorDefinition.Capabilities("READ", "IDEMPOTENT", false, true, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", OPERATOR_REF, java.util.Map.of()),
                List.of());
    }

    /** Exact catalog/contract pair used by one governed candidate. */
    public record Target(
            OperatorDefinition operator,
            ContractDraft contract,
            String contractFingerprint) {
        public Target {
            if (operator == null || contract == null) {
                throw new IllegalArgumentException("operator and contract are required");
            }
            if (contractFingerprint == null || contractFingerprint.isBlank()) {
                throw new IllegalArgumentException("contractFingerprint is required");
            }
        }

        public ContractDraft.Target exactTarget() {
            return contract.target();
        }
    }
}
