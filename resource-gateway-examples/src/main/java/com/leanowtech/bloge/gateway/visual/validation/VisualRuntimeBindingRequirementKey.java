package com.leanowtech.bloge.gateway.visual.validation;

/**
 * Stable key contract for runtime binding requirements.
 */
public final class VisualRuntimeBindingRequirementKey {

    private VisualRuntimeBindingRequirementKey() {
    }

    /**
     * Builds the stable key shared by import results, import readiness, and the runtime-binding requirement index.
     *
     * @param targetKind draft, publication, or operator-library
     * @param targetId target asset id
     * @param nodeId node id or operatorRef that needs the binding
     * @param bindingKind missing binding kind
     * @param bindingTarget binding route or operator target
     * @param artifactKind publication artifact kind, blank for drafts
     * @return stable requirement key
     */
    public static String stable(String targetKind,
                                String targetId,
                                String nodeId,
                                String bindingKind,
                                String bindingTarget,
                                String artifactKind) {
        return String.join("|",
                "RUNTIME_BINDING",
                targetKind == null ? "" : targetKind,
                targetId == null ? "" : targetId,
                nodeId == null ? "" : nodeId,
                bindingKind == null ? "" : bindingKind,
                bindingTarget == null ? "" : bindingTarget,
                artifactKind == null ? "" : artifactKind
        );
    }
}
