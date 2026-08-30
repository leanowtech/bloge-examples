package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Provider seam. Implementations must be invoked outside any database transaction. */
public interface ExternalSecretProvider {
    String providerId();
    PreparedExternalSecret prepare(SecretOperationContext context, SecretSource source);
    ActivatedExternalSecret activate(SecretOperationContext context, PreparedExternalSecret prepared);
    /** Idempotent compensation for a prepared lease. KEEP_EXISTING must not call this seam. */
    void abort(SecretOperationContext context, PreparedExternalSecret prepared);
    ResolvedExternalSecret resolve(SecretOperationContext context, ActiveSecretBinding binding);
}
