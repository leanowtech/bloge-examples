package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Concrete fake exercising the reusable provider contract without claiming transaction integration. */
class FakeExternalSecretProviderContractTest extends ExternalSecretProviderContractTest {
    @Override
    protected ExternalSecretProvider provider() {
        return new FakeProvider();
    }

    private static final class FakeProvider extends ExternalSecretProvider {
        private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");
        private final Map<String, PreparedExternalSecret> prepared = new HashMap<>();
        private final Map<String, String> materialByLocator = new HashMap<>();
        private final Set<String> abortedLocators = new HashSet<>();

        private FakeProvider() { super("fake"); }

        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            if (source instanceof SecretSource.Value value) {
                value.secret().borrow(chars -> { });
            }
            PreparedExternalSecret result = new PreparedExternalSecret(providerId(), "lease", "opaque", UNTIL);
            prepared.put(result.leaseId(), result);
            return result;
        }

        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context, PreparedExternalSecret prepared) {
            if (!this.prepared.containsKey(prepared.leaseId())) {
                throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.NOT_FOUND);
            }
            ActivatedExternalSecret result = new ActivatedExternalSecret(providerId(), prepared.leaseId(), "active");
            materialByLocator.put(result.activeLocator(), "material");
            return result;
        }

        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) {
            abortedLocators.add(prepared.opaqueLocator());
            abortedLocators.add("active");
        }

        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context, ActiveSecretBinding binding) {
            String material = materialByLocator.get(binding.activeLocator());
            if (material == null || abortedLocators.contains(binding.activeLocator())) {
                throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.NOT_FOUND);
            }
            return new ResolvedExternalSecret(providerId(), new DestroyableSecret(material.toCharArray()));
        }
    }
}
