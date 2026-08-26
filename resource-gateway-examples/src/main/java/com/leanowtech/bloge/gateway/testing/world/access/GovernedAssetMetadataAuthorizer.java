package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;

/** Authorization port for trusted, payload-free governance metadata. */
@FunctionalInterface
public interface GovernedAssetMetadataAuthorizer {
    void authorize(IntegrationRequestContext trustedContext, GovernedAssetMetadata exactMetadata);

    /** Fail closed until a policy-backed metadata authorizer is supplied. */
    static GovernedAssetMetadataAuthorizer denyAll() {
        return (trustedContext, exactMetadata) -> {
            throw GovernedAssetAccessException.denied();
        };
    }
}
