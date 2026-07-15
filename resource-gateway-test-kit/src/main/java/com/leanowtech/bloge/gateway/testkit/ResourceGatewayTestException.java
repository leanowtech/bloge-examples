package com.leanowtech.bloge.gateway.testkit;

/**
 * Bounded client failure projected from a Resource Gateway problem response or transport failure.
 * The exception deliberately excludes response details, request bodies, and credentials.
 */
public final class ResourceGatewayTestException extends RuntimeException {

    /** HTTP status, or zero when no valid HTTP response was received. */
    private final int status;
    /** Stable machine-readable failure code. */
    private final String code;
    /** Bounded human-readable problem title. */
    private final String title;
    /** Server-provided retryability decision. */
    private final boolean retryable;
    /** Correlation id used to locate server-side diagnostics. */
    private final String correlationId;

    ResourceGatewayTestException(int status, String code, String title, boolean retryable,
                                 String correlationId, Throwable cause) {
        super(message(status, code, title, correlationId), cause);
        this.status = status;
        this.code = bounded(code, 160);
        this.title = bounded(title, 512);
        this.retryable = retryable;
        this.correlationId = bounded(correlationId, 128);
    }

    /**
     * Returns the HTTP status associated with the failure.
     *
     * @return HTTP status, or zero for a local transport/protocol failure
     */
    public int status() {
        return status;
    }

    /**
     * Returns the stable code callers can use for policy decisions.
     *
     * @return stable Resource Gateway or test-kit error code
     */
    public String code() {
        return code;
    }

    /**
     * Returns a safe summary suitable for logs and CI output.
     *
     * @return bounded human-readable title with no problem details
     */
    public String title() {
        return title;
    }

    /**
     * Indicates whether the server considers the operation safe to retry.
     *
     * @return whether the server declared the failure retryable
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Returns the opaque diagnostic correlation id, when one was supplied.
     *
     * @return correlation id suitable for server-side diagnosis
     */
    public String correlationId() {
        return correlationId;
    }

    static ResourceGatewayTestException local(String code, String title, Throwable cause) {
        return new ResourceGatewayTestException(0, code, title, false, "", cause);
    }

    private static String message(int status, String code, String title, String correlationId) {
        return "Resource Gateway test request failed: status=" + status
                + ", code=" + bounded(code, 160)
                + ", title=" + bounded(title, 512)
                + (bounded(correlationId, 128).isEmpty() ? "" : ", correlationId="
                + bounded(correlationId, 128));
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\p{Cntrl}", " ").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
