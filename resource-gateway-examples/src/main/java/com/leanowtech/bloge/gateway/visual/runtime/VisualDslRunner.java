package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.List;

/**
 * Port used by the visual orchestration core to compile and execute generated BLOGE DSL.
 *
 * <p>Implementations belong to the hosting application. In this example project the adapter delegates
 * to the resource-gateway dynamic composer, while the visual package depends only on this interface.</p>
 */
public interface VisualDslRunner {

    /**
     * Compiles and executes one generated DSL graph.
     *
     * @param request generated DSL plus runtime context
     * @return adapter run result in the visual-owned response shape
     */
    VisualDslRunResponse run(VisualDslRunRequest request);

    /** Returns the latest process-local lifecycle view for a controlled run. */
    default VisualRunControlResult runControl(String requestId, String fencingToken) {
        return new VisualRunControlResult(false, "RG.RUN_CONTROL.UNSUPPORTED",
                "This DSL runner does not support controlled runs.", VisualRunControlView.unmanaged());
    }

    /** Requests cooperative cancellation of a controlled run. */
    default VisualRunControlResult cancel(VisualRunControlCommand command) {
        return new VisualRunControlResult(false, "RG.RUN_CONTROL.UNSUPPORTED",
                "This DSL runner does not support controlled runs.", VisualRunControlView.unmanaged());
    }

    /**
     * Compiles DSL without executing it.
     *
     * @param dsl generated DSL source
     * @return compile diagnostics in the visual-owned diagnostic shape
     */
    List<VisualDslRunResponse.Diagnostic> compileDiagnostics(String dsl);
}
