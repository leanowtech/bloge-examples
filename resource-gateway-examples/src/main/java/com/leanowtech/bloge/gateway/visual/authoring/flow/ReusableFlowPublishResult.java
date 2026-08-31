package com.leanowtech.bloge.gateway.visual.authoring.flow;

import java.util.Objects;

/** Publication result with exact immutable version and replay evidence. */
public record ReusableFlowPublishResult(ReusableFlowVersion version,
                                        ReusableFlowPublishReceipt receipt,
                                        boolean replayed) {
    public ReusableFlowPublishResult {
        version = Objects.requireNonNull(version, "version");
        receipt = Objects.requireNonNull(receipt, "receipt");
    }
}
