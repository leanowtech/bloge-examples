package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown at startup when a {@code ResourceDescriptor} contains an invalid bloge expression
 * that fails to compile.
 *
 * <p>This is a fail-fast validation error: if a descriptor's parameter mapping or
 * {@code BlgeExpression} response protocol references an expression that cannot be parsed
 * or compiled, the application should refuse to start rather than failing at runtime.
 *
 * <p><b>Retry semantics:</b> <em>not</em> retryable — requires a configuration fix.
 */
public class ResourceDescriptorException extends RuntimeException {

    private final String resourceId;
    private final String expression;

    /**
     * @param resourceId the resource descriptor that contains the invalid expression
     * @param expression the expression text that failed to compile
     * @param cause      the underlying compilation error
     */
    public ResourceDescriptorException(String resourceId, String expression, Throwable cause) {
        super("Failed to compile expression in resource '%s': %s".formatted(resourceId, expression), cause);
        this.resourceId = resourceId;
        this.expression = expression;
    }

    /**
     * @param resourceId the resource descriptor that contains the invalid expression
     * @param expression the expression text that failed to compile
     * @param message    a human-readable description of the compilation failure
     */
    public ResourceDescriptorException(String resourceId, String expression, String message) {
        super("Failed to compile expression in resource '%s': %s — %s".formatted(resourceId, expression, message));
        this.resourceId = resourceId;
        this.expression = expression;
    }

    public String resourceId() {
        return resourceId;
    }

    public String expression() {
        return expression;
    }
}
