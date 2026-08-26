package com.leanowtech.bloge.gateway.testing.world;

import java.util.HashSet;
import java.util.Set;

/**
 * Fail-closed admission boundary for a stateless resource world.
 *
 * <p>The S1-A binding is already proven when it is created, but this service repeats the
 * cross-object identity checks at the world boundary. Tenant scope is carried by the
 * {@link WorldSlice.Registration} proof because the S1-A binding deliberately has no tenant
 * field.</p>
 */
public final class WorldModelAdmissionService {
    public record Admission(String worldModelId, String tenantId, long revision, String fingerprint) {
    }

    private final PureBlogeFragmentValidator purityValidator;

    public WorldModelAdmissionService() {
        this(new PureBlogeFragmentValidator());
    }

    public WorldModelAdmissionService(PureBlogeFragmentValidator purityValidator) {
        if (purityValidator == null) {
            throw new WorldModelException(WorldModelException.Code.INVALID_MODEL);
        }
        this.purityValidator = purityValidator;
    }

    public Admission admit(ResourceWorldModel world) {
        if (world == null || world.worldModelId().isBlank() || world.tenantId().isBlank()
                || world.revision() <= 0 || world.slices().isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.INVALID_MODEL);
        }

        Set<String> coordinates = new HashSet<>();
        for (WorldSlice slice : world.slices()) {
            if (slice == null) {
                throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
            }
            if (!world.tenantId().equals(slice.tenantId())) {
                throw new WorldModelException(WorldModelException.Code.TENANT_DRIFT);
            }
            if (!coordinates.add(slice.coordinate())) {
                throw new WorldModelException(WorldModelException.Code.DUPLICATE_SLICE);
            }
            if (!slice.bindingValid()) {
                throw new WorldModelException(WorldModelException.Code.BINDING_UNAVAILABLE);
            }
            LogicalResourceContract contract = slice.contract();
            LogicalResourceBinding binding = slice.binding();
            if (contract == null || binding == null) {
                throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
            }
            if (!slice.logicalContractId().equals(contract.contractId())
                    || !slice.logicalContractId().equals(binding.contractId())) {
                throw new WorldModelException(WorldModelException.Code.CONTRACT_MISMATCH);
            }
            if (!slice.contractFingerprint().equals(contract.contractFingerprint())
                    || !slice.contractFingerprint().equals(binding.contractFingerprint())) {
                throw new WorldModelException(WorldModelException.Code.CONTRACT_DRIFT);
            }
            if (!slice.provider().equals(binding.provider())
                    || !slice.apiVersion().equals(binding.apiVersion())) {
                throw new WorldModelException(WorldModelException.Code.BINDING_MISMATCH);
            }
            if (!slice.bindingFingerprint().equals(binding.descriptorFingerprint())) {
                throw new WorldModelException(WorldModelException.Code.BINDING_DRIFT);
            }
            if (slice.worldStateSpec() == null || !slice.worldStateSpec().isEmpty()) {
                throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
            }
            purityValidator.validate(slice.behavior());
        }
        return new Admission(world.worldModelId(), world.tenantId(), world.revision(), world.fingerprint());
    }

    public static Admission validate(ResourceWorldModel world) {
        return new WorldModelAdmissionService().admit(world);
    }
}
