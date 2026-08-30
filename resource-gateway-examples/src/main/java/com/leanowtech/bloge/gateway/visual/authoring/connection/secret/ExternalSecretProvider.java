package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;

import java.util.Objects;

/**
 * Template boundary for an external secret provider. All public lifecycle
 * methods are final: the wrapper performs null, scope and provider-identity
 * checks before or after invoking a protected provider SPI hook. Provider I/O
 * must occur outside the database transaction.
 *
 * <p>For one exact attempt, repeated prepare and activate calls return the
 * same lease and active locator. Abort idempotently cancels a prepared lease or
 * an activated lease not locally committed, including recovery retries.</p>
 */
public abstract class ExternalSecretProvider {
    private final String providerId;

    /**
     * Creates a provider with a stable implementation identity.
     * @param providerId provider identity used for every lifecycle closure
     */
    protected ExternalSecretProvider(String providerId) {
        this.providerId = SecretValidation.identifier(providerId, "providerId");
    }

    /** @return stable provider implementation identity */
    public final String providerId() { return providerId; }

    /**
     * Stages a value or scope-authorized reference for this exact attempt.
     * A VALUE caller retains ownership and must close its {@link DestroyableSecret}
     * in a finally block around this call.
     * @param context exact scoped attempt coordinates
     * @param source caller-owned value or existing scope-bound reference
     * @return provider lease and opaque recovery locator, possibly expired during recovery
     */
    public final PreparedExternalSecret prepare(SecretOperationContext context, SecretSource source) {
        requireSourceScope(context, source);
        PreparedExternalSecret result = require(doPrepare(context, source));
        requireProvider(result.providerId());
        return result;
    }

    /**
     * Provider SPI called after the final prepare template has checked source scope.
     * @param context exact scoped attempt coordinates
     * @param source source already checked by the template
     * @return prepared provider lease
     */
    protected abstract PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source);

    /**
     * Activates a prepared lease for this exact attempt.
     * @param context exact scoped attempt coordinates
     * @param prepared hydrated preparation result, including recovery records
     * @return provider active locator and transient lease identity
     */
    public final ActivatedExternalSecret activate(SecretOperationContext context,
                                                   PreparedExternalSecret prepared) {
        requireContext(context);
        requirePrepared(prepared);
        ActivatedExternalSecret result = require(doActivate(context, prepared));
        requireProvider(result.providerId());
        if (!prepared.leaseId().equals(result.leaseId())) invalid();
        return result;
    }

    /**
     * Provider SPI called only after the final activate template has checked input identity.
     * @param context exact scoped attempt coordinates
     * @param prepared provider preparation result
     * @return activated provider lease and locator
     */
    protected abstract ActivatedExternalSecret doActivate(SecretOperationContext context,
                                                          PreparedExternalSecret prepared);

    /**
     * Idempotently compensates a prepared or not-locally-committed activated lease.
     * KEEP_EXISTING must not call this seam.
     * @param context exact scoped attempt coordinates
     * @param prepared hydrated preparation result
     */
    public final void abort(SecretOperationContext context, PreparedExternalSecret prepared) {
        requireContext(context);
        requirePrepared(prepared);
        doAbort(context, prepared);
    }

    /**
     * Provider SPI called only after the final abort template has checked input identity.
     * @param context exact scoped attempt coordinates
     * @param prepared provider preparation result
     */
    protected abstract void doAbort(SecretOperationContext context, PreparedExternalSecret prepared);

    /**
     * Resolves solely from the durable active locator; no lease identity is required.
     * The returned material is caller-owned and must be closed after use.
     * @param context exact scoped attempt coordinates
     * @param binding V003 active binding
     * @return caller-closeable resolved material
     */
    public final ResolvedExternalSecret resolve(SecretOperationContext context, ActiveSecretBinding binding) {
        requireContext(context);
        if (binding == null) invalid();
        requireProvider(binding.providerId());
        ResolvedExternalSecret result = require(doResolve(context, binding));
        requireProvider(result.providerId());
        return result;
    }

    /**
     * Provider SPI called only after the final resolve template has checked binding identity.
     * @param context exact scoped attempt coordinates
     * @param binding V003 active binding
     * @return caller-closeable resolved material
     */
    protected abstract ResolvedExternalSecret doResolve(SecretOperationContext context,
                                                         ActiveSecretBinding binding);

    /**
     * Shared provider-contract guard for a reference source. Equality is exact
     * over the full AuthoringScope and must happen before reference I/O.
     * @param context operation scope
     * @param reference scope-bound opaque reference
     * @throws ExternalSecretProviderException when scopes differ
     */
    public static void requireReferenceScope(SecretOperationContext context, SecretReference reference) {
        requireContext(context);
        if (reference == null || !context.scope().equals(reference.scope())) {
            throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.UNAUTHORIZED);
        }
    }

    /**
     * Checks the operation source before provider I/O.
     * @param context operation scope
     * @param source source supplied by the caller
     */
    public static void requireSourceScope(SecretOperationContext context, SecretSource source) {
        requireContext(context);
        if (source == null) invalid();
        if (source instanceof SecretSource.Reference reference) {
            requireReferenceScope(context, reference.reference());
        }
    }

    /**
     * Coordinator helper for a VALUE operation. Ownership remains with the
     * caller for the duration of prepare; success and failure both close it.
     * @param provider provider to invoke
     * @param context exact scoped attempt coordinates
     * @param value caller-owned secret value
     * @return provider preparation result
     */
    public static PreparedExternalSecret prepareOwnedValue(ExternalSecretProvider provider,
                                                            SecretOperationContext context,
                                                            DestroyableSecret value) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(value, "value");
        try (value) {
            return provider.prepare(context, new SecretSource.Value(value));
        }
    }

    private void requirePrepared(PreparedExternalSecret prepared) {
        if (prepared == null) invalid();
        requireProvider(prepared.providerId());
    }

    private static void requireContext(SecretOperationContext context) {
        if (context == null) invalid();
    }

    private void requireProvider(String actualProviderId) {
        if (!providerId.equals(actualProviderId)) invalid();
    }

    private static <T> T require(T value) {
        if (value == null) invalid();
        return value;
    }

    private static void invalid() {
        throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.INVALID_REQUEST);
    }
}
