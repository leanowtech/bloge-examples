package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ExternalSecretProviderContractTest {
    private static final Instant UNTIL = Instant.now().plusSeconds(60);
    private static SecretOperationContext context() {
        return new SecretOperationContext("tenant/acme", "actor-1", "connection-test", "conn-1", 3, "cmd-1", 1, "attempt-token", "primary");
    }

    @Test void secretCopiesInputAndOutputAndClosesIdempotently() {
        char[] input = "secret".toCharArray(); var secret = new DestroyableSecret(input); input[0] = 'X';
        char[] output = secret.value(); output[0] = 'X';
        assertThat(secret.value()).containsExactly("secret".toCharArray());
        secret.close(); secret.close(); assertThat(secret.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(secret::value);
    }

    @Test void closeErasesBackingArrayEvenWhenClientRetainsConstructorInput() {
        char[] input = "secret".toCharArray(); var secret = new DestroyableSecret(input); secret.close();
        assertThat(input).containsExactly("secret".toCharArray());
    }

    @Test void sourceAndDtosRedactSensitiveFields() {
        var source = new SecretSource.Reference("vault/secret");
        var prepared = new PreparedExternalSecret("vault", "lease-1", "opaque-locator", UNTIL);
        var activated = new ActivatedExternalSecret("vault", "lease-1", "active-locator");
        var binding = new ActiveSecretBinding("vault", "lease-1", "active-locator");
        assertThat(source + " " + prepared + " " + activated + " " + binding)
                .doesNotContain("vault/secret", "opaque-locator", "active-locator", "lease-1");
    }

    @Test void valueAndReferenceValidateAndValueIsClosedByResolvedSecret() {
        var resolved = new ResolvedExternalSecret("vault", new DestroyableSecret("x".toCharArray()));
        resolved.close(); assertThat(resolved.material().isClosed()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretSource.Reference(""));
    }

    @Test void rejectsInvalidContextFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext("", "a", "p", "c", 0, "cmd", 1, "t", "slot"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext("scope", "a", "p", "c", -1, "cmd", 1, "t", "slot"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext("scope", "a", "p", "c", 0, "cmd", 0, "t", "slot"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext("scope", "a", "p", "c", 0, "cmd", 1, "t", "bad slot"));
    }

    @Test void rejectsInvalidProviderIdentifiersAndExpiry() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PreparedExternalSecret("bad id", "lease", "locator", UNTIL));
        assertThatIllegalArgumentException().isThrownBy(() -> new PreparedExternalSecret("p", "lease", "locator", Instant.now().minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivatedExternalSecret("bad id", "lease", "locator"));
    }

    @Test void exceptionHasStableSafeCodeAndRendering() {
        var exception = new ExternalSecretProviderException(ExternalSecretProviderException.Code.RESOLVE_FAILED, new RuntimeException("secret-value"));
        assertThat(exception.code()).isEqualTo(ExternalSecretProviderException.Code.RESOLVE_FAILED);
        assertThat(exception.toString()).doesNotContain("secret-value");
        assertThat(exception.getMessage()).isEqualTo("RESOLVE_FAILED");
    }

    @Test void fakeProviderExecutesPrepareActivateAbortResolveAndAbortIsIdempotent() {
        var provider = new FakeProvider(); var prepared = provider.prepare(context(), new SecretSource.Reference("vault/ref"));
        var activated = provider.activate(context(), prepared); provider.abort(context(), prepared); provider.abort(context(), prepared);
        try (var resolved = provider.resolve(context(), new ActiveSecretBinding("fake", prepared.leaseId(), activated.activeLocator()))) {
            assertThat(resolved.material().value()).containsExactly("material".toCharArray());
        }
        assertThat(provider.calls).containsExactly("prepare", "activate", "abort", "abort", "resolve");
    }

    @Test void providerSeamIsCalledWithoutSpringTransaction() {
        var provider = new FakeProvider(); provider.prepare(context(), new SecretSource.Reference("vault/ref"));
        assertThat(provider.sawTransaction).isFalse();
    }

    @Test void secretMaterialIsErasedWhenResolveClientClosesAfterUse() {
        var material = new DestroyableSecret("material".toCharArray()); var resolved = new ResolvedExternalSecret("fake", material);
        resolved.close(); assertThat(material.isClosed()).isTrue();
    }

    @Test void providerExceptionDoesNotRetainASecretCause() {
        var exception = new ExternalSecretProviderException(ExternalSecretProviderException.Code.PREPARE_FAILED,
                new RuntimeException("sensitive-provider-payload"));
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getMessage()).doesNotContain("sensitive-provider-payload");
    }

    @Test void providerClosesValueOnPrepareFailure() {
        var material = new DestroyableSecret("secret".toCharArray());
        try {
            try { throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.PREPARE_FAILED); }
            finally { material.close(); }
        } catch (ExternalSecretProviderException expected) { }
        assertThat(material.isClosed()).isTrue();
    }

    @Test void referenceMustBeAuthorizedInTheOperationScope() {
        var provider = new FakeProvider();
        assertThatIllegalArgumentException().isThrownBy(() -> provider.prepare(
                new SecretOperationContext("tenant/other", "actor-1", "connection-test", "conn-1", 3, "cmd-1", 1, "attempt-token", "primary"),
                new SecretSource.Reference("vault/ref")));
    }

    private static final class FakeProvider implements ExternalSecretProvider {
        final List<String> calls = new ArrayList<>(); boolean sawTransaction;
        public String providerId() { return "fake"; }
        public PreparedExternalSecret prepare(SecretOperationContext c, SecretSource s) { observe("prepare"); if (s instanceof SecretSource.Reference && !c.scope().equals("tenant/acme")) throw new IllegalArgumentException("reference is not authorized"); return new PreparedExternalSecret("fake", "lease", "opaque", UNTIL); }
        public ActivatedExternalSecret activate(SecretOperationContext c, PreparedExternalSecret p) { observe("activate"); return new ActivatedExternalSecret("fake", p.leaseId(), "active"); }
        public void abort(SecretOperationContext c, PreparedExternalSecret p) { observe("abort"); }
        public ResolvedExternalSecret resolve(SecretOperationContext c, ActiveSecretBinding b) { observe("resolve"); return new ResolvedExternalSecret("fake", new DestroyableSecret("material".toCharArray())); }
        private void observe(String call) { calls.add(call); sawTransaction |= TransactionSynchronizationManager.isActualTransactionActive(); }
    }
}
