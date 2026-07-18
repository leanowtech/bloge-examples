package com.leanowtech.bloge.gateway.testkit;

import java.time.Duration;
import java.util.Optional;

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
    /** Valid bounded server retry delay, when supplied. */
    private final Duration retryAfter;
    /** Whether the server sent a Retry-After header, including an invalid one. */
    private final boolean retryAfterSpecified;

    ResourceGatewayTestException(int status, String code, String title, boolean retryable,
                                 String correlationId, Throwable cause) {
        this(status, code, title, retryable, correlationId, false, null, cause);
    }

    ResourceGatewayTestException(int status, String code, String title, boolean retryable,
                                 String correlationId, boolean retryAfterSpecified,
                                 Duration retryAfter, Throwable cause) {
        super(message(status, code, title, correlationId), cause);
        this.status = status;
        this.code = bounded(code, 160);
        this.title = bounded(title, 512);
        this.retryable = retryable;
        this.correlationId = bounded(correlationId, 128);
        this.retryAfterSpecified = retryAfterSpecified;
        this.retryAfter = boundedRetryAfter(retryAfter);
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

    /**
     * Returns the validated server retry delay without retaining raw headers or problem details.
     *
     * @return bounded retry delay, when supplied in a valid {@code Retry-After} header
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Indicates that the server supplied a retry directive, even when its value was rejected.
     *
     * <p>Automatic retry code must fail closed when this method is true and
     * {@link #retryAfter()} is empty; treating an invalid long delay as absence could retry before
     * the server permits it.</p>
     *
     * @return whether a {@code Retry-After} header was present
     */
    public boolean retryAfterSpecified() {
        return retryAfterSpecified;
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

    private static Duration boundedRetryAfter(Duration value) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofHours(24)) > 0) {
            return null;
        }
        return value;
    }
}
