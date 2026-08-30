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

    static final class FakeProvider extends ExternalSecretProvider {
        private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");
        private final Map<String, PreparedExternalSecret> prepared = new HashMap<>();
        private final Map<String, ActivatedExternalSecret> activatedByLease = new HashMap<>();
        private final Map<String, String> materialByLocator = new HashMap<>();
        private final Set<String> abortedLocators = new HashSet<>();
        int activateCalls;
        int abortCalls;

        private FakeProvider() { super("fake"); }

        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            if (source instanceof SecretSource.Value value) {
                value.secret().borrow(chars -> { });
            }
            String key = attemptKey(context, source);
            PreparedExternalSecret existing = prepared.get(key);
            if (existing != null) return existing;
            String suffix = context.commandId() + "-" + context.attemptNo();
            PreparedExternalSecret result = new PreparedExternalSecret(providerId(), "lease-" + suffix,
                    "opaque-" + suffix, UNTIL, context);
            prepared.put(key, result);
            return result;
        }

        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context, PreparedExternalSecret prepared) {
            activateCalls++;
            ActivatedExternalSecret existing = activatedByLease.get(prepared.leaseId());
            if (existing != null) return existing;
            if (!this.prepared.containsValue(prepared)) {
                throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.NOT_FOUND);
            }
            ActivatedExternalSecret result = new ActivatedExternalSecret(providerId(), prepared.leaseId(),
                    "active-" + prepared.leaseId().substring("lease-".length()));
            activatedByLease.put(prepared.leaseId(), result);
            materialByLocator.put(result.activeLocator(), "material");
            return result;
        }

        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) {
            abortCalls++;
            abortedLocators.add(prepared.opaqueLocator());
            ActivatedExternalSecret activation = activatedByLease.get(prepared.leaseId());
            if (activation != null) abortedLocators.add(activation.activeLocator());
        }

        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context, ActiveSecretBinding binding) {
            String material = materialByLocator.get(binding.activeLocator());
            if (material == null || abortedLocators.contains(binding.activeLocator())) {
                throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.NOT_FOUND);
            }
            return new ResolvedExternalSecret(providerId(), new DestroyableSecret(material.toCharArray()));
        }

        private static String attemptKey(SecretOperationContext context, SecretSource source) {
            String sourceKey = source instanceof SecretSource.Reference reference
                    ? reference.reference().ref() : "value";
            return context.commandId() + ":" + context.attemptNo() + ":"
                    + context.attemptToken() + ":" + context.slot() + ":" + sourceKey;
        }
    }
}
