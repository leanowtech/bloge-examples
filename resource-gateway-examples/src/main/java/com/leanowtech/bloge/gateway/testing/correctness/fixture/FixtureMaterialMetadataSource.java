package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;

import java.util.Optional;

/** Payload-free exact metadata lookup; deliberately has no decrypt or resolve method. */
@FunctionalInterface
public interface FixtureMaterialMetadataSource {

    Optional<Receipt> findReceipt(EnterpriseScope scope, ExactAssetRef materialRef);

    static FixtureMaterialMetadataSource denyAll() {
        return (scope, ref) -> Optional.empty();
    }
}
