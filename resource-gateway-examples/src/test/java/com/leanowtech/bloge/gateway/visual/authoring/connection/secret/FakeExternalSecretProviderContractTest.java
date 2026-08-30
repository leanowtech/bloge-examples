package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.time.Instant;

/** Concrete fake exercising the reusable provider contract without claiming transaction integration. */
class FakeExternalSecretProviderContractTest extends ExternalSecretProviderContractTest {
    @Override
    protected ExternalSecretProvider provider() {
        return new FakeProvider();
    }

    private static final class FakeProvider implements ExternalSecretProvider {
        private static final Instant UNTIL = Instant.now().plusSeconds(60);

        @Override public String providerId() { return "fake"; }

        @Override public PreparedExternalSecret prepare(SecretOperationContext context, SecretSource source) {
            ExternalSecretProvider.requireSourceScope(context, source);
            if (source instanceof SecretSource.Value value) {
                value.secret().borrow(chars -> { });
            }
            return new PreparedExternalSecret("fake", "lease", "opaque", UNTIL);
        }

        @Override public ActivatedExternalSecret activate(SecretOperationContext context, PreparedExternalSecret prepared) {
            return new ActivatedExternalSecret("fake", prepared.leaseId(), "active");
        }

        @Override public void abort(SecretOperationContext context, PreparedExternalSecret prepared) {
            // Idempotent fake compensation: prepared and activated-not-committed are both safe.
        }

        @Override public ResolvedExternalSecret resolve(SecretOperationContext context, ActiveSecretBinding binding) {
            return new ResolvedExternalSecret("fake", new DestroyableSecret("material".toCharArray()));
        }
    }
}
