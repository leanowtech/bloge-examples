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
    private static final Instant UNTIL = Instant.parse("2020-01-01T00:00:00Z");
    private static final String MAX_LEASE = "l".repeat(256);

    /** @return fresh provider fixture for each contract test */
    protected abstract ExternalSecretProvider provider();

    private static SecretOperationContext context() {
        return context("123e4567-e89b-12d3-a456-426614174000", 1, "attempt-token");
    }

    private static SecretOperationContext context(String commandId, int attemptNo, String attemptToken) {
        return new SecretOperationContext(SCOPE, "123", "connection-test", "conn:1", 3,
                commandId, attemptNo, attemptToken, "token");
    }

    @Test
    void destroyableSecretOnlyBorrowsAndErasesTemporaryCopy() {
        char[] input = "secret".toCharArray();
        DestroyableSecret secret = new DestroyableSecret(input);
        char[][] retained = {null};
        secret.borrow(chars -> retained[0] = chars);
        assertThat(retained[0]).containsOnly('\0');
        assertThatThrownBy(() -> secret.borrow(chars -> {
            retained[0] = chars;
            throw new IllegalStateException("callback failed");
        })).isInstanceOf(IllegalStateException.class);
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
        SecretReference reference = new SecretReference(SCOPE, "vault://opaque-ref");
        SecretSource source = new SecretSource.Reference(reference);
        DestroyableSecret value = new DestroyableSecret("plain-secret".toCharArray());
        SecretSource valueSource = new SecretSource.Value(value);
        PreparedExternalSecret prepared = new PreparedExternalSecret("fake", MAX_LEASE, "opaque-locator", UNTIL, context());
        ActivatedExternalSecret activated = new ActivatedExternalSecret("fake", MAX_LEASE, "active-locator");
        ActiveSecretBinding binding = new ActiveSecretBinding("fake", "active-locator", context().commandId());
        ResolvedExternalSecret resolved = new ResolvedExternalSecret("fake", value);
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(Arrays.asList(context(), source, valueSource, prepared, activated, binding, resolved));
        resolved.close();
        assertThat(json).doesNotContain("attemptToken", "leaseId", "opaqueLocator", "activeLocator", "reference", "plain-secret");
        assertThat(json).doesNotContain("context");
        assertThat(source + " " + valueSource + " " + prepared + " " + activated + " " + binding + " " + resolved)
                .doesNotContain("vault://opaque-ref", "opaque-locator", "active-locator", MAX_LEASE, "plain-secret");
    }

    @Test
    void operationContextUsesAuthoringScopeAndV003Bounds() {
        assertThat(context().scope()).isEqualTo(SCOPE);
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 0, "cmd", 1, "t", "token"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 1, "cmd", 1, "t", "bad"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretOperationContext(SCOPE, "actor", "p", "c", 1, "cmd", 1, "t".repeat(129), "token"));
        assertThat(new PreparedExternalSecret("fake", MAX_LEASE, "locator", UNTIL, context()).leaseId()).hasSize(256);
        assertThat(new ActivatedExternalSecret("fake", MAX_LEASE, "locator").leaseId()).hasSize(256);
        assertThatIllegalArgumentException().isThrownBy(() -> new PreparedExternalSecret("fake", MAX_LEASE + "x", "locator", UNTIL, context()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivatedExternalSecret("fake", MAX_LEASE + "x", "locator"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PreparedExternalSecret("fake", "lease", "x".repeat(2049), UNTIL, context()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActiveSecretBinding("fake", "x".repeat(2049), "cmd"));
    }

    @Test
    void expiredPreparedLeaseCanBeHydratedForAbortRecovery() {
        PreparedExternalSecret prepared = new PreparedExternalSecret("fake", "lease", "opaque", UNTIL, context());
        assertThat(prepared.leaseUntil()).isEqualTo(UNTIL);
    }

    @Test
    void referenceMustUseExactAuthoringScopeAndBindingHasV003Coordinates() {
        SecretReference reference = new SecretReference(SCOPE, "vault://ref");
        ExternalSecretProvider.requireReferenceScope(context(), reference);
        assertThatThrownBy(() -> provider().prepare(
                new SecretOperationContext(new AuthoringScope("other", "123", "prod"), "actor", "p", "c", 1, "cmd", 1, "t", "value"),
                new SecretSource.Reference(reference)))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.UNAUTHORIZED);
        ActiveSecretBinding binding = new ActiveSecretBinding("fake", "active", "command");
        assertThat(binding.commandId()).isEqualTo("command");
    }

    @Test
    void finalTemplatesRejectWrongProviderAndLeaseClosure() {
        assertThatThrownBy(() -> new WrongResultProvider(false).prepare(context(),
                new SecretSource.Reference(new SecretReference(SCOPE, "vault://wrong-provider"))))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);

        assertThatThrownBy(() -> new WrongContextProvider().prepare(context(),
                new SecretSource.Reference(new SecretReference(SCOPE, "vault://wrong-context"))))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);

        WrongResultProvider wrongLease = new WrongResultProvider(true);
        PreparedExternalSecret prepared = wrongLease.prepare(context(), new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://wrong-lease")));
        assertThatThrownBy(() -> wrongLease.activate(context(), prepared))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);

        ExternalSecretProvider provider = provider();
        PreparedExternalSecret valid = provider.prepare(context(), new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://closure")));
        assertThatThrownBy(() -> provider.abort(context(), new PreparedExternalSecret(
                "other", valid.leaseId(), valid.opaqueLocator(), valid.leaseUntil(), valid.context())))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThatThrownBy(() -> provider.resolve(context(), new ActiveSecretBinding(
                "other", "active", context().commandId())))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);

        WrongResultProvider commandGuard = new WrongResultProvider(true);
        assertThatThrownBy(() -> commandGuard.resolve(context(), new ActiveSecretBinding(
                commandGuard.providerId(), "active", "different-command")))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThat(commandGuard.resolveCalls).isZero();

        WrongResultProvider wrongResolve = new WrongResultProvider(true);
        assertThatThrownBy(() -> wrongResolve.resolve(context(), new ActiveSecretBinding(
                wrongResolve.providerId(), "active", context().commandId())))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThat(wrongResolve.resolvedMaterial).isNotNull();
        assertThat(wrongResolve.resolvedMaterial.isClosed()).isTrue();
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
    void providerContractRetainsDeterministicPrepareAndActivation() {
        ExternalSecretProvider provider = provider();
        SecretReference reference = new SecretReference(SCOPE, "vault://ref");
        PreparedExternalSecret first = provider.prepare(context(), new SecretSource.Reference(reference));
        PreparedExternalSecret retry = provider.prepare(context(), new SecretSource.Reference(reference));
        assertThat(retry).isEqualTo(first);
        assertThat(first.context()).isEqualTo(context());
        assertThat(retry.providerId()).isEqualTo(first.providerId());
        assertThat(retry.leaseId()).isEqualTo(first.leaseId());
        ActivatedExternalSecret activated = provider.activate(context(), first);
        ActivatedExternalSecret activatedRetry = provider.activate(context(), first);
        assertThat(activatedRetry).isEqualTo(activated);
        assertThat(activatedRetry.providerId()).isEqualTo(activated.providerId());
        assertThat(activatedRetry.leaseId()).isEqualTo(activated.leaseId());
        assertThat(activatedRetry.activeLocator()).isEqualTo(activated.activeLocator());
    }

    @Test
    void providerAbortIsIdempotentAfterActivationWithoutResolve() {
        ExternalSecretProvider provider = provider();
        SecretReference reference = new SecretReference(SCOPE, "vault://abort");
        PreparedExternalSecret prepared = provider.prepare(context(), new SecretSource.Reference(reference));
        provider.activate(context(), prepared);
        provider.abort(context(), prepared);
        provider.abort(context(), prepared);

        PreparedExternalSecret expired = new PreparedExternalSecret(
                provider.providerId(), "expired", "expired-locator", UNTIL, context());
        provider.abort(context(), expired);
        provider.abort(context(), expired);
    }

    @Test
    void providerResolvesAnUnabortedActivatedLocator() {
        ExternalSecretProvider provider = provider();
        PreparedExternalSecret prepared = provider.prepare(context(), new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://resolve")));
        ActivatedExternalSecret activated = provider.activate(context(), prepared);
        try (ResolvedExternalSecret resolved = provider.resolve(
                context(), new ActiveSecretBinding(provider.providerId(), activated.activeLocator(), context().commandId()))) {
            resolved.material().borrow(chars -> assertThat(chars).containsExactly("material".toCharArray()));
        }
        provider.abort(context(), prepared);
        assertThatThrownBy(() -> provider.resolve(context(), new ActiveSecretBinding(
                provider.providerId(), activated.activeLocator(), context().commandId())))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.NOT_FOUND);
    }

    @Test
    void abortIsExactAttemptScopedAndDoesNotInvalidateAnotherAttempt() {
        ExternalSecretProvider provider = provider();
        SecretOperationContext contextA = context("command-shared", 1, "attempt-a");
        SecretOperationContext contextB = context("command-shared", 2, "attempt-b");
        PreparedExternalSecret preparedA = provider.prepare(contextA, new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://attempt-a")));
        PreparedExternalSecret preparedB = provider.prepare(contextB, new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://attempt-b")));
        ActivatedExternalSecret activeA = provider.activate(contextA, preparedA);
        ActivatedExternalSecret activeB = provider.activate(contextB, preparedB);
        assertThat(preparedA.leaseId()).isNotEqualTo(preparedB.leaseId());
        assertThat(activeA.activeLocator()).isNotEqualTo(activeB.activeLocator());

        assertThatThrownBy(() -> provider.activate(contextB, preparedA))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThatThrownBy(() -> provider.abort(contextB, preparedA))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        provider.abort(contextA, preparedA);
        assertThatThrownBy(() -> provider.resolve(contextA, new ActiveSecretBinding(
                provider.providerId(), activeA.activeLocator(), contextA.commandId())))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.NOT_FOUND);
        try (ResolvedExternalSecret resolved = provider.resolve(contextB, new ActiveSecretBinding(
                provider.providerId(), activeB.activeLocator(), contextB.commandId()))) {
            resolved.material().borrow(chars -> assertThat(chars).containsExactly("material".toCharArray()));
        }
    }

    @Test
    void contextFenceRejectsBeforeProviderHooksAreCalled() {
        TrackingProvider provider = new TrackingProvider();
        SecretOperationContext first = context("command-shared", 1, "token-a");
        SecretOperationContext second = context("command-shared", 2, "token-b");
        PreparedExternalSecret prepared = provider.prepare(first, new SecretSource.Reference(
                new SecretReference(SCOPE, "vault://tracking")));
        int activateCalls = provider.activateCalls;
        int abortCalls = provider.abortCalls;

        assertThatThrownBy(() -> provider.activate(second, prepared))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThatThrownBy(() -> provider.abort(second, prepared))
                .isInstanceOf(ExternalSecretProviderException.class)
                .extracting("code").isEqualTo(ExternalSecretProviderException.Code.INVALID_REQUEST);
        assertThat(provider.activateCalls).isEqualTo(activateCalls);
        assertThat(provider.abortCalls).isEqualTo(abortCalls);
    }

    private static final class ThrowingPrepareProvider extends ExternalSecretProvider {
        private ThrowingPrepareProvider() { super("throwing"); }
        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            throw new ExternalSecretProviderException(ExternalSecretProviderException.Code.PREPARE_FAILED);
        }
        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context, PreparedExternalSecret prepared) { throw new UnsupportedOperationException(); }
        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) { }
        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context, ActiveSecretBinding binding) { throw new UnsupportedOperationException(); }
    }

    private static final class TrackingProvider extends ExternalSecretProvider {
        private int activateCalls;
        private int abortCalls;

        private TrackingProvider() { super("tracking"); }

        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            return new PreparedExternalSecret(providerId(), "lease", "opaque", UNTIL, context);
        }

        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context,
                                                                PreparedExternalSecret prepared) {
            activateCalls++;
            return new ActivatedExternalSecret(providerId(), prepared.leaseId(), "active");
        }

        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) {
            abortCalls++;
        }

        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context,
                                                              ActiveSecretBinding binding) {
            return new ResolvedExternalSecret(providerId(), new DestroyableSecret("material".toCharArray()));
        }
    }

    private static final class WrongResultProvider extends ExternalSecretProvider {
        private final boolean wrongLease;
        private int resolveCalls;
        private DestroyableSecret resolvedMaterial;

        private WrongResultProvider(boolean wrongLease) {
            super("fake");
            this.wrongLease = wrongLease;
        }

        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            return new PreparedExternalSecret(wrongLease ? "fake" : "other", "lease", "opaque", UNTIL, context());
        }

        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context, PreparedExternalSecret prepared) {
            return new ActivatedExternalSecret("fake", wrongLease ? "other-lease" : prepared.leaseId(), "active");
        }

        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) { }

        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context, ActiveSecretBinding binding) {
            resolveCalls++;
            resolvedMaterial = new DestroyableSecret("material".toCharArray());
            return new ResolvedExternalSecret("other", resolvedMaterial);
        }
    }

    private static final class WrongContextProvider extends ExternalSecretProvider {
        private WrongContextProvider() { super("fake"); }

        @Override protected PreparedExternalSecret doPrepare(SecretOperationContext context, SecretSource source) {
            return new PreparedExternalSecret("fake", "lease", "opaque", UNTIL,
                    ExternalSecretProviderContractTest.context("different-command", 1, "different-token"));
        }

        @Override protected ActivatedExternalSecret doActivate(SecretOperationContext context, PreparedExternalSecret prepared) {
            return new ActivatedExternalSecret("fake", prepared.leaseId(), "active");
        }

        @Override protected void doAbort(SecretOperationContext context, PreparedExternalSecret prepared) { }

        @Override protected ResolvedExternalSecret doResolve(SecretOperationContext context, ActiveSecretBinding binding) {
            return new ResolvedExternalSecret("fake", new DestroyableSecret("material".toCharArray()));
        }
    }
}
