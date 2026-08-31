package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** Exact committed Flow draft authority returned by persistence reads. */
public record ReusableFlowStoredDraft(ReusableFlowDraft draft,
                                      ReusableFlowSaveReceipt receipt,
                                      String strongEtag) {
    public ReusableFlowStoredDraft {
        if (draft == null || receipt == null || !receipt.flowId().equals(draft.flowId())
                || !receipt.draft().equals(draft.subject()) || !ReusableFlowStrongEtag.isValid(strongEtag)) {
            throw new IllegalArgumentException("stored reusable Flow draft is invalid");
        }
    }
}
