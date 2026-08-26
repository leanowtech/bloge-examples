package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlAsset;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetGovernance;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies metadata authorization is a strict payload-read gate for function-control assets. */
class AuthorizedFunctionControlAssetResolverTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void unauthorizedReferenceNeverReadsMetadataPayload() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, ref) -> { throw GovernedAssetAccessException.denied(); },
                (context, metadata) -> { throw new AssertionError("metadata policy reached"); },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-a"), GovernedAssetAccessException.Code.ACCESS_DENIED);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
        verify(repository, never()).findMetadata(any());
    }

    @Test
    void tenantMismatchCannotTurnIntoPayloadRead() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, ref) -> { }, (context, metadata) -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-b"), GovernedAssetAccessException.Code.REFERENCE_NOT_FOUND);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
    }

    @Test
    void missingReferenceNeverReadsPayload() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        when(repository.findMetadata(any())).thenReturn(Optional.empty());
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, ref) -> { }, (context, metadata) -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-a"), GovernedAssetAccessException.Code.REFERENCE_NOT_FOUND);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
    }

    @Test
    void tamperedMetadataFingerprintNeverReadsPayload() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        GovernedResourceRef ref = ref("tenant-a");
        GovernedAssetGovernance governance = GovernedAssetGovernance.safeDefaults();
        when(repository.findMetadata(ref)).thenReturn(Optional.of(
                new GovernedAssetMetadata(ref, governance, "sha256:" + "b".repeat(64))));
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, exactRef) -> { }, (context, metadata) -> {
                    throw new AssertionError("metadata policy reached");
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-a"), GovernedAssetAccessException.Code.INTEGRITY_FAILURE);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
    }

    @Test
    void expiredMetadataNeverReadsPayload() {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        GovernedResourceRef ref = ref("tenant-a");
        GovernedAssetGovernance governance = new GovernedAssetGovernance(
                com.leanowtech.bloge.gateway.testing.world.persistence.GovernedPayloadOrigin.SYNTHETIC,
                com.leanowtech.bloge.gateway.testing.world.persistence.GovernedSecurityClassification.PUBLIC,
                NOW.minusSeconds(1), GovernedAssetGovernance.BUILTIN_SYNTHETIC_PUBLIC_POLICY, null);
        when(repository.findMetadata(ref)).thenReturn(Optional.of(
                new GovernedAssetMetadata(ref, governance,
                        GovernedAssetMetadata.fingerprint(ref, governance))));
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, exactRef) -> { }, (context, metadata) -> {
                    throw new AssertionError("metadata policy reached");
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-a"), GovernedAssetAccessException.Code.INTEGRITY_FAILURE);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
    }

    @Test
    void metadataPolicyDenialNeverReadsPayload() {
        GovernedCatalogRepository repository = metadataRepository("tenant-a", validMetadata("tenant-a"));
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, ref) -> { }, (context, metadata) -> {
                    throw GovernedAssetAccessException.denied();
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(resolver, context("tenant-a"), GovernedAssetAccessException.Code.ACCESS_DENIED);
        verify(repository, never()).findExact(any(GovernedResourceRef.class),
                eq(FunctionControlAsset.class));
    }

    @Test
    void payloadReadOccursExactlyOnceOnlyAfterBothAuthorizationGates() {
        GovernedResourceRef ref = ref("tenant-a");
        GovernedCatalogRepository repository = metadataRepository("tenant-a", validMetadata("tenant-a"));
        FunctionControlAsset asset = mock(FunctionControlAsset.class);
        when(repository.findExact(ref, FunctionControlAsset.class)).thenReturn(Optional.of(asset));
        AuthorizedFunctionControlAssetResolver resolver = resolver(repository,
                (context, exactRef) -> { }, (context, metadata) -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(resolver.resolve(envelope(), context("tenant-a"))).isSameAs(asset);
        verify(repository).findMetadata(ref);
        verify(repository).findExact(ref, FunctionControlAsset.class);
    }

    private static AuthorizedFunctionControlAssetResolver resolver(
            GovernedCatalogRepository repository,
            GovernedAssetReadAuthorizer authorizer,
            GovernedAssetMetadataAuthorizer metadataAuthorizer,
            Clock clock) {
        return new AuthorizedFunctionControlAssetResolver(repository, authorizer, metadataAuthorizer, clock);
    }

    private static GovernedCatalogRepository metadataRepository(String tenant,
                                                                GovernedAssetMetadata metadata) {
        GovernedCatalogRepository repository = mock(GovernedCatalogRepository.class);
        when(repository.findMetadata(ref(tenant))).thenReturn(Optional.of(metadata));
        return repository;
    }

    private static GovernedAssetMetadata validMetadata(String tenant) {
        GovernedResourceRef ref = ref(tenant);
        GovernedAssetGovernance governance = GovernedAssetGovernance.safeDefaults();
        return new GovernedAssetMetadata(ref, governance,
                GovernedAssetMetadata.fingerprint(ref, governance));
    }

    private static GovernedResourceRef ref(String tenant) {
        return new GovernedResourceRef(tenant, GovernedCatalogKind.FUNCTION_CONTROL,
                "function-ref", 1, FINGERPRINT);
    }

    private static TestControlEnvelope envelope() {
        return new TestControlEnvelope("GRAPH_CONTRACT_TEST", null,
                new TestAssetReference("world-ref", 1, FINGERPRINT), "correlation-1",
                new TestAssetReference("function-ref", 1, FINGERPRINT));
    }

    private static IntegrationRequestContext context(String tenant) {
        return new IntegrationRequestContext(tenant, "org-a", "project-a", "test", "local",
                "WORKLOAD", "test-runner", "", "GRAPH_CONTRACT_TEST", "correlation-1",
                Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static void assertCode(AuthorizedFunctionControlAssetResolver resolver,
                                   IntegrationRequestContext context,
                                   GovernedAssetAccessException.Code code) {
        assertThatThrownBy(() -> resolver.resolve(envelope(), context))
                .isInstanceOfSatisfying(GovernedAssetAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
