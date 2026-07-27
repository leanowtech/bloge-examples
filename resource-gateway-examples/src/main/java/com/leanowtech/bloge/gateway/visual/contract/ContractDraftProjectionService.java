package com.leanowtech.bloge.gateway.visual.contract;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.springframework.stereotype.Service;

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
        return new ContractDraft(
                ContractDraft.SCHEMA_VERSION,
                new ContractDraft.Target(ContractDraft.TargetKind.GRAPH, targetId, draft.revision(),
                        targetFingerprint),
                draft.inputSchema(),
                draft.outputSchema(),
                List.of(),
                ContractDraft.ExecutionSemantics.unknown(),
                List.of(),
                ContractDraft.CompatibilityPolicy.strict(),
                Map.of(),
                ContractDraft.Source.AUTHORED,
                confidence
        );
    }

    private static boolean schemaIsOpaque(GraphDraft draft) {
        return draft.inputSchema().schema().isEmpty()
                || draft.outputSchema().schema().isEmpty()
                || "opaque".equals(draft.inputSchema().schema().get("type"))
                || "opaque".equals(draft.outputSchema().schema().get("type"));
    }
}
