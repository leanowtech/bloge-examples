package com.leanowtech.bloge.gateway.solution;

import com.leanowtech.bloge.core.context.GraphContext;

/**
 * Unforgeable in-process authority attached by the governed Solution execution boundary.
 *
 * <p>Public JSON callers cannot construct this object through normal input binding. Built-in
 * operators reject execution unless the server has overwritten the reserved graph-context slot
 * with an issued instance, preventing a caller-authored scope string from selecting another
 * project's contracts.</p>
 */
public final class SolutionExecutionAuthority {
    /** Reserved {@link GraphContext} slot used only for the server-owned capability object. */
    public static final String CONTEXT_KEY = "__rgSolutionExecutionAuthority";

    /** Execution mode visible to effect-aware Instruction dispatch. */
    public enum Mode { SIMULATE, CONTROLLED_TEST, RUNTIME, WRITE_EXEC }

    private final String scopeKey;
    private final Mode mode;

    private SolutionExecutionAuthority(String scopeKey, Mode mode) {
        this.scopeKey = scopeKey;
        this.mode = mode;
    }

    /** Issues one capability inside the solution package after authentication and gate checks. */
    static SolutionExecutionAuthority issue(String scopeKey, Mode mode) {
        if (scopeKey == null || scopeKey.isBlank() || mode == null) {
            throw new IllegalArgumentException("scopeKey and mode are required");
        }
        return new SolutionExecutionAuthority(scopeKey, mode);
    }

    /** Reads the server-owned capability or fails closed. */
    static SolutionExecutionAuthority require(GraphContext context) {
        Object value = context == null ? null : context.get(CONTEXT_KEY);
        if (value instanceof SolutionExecutionAuthority authority) return authority;
        throw new SolutionContractException(
                "SOLUTION_EXECUTION_UNAUTHORIZED", "Solution execution authority is required.");
    }

    String scopeKey() {
        return scopeKey;
    }

    Mode mode() {
        return mode;
    }
}
