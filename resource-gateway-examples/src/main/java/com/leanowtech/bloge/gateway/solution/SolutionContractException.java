package com.leanowtech.bloge.gateway.solution;

/**
 * Stable, payload-free failure raised by solution contract validation or pure evaluation.
 *
 * <p>The exception intentionally carries only a catalog-owned code and generic message. Entity
 * references, predicates and supplied values must not cross the Agent protocol boundary through
 * exception prose.</p>
 */
public final class SolutionContractException extends RuntimeException {
    private final String code;

    /** Creates a stable failure without business payload material. */
    public SolutionContractException(String code, String message) {
        super(message);
        this.code = code == null ? "SOLUTION_CONTRACT_INVALID" : code.trim();
    }

    /** @return stable machine-readable failure code */
    public String code() {
        return code;
    }
}
