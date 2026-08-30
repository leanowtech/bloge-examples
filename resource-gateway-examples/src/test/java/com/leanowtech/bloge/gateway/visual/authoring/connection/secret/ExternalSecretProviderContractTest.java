package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable provider contract; implementations supply only a provider fixture. */
abstract class ExternalSecretProviderContractTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant:1", "123e4567-e89b-12d3-a456-426614174000", "prod");
    private static final Instant UNTIL = Instant.now().minusSeconds(1);

    /** @return fresh provider fixture for each contract test */
    protected abstract ExternalSecretProvider provider();

    private static SecretOperationContext context() {
        return new SecretOperationContext(SCOPE, "123", "connection-test", "conn:1", 3,
                "123e4567-e89b-12d3-a456-426614174000", 1, "attempt-token", "token");
    }

    @Test
    void destroyableSecretOnlyBorrowsAndErasesTemporaryCopy() {
        char[] input = "secret".toCharArray();
        DestroyableSecret secret = new DestroyableSecret(input);
        char[][] retained = {null};
        secret.borrow(chars -> retained[0] = chars);
        assertThat(retained[0]).containsOnly('\0');
        input[0] = 'X';
        secret.borrow(chars -> assertThat(chars).containsExactly("secret".toCharArray()));
        secret.close();
        assertThatIllegalStateException().isThrownBy(() -> secret.borrow(chars -> { }));
    }

    @Test
    void callerOwnedValueClosesOnSuccessfulAndThrowingPrepare() {
        DestroyableSecret success = new DestroyableSecret("success".toCharArray());
        ExternalSecretProvider.prepareOwnedValue(provider(), context(), success);
        assertThat(success.isClosed()).isTrue();

        DestroyableSecret failure = new DestroyableSecret("failure".toCharArray());
        ExternalSecretProvider throwing = new ThrowingPrepareProvider();
        assertThatThrownBy(() -> ExternalSecretProvider.prepareOwnedValue(throwing, context(), failure))
                .isInstanceOf(ExternalSecretProviderException.class);
        assertThat(failure.isClosed()).isTrue();
    }

    @Test
    void sourceAndInternalDtosHideSensitiveFieldsFromJacksonAndToString() throws Exception {
        SecretReference reference = new SecretReference(SCOPE, "vault://secret");
        SecretSource source = new SecretSource.Reference(reference);
        PreparedExternalSecret prepared = new PreparedExternalSecret("fake", "lease-1", "opaque-locator", UNTIL);
        ActivatedExternalSecret activated = new ActivatedExternalSecret("fake", "lease-1", "active-locator");
        ActiveSecretBinding binding = new ActiveSecretBinding("fake", "active-locator", context().commandId());
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(Arrays.asList(context(), source, prepared, activated, binding));
        assertThat(json).doesNotContain("attemptToken", "leaseId", "opaqueLocator", "activeLocator", "reference");
        assertThat(source + " " + prepared + " " + activated + " " + binding)
                .doesNotContain("vault://secret", "opaque-locator", "active-locator", "lease-1");
    }

    @Test
    void operationContextUsesAuthoringScopeAndV003Bounds() {
        assertThat(context().scope()).isEqualTo(SCOPE);
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 0, "cmd", 1, "t", "token"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 1, "cmd", 1, "t", "bad"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 1, "cmd", 1, "t".repeat(129), "token"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PreparedExternalSecret("fake", "lease", "x".repeat(2049), UNTIL));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActiveSecretBinding("fake", "x".repeat(2049), "cmd"));
    }

    @Test
    void expiredPreparedLeaseCanBeHydratedForAbortRecovery() {
        PreparedExternalSecret prepared = new PreparedExternalSecret("fake", "lease", "opaque", UNTIL);
        assertThat(prepared.leaseUntil()).isEqualTo(UNTIL);
    }

    @Test
    void referenceMustUseExactAuthoringScopeAndBindingHasV003Coordinates() {
        SecretReference reference = new SecretReference(SCOPE, "vault://ref");
        ExternalSecretProvider.requireReferenceScope(context(), reference);
        assertThatThrownBy(() -> ExternalSecretProvider.requireReferenceScope(
                new SecretOperationContext(new AuthoringScope("other", "123", "prod"), "actor", "p", "c", 1, "cmd", 1, "t", "value"), reference))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.UNAUTHORIZED);
        ActiveSecretBinding binding = new ActiveSecretBinding("fake", "active", "command");
        assertThat(binding.commandId()).isEqualTo("command");
    }

    @Test
    void exceptionNormalizesNullToOneSafeCodeAndNeverKeepsCause() {
        ExternalSecretProviderException exception = new ExternalSecretProviderException(null);
        assertThat(exception.code()).isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThat(exception.getMessage()).isEqualTo("INVALID_REQUEST");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.toString()).isEqualTo("ExternalSecretProviderException[code=INVALID_REQUEST]");
    }

    @Test
    void providerContractRetainsDeterministicIdempotentOperations() {
        ExternalSecretProvider provider = provider();
        SecretReference reference = new SecretReference(SCOPE, "vault://ref");
        PreparedExternalSecret first = provider.prepare(context(), new SecretSource.Reference(reference));
        PreparedExternalSecret retry = provider.prepare(context(), new SecretSource.Reference(reference));
        assertThat(retry.leaseId()).isEqualTo(first.leaseId());
        ActivatedExternalSecret activated = provider.activate(context(), first);
        ActivatedExternalSecret activatedRetry = provider.activate(context(), first);
        assertThat(activatedRetry.activeLocator()).isEqualTo(activated.activeLocator());
        provider.abort(context(), first);
        provider.abort(context(), first);
        try (ResolvedExternalSecret resolved = provider.resolve(
                context(), new ActiveSecretBinding(provider.providerId(), activated.activeLocator(), context().commandId()))) {
            resolved.material().borrow(chars -> assertThat(chars).containsExactly("material".toCharArray()));
        }
    }

    private static final class ThrowingPrepareProvider implements ExternalSecretProvider {
        @Override public String providerId() { return "throwing"; }
        @Override public PreparedExternalSecret prepare(SecretOperationContext context, SecretSource source) {
            throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.PREPARE_FAILED);
        }
        @Override public ActivatedExternalSecret activate(SecretOperationContext context, PreparedExternalSecret prepared) { throw new UnsupportedOperationException(); }
        @Override public void abort(SecretOperationContext context, PreparedExternalSecret prepared) { }
        @Override public ResolvedExternalSecret resolve(SecretOperationContext context, ActiveSecretBinding binding) { throw new UnsupportedOperationException(); }
    }
}
