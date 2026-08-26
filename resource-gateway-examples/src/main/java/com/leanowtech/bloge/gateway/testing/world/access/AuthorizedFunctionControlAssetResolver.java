package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlAsset;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/** Resolves the optional function-control asset through the existing governed catalog boundary. */
public final class AuthorizedFunctionControlAssetResolver {
    private final GovernedCatalogRepository repository;
    private final GovernedAssetReadAuthorizer authorizer;
    private final GovernedAssetMetadataAuthorizer metadataAuthorizer;
    private final Clock clock;

    public AuthorizedFunctionControlAssetResolver(GovernedCatalogRepository repository,
                                                  GovernedAssetReadAuthorizer authorizer,
                                                  GovernedAssetMetadataAuthorizer metadataAuthorizer,
                                                  Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorizer = authorizer == null ? GovernedAssetReadAuthorizer.denyAll() : authorizer;
        this.metadataAuthorizer = metadataAuthorizer == null
                ? GovernedAssetMetadataAuthorizer.denyAll() : metadataAuthorizer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public FunctionControlAsset resolve(TestControlEnvelope envelope,
                                        IntegrationRequestContext context) {
        if (envelope == null || envelope.functionControl() == null || context == null
                || context.tenantId() == null || context.tenantId().isBlank()
                || !SetSupport.TEST_ENVIRONMENTS.contains(context.environmentId().toLowerCase(Locale.ROOT))) {
            throw GovernedAssetAccessException.invalidContext();
        }
        TestAssetReference reference = envelope.functionControl();
        GovernedResourceRef ref;
        try {
            ref = new GovernedResourceRef(context.tenantId(), GovernedCatalogKind.FUNCTION_CONTROL,
                    reference.id(), reference.revision(), reference.fingerprint());
            authorizer.authorize(context, ref);
            GovernedAssetMetadata metadata = repository.findMetadata(ref)
                    .orElseThrow(GovernedAssetAccessException::notFound);
            if (!metadata.exactRef().equals(ref)
                    || !metadata.governanceFingerprint().equals(
                    GovernedAssetMetadata.fingerprint(ref, metadata.governance()))
                    || metadata.governance().retentionExpiresAt() != null
                    && !metadata.governance().retentionExpiresAt().isAfter(clock.instant())) {
                throw GovernedAssetAccessException.integrity();
            }
            metadataAuthorizer.authorize(context, metadata);
            return repository.findExact(ref, FunctionControlAsset.class)
                    .orElseThrow(GovernedAssetAccessException::notFound);
        } catch (GovernedAssetAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw GovernedAssetAccessException.integrity();
        }
    }

    private static final class SetSupport {
        private static final java.util.Set<String> TEST_ENVIRONMENTS =
                java.util.Set.of("test", "staging");
    }
}
