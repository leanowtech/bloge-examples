package com.leanowtech.bloge.gateway.visual.fixture;

/**
 * Typed failure for deterministic HTTP mapping of graph-node Fixture promotion.
 *
 * @param status intended HTTP status
 * @param code stable machine-readable failure code
 * @param message operator-safe failure explanation
 */
public final class GraphNodeFixturePromotionException extends RuntimeException {
    /**
     * Serial version for typed HTTP boundary translation.
     */
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    /**
     * Creates a typed client-facing promotion failure.
     */
    public GraphNodeFixturePromotionException(int status, String code, String message) {
        super(message);
        if (status < 400 || status > 499) {
            throw new IllegalArgumentException("Promotion failures must be client responses");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A stable promotion failure code is required");
        }
        this.status = status;
        this.code = code;
    }

    /**
     * Returns the intended HTTP response status.
     */
    public int status() {
        return status;
    }

    /**
     * Returns the stable machine-readable failure code.
     */
    public String code() {
        return code;
    }

    /**
     * Creates a failed lookup or semantic precondition response.
     *
     * @param status intended 4xx status
     * @param code stable failure code
     * @param message operator-safe explanation
     * @return typed failure
     */
    public static GraphNodeFixturePromotionException of(int status, String code, String message) {
        return new GraphNodeFixturePromotionException(status, code, message);
    }
}
