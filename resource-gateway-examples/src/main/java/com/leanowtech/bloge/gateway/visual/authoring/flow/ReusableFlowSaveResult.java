package com.leanowtech.bloge.gateway.visual.authoring.flow;

/** Application result carrying the exact saved draft, wire receipt and opaque strong validator. */
public record ReusableFlowSaveResult(ReusableFlowDraft draft, ReusableFlowSaveReceipt receipt,
                                     String strongEtag, boolean replayed) {
    public ReusableFlowSaveResult {
        if (draft == null || receipt == null || strongEtag == null
                || !strongEtag.startsWith("\"") || !strongEtag.endsWith("\"")
                || !receipt.flowId().equals(draft.flowId()) || !receipt.draft().equals(draft.subject())) {
            throw new IllegalArgumentException("reusable Flow save result is invalid");
        }
    }
}
