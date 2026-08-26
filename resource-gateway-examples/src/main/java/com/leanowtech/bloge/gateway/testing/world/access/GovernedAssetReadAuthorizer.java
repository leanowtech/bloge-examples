package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;

/** Authorization port for one exact, payload-free governed asset read. */
@FunctionalInterface
public interface GovernedAssetReadAuthorizer {
    void authorize(IntegrationRequestContext trustedContext, GovernedResourceRef exactRef);

    /** Fail closed until a real policy-backed authorizer is supplied. */
    static GovernedAssetReadAuthorizer denyAll() {
        return (trustedContext, exactRef) -> {
            throw GovernedAssetAccessException.denied();
        };
    }
}
