package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;

import java.util.Objects;
import java.util.Optional;

/** Narrows the encrypted repository to receipt-only catalog access. */
public final class RepositoryFixtureMaterialMetadataSource
        implements FixtureMaterialMetadataSource {

    private final FixtureMaterialRepository repository;

    public RepositoryFixtureMaterialMetadataSource(FixtureMaterialRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<Receipt> findReceipt(EnterpriseScope scope, ExactAssetRef materialRef) {
        if (scope == null || materialRef == null
                || !"FIXTURE_MATERIAL".equals(materialRef.kind())) {
            return Optional.empty();
        }
        return repository.find(scope, materialRef.id(), materialRef.revision())
                .filter(stored -> stored.receipt().materialRef().equals(materialRef))
                .map(StoredFixtureMaterial::receipt);
    }
}
