package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Optional;

/** Immutable, Scope-complete authority for server-attested Proposal implementation bindings. */
public interface CapabilityImplementationBindingRepository {
    /** Creates one immutable binding, or returns the exact existing idempotent value. */
    CreateResult create(StoredCapabilityImplementationBinding binding);

    /** Reads one exact binding in the authenticated Scope. */
    Optional<StoredCapabilityImplementationBinding> find(
            CapabilitySnapshot.Scope scope, String bindingId);

    /** Atomic immutable-create outcome. */
    record CreateResult(
            StoredCapabilityImplementationBinding binding,
            boolean created
    ) {
    }
}
