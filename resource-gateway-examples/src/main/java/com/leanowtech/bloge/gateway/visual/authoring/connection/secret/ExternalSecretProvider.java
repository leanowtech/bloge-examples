package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;

import java.util.Objects;

/**
 * External secret provider boundary. Every provider I/O call must happen outside
 * the database transaction. Implementations must be idempotent for one exact
 * attempt: repeating {@link #prepare(SecretOperationContext, SecretSource)} or
 * {@link #activate(SecretOperationContext, PreparedExternalSecret)} with the
 * same inputs returns the same lease/active locator. {@link #abort} must safely
 * cancel either a prepared lease or an activated lease that was not locally
 * committed, including repeated calls.
 */
public interface ExternalSecretProvider {
    /** @return stable provider implementation identity */
    String providerId();

    /**
     * Stages a value or scope-authorized reference for this exact attempt.
     * A VALUE caller retains ownership and must close its {@link DestroyableSecret}
     * in a finally block around this call.
     * @param context exact scoped attempt coordinates
     * @param source caller-owned value or existing scope-bound reference
     * @return provider lease and opaque recovery locator, possibly expired during recovery
     */
    PreparedExternalSecret prepare(SecretOperationContext context, SecretSource source);

    /**
     * Activates the prepared lease for this exact attempt.
     * @param context exact scoped attempt coordinates
     * @param prepared hydrated preparation result, including recovery records
     * @return provider active locator and transient lease identity
     */
    ActivatedExternalSecret activate(SecretOperationContext context, PreparedExternalSecret prepared);

    /**
     * Idempotently compensates a prepared or not-locally-committed activated lease.
     * KEEP_EXISTING must not call this seam.
     * @param context exact scoped attempt coordinates
     * @param prepared hydrated preparation result
     */
    void abort(SecretOperationContext context, PreparedExternalSecret prepared);

    /**
     * Resolves solely from the durable active locator; no lease identity is required.
     * The returned material is caller-owned and must be closed after use.
     * @param context exact scoped attempt coordinates
     * @param binding V003 active binding
     * @return caller-closeable resolved material
     */
    ResolvedExternalSecret resolve(SecretOperationContext context, ActiveSecretBinding binding);

    /**
     * Shared provider-contract guard for a reference source. Providers must call
     * this before any reference I/O; equality is exact over the full AuthoringScope.
     * @param context operation scope
     * @param reference scope-bound opaque reference
     * @throws ExternalSecretProviderException when scopes differ
     */
    static void requireReferenceScope(SecretOperationContext context, SecretReference reference) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reference, "reference");
        if (!context.scope().equals(reference.scope())) {
            throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.UNAUTHORIZED);
        }
    }

    /**
     * Shared source guard to call at the start of every provider implementation.
     * @param context operation scope
     * @param source source supplied by the caller
     */
    static void requireSourceScope(SecretOperationContext context, SecretSource source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        if (source instanceof SecretSource.Reference reference) {
            requireReferenceScope(context, reference.reference());
        }
    }

    /**
     * Coordinator helper for a VALUE operation. Ownership remains with the
     * caller for the duration of {@code prepare}; success and failure both close it.
     * @param provider provider to invoke
     * @param context exact scoped attempt coordinates
     * @param value caller-owned secret value
     * @return provider preparation result
     */
    static PreparedExternalSecret prepareOwnedValue(ExternalSecretProvider provider,
                                                     SecretOperationContext context,
                                                     DestroyableSecret value) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(value, "value");
        try (value) {
            return provider.prepare(context, new SecretSource.Value(value));
        }
    }
}
