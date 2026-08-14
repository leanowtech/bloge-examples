package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.List;
import java.util.Optional;

/** Optimistically locked Package authoring store with immutable revision history. */
public interface DomainCapabilityPackageDraftRepository {
    Optional<StoredDomainCapabilityPackageDraft> find(
            CapabilitySnapshot.Scope scope, String packageId);

    Optional<StoredDomainCapabilityPackageDraft> findRevision(
            CapabilitySnapshot.Scope scope, String packageId, long revision);

    List<StoredDomainCapabilityPackageDraft> revisions(
            CapabilitySnapshot.Scope scope, String packageId);

    List<StoredDomainCapabilityPackageDraft> list(
            CapabilitySnapshot.Scope scope, String afterPackageId, int limit);

    Optional<StoredDomainCapabilityPackageDraft> saveIfRevision(
            long expectedRevision, DomainCapabilityPackageDraft candidate, String actor);
}
