package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Runtime-owned, credential-free boundary used to attest and invoke customer implementations. */
public interface CapabilityImplementationRuntimePort {
    /** @return whether this process has a customer-owned implementation adapter installed */
    default boolean available() {
        return true;
    }

    /** Returns the exact current descriptor for one runtime port in a complete enterprise Scope. */
    Optional<Descriptor> describe(CapabilitySnapshot.Scope scope, String runtimePortRef);

    /** Invokes a previously attested exact binding inside the isolated conformance runtime. */
    Object invoke(CapabilityImplementationBinding binding, Invocation invocation) throws Exception;

    /** Payload-free runtime-owned implementation descriptor. */
    record Descriptor(
            String runtimePortRef,
            String runtimePortFingerprint,
            String implementationVersion,
            String implementationFingerprint,
            String candidateContractFingerprint,
            String runtimeOwner,
            List<String> allowedRegions,
            boolean readOnly,
            boolean stateless,
            Instant attestedAt,
            Instant expiresAt
    ) {
        /** Normalizes collections; the binding service performs request-specific equality checks. */
        public Descriptor {
            allowedRegions = allowedRegions == null ? List.of() : allowedRegions.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank()).distinct().sorted().toList();
        }
    }

    /** Ephemeral invocation values. Implementations must not retain input or output through this port. */
    record Invocation(
            String invocationId,
            String invocationSiteId,
            Object input,
            Instant logicalTime,
            Instant deadline
    ) {
    }

    /** @return fail-closed port used when no implementation runtime is installed */
    static CapabilityImplementationRuntimePort unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements CapabilityImplementationRuntimePort {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<Descriptor> describe(
                CapabilitySnapshot.Scope scope, String runtimePortRef) {
            return Optional.empty();
        }

        @Override
        public Object invoke(
                CapabilityImplementationBinding binding, Invocation invocation) {
            throw new IllegalStateException("Capability implementation runtime is unavailable");
        }
    }
}
