package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;

/** One provider/API-version implementation slice of an immutable resource world. */
public final class WorldSlice {
    /**
     * Explicit registration proof for metadata that the S1-A binding does not own.
     * The world boundary never infers tenant scope or implementation availability.
     */
    public record Registration(
            String tenantId,
            String provider,
            String apiVersion,
            String contractId,
            String contractFingerprint,
            String bindingFingerprint,
            boolean valid
    ) {
    }

    private final String tenantId;
    private final String provider;
    private final String apiVersion;
    private final String logicalContractId;
    private final String contractFingerprint;
    private final String bindingFingerprint;
    private final boolean bindingValid;
    private final LogicalResourceContract contract;
    private final LogicalResourceBinding binding;
    private final BlogeFragmentRef behavior;
    private final WorldStateSpec state;
    private final String fingerprint;

    private WorldSlice(Registration registration,
                       LogicalResourceContract contract,
                       LogicalResourceBinding binding,
                       BlogeFragmentRef behavior,
                       WorldStateSpec state) {
        this.tenantId = required(registration.tenantId());
        this.provider = required(registration.provider());
        this.apiVersion = required(registration.apiVersion());
        this.bindingFingerprint = required(registration.bindingFingerprint());
        this.bindingValid = registration.valid();
        if (contract == null || binding == null || behavior == null || state == null) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        this.contract = contract;
        this.binding = binding;
        this.logicalContractId = contract.contractId();
        this.contractFingerprint = contract.contractFingerprint();
        this.behavior = behavior;
        this.state = state;
        this.fingerprint = VisualBundleFingerprint.fromMaterial(material());
    }

    public static WorldSlice register(Registration registration,
                                      LogicalResourceContract contract,
                                      LogicalResourceBinding binding,
                                      BlogeFragmentRef behavior,
                                      StateSpec state) {
        if (registration == null || contract == null || binding == null) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        if (!registration.valid()) {
            throw new WorldModelException(WorldModelException.Code.BINDING_UNAVAILABLE);
        }
        if (!same(registration.contractId(), contract.contractId())
                || !same(registration.contractId(), binding.contractId())
                || !same(registration.contractFingerprint(), contract.contractFingerprint())
                || !same(registration.contractFingerprint(), binding.contractFingerprint())) {
            throw new WorldModelException(WorldModelException.Code.CONTRACT_DRIFT);
        }
        if (!same(registration.provider(), binding.provider())
                || !same(registration.apiVersion(), binding.apiVersion())
                || !same(registration.bindingFingerprint(), binding.descriptorFingerprint())) {
            throw new WorldModelException(WorldModelException.Code.BINDING_DRIFT);
        }
        if (state == null || !state.isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
        }
        return new WorldSlice(registration, contract, binding, behavior, state);
    }

    public static WorldSlice register(Registration registration,
                                      LogicalResourceContract contract,
                                      LogicalResourceBinding binding,
                                      BlogeFragmentRef behavior,
                                      WorldStateSpec state) {
        if (state == null) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        if (registration == null || contract == null || binding == null) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        if (!registration.valid()) {
            throw new WorldModelException(WorldModelException.Code.BINDING_UNAVAILABLE);
        }
        if (!same(registration.contractId(), contract.contractId())
                || !same(registration.contractId(), binding.contractId())
                || !same(registration.contractFingerprint(), contract.contractFingerprint())
                || !same(registration.contractFingerprint(), binding.contractFingerprint())) {
            throw new WorldModelException(WorldModelException.Code.CONTRACT_DRIFT);
        }
        if (!same(registration.provider(), binding.provider())
                || !same(registration.apiVersion(), binding.apiVersion())
                || !same(registration.bindingFingerprint(), binding.descriptorFingerprint())) {
            throw new WorldModelException(WorldModelException.Code.BINDING_DRIFT);
        }
        return new WorldSlice(registration, contract, binding, behavior, state);
    }

    public String tenantId() { return tenantId; }
    public String provider() { return provider; }
    public String apiVersion() { return apiVersion; }
    public String logicalContractId() { return logicalContractId; }
    public String contractFingerprint() { return contractFingerprint; }
    public String bindingFingerprint() { return bindingFingerprint; }
    public boolean bindingValid() { return bindingValid; }
    public LogicalResourceContract contract() { return contract; }
    public LogicalResourceBinding binding() { return binding; }
    public BlogeFragmentRef behavior() { return behavior; }
    /**
     * Source-compatible Stage 1 accessor. Stateful declarations must use
     * {@link #worldStateSpec()} and fail closed through this legacy view.
     */
    @Deprecated
    public StateSpec state() {
        if (state instanceof StateSpec legacy) return legacy;
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }
    public WorldStateSpec worldStateSpec() { return state; }
    public String fingerprint() { return fingerprint; }

    String coordinate() {
        return provider + "\u0000" + apiVersion + "\u0000" + logicalContractId;
    }

    private Map<String, Object> material() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("tenantId", tenantId);
        material.put("provider", provider);
        material.put("apiVersion", apiVersion);
        material.put("logicalContractId", logicalContractId);
        material.put("contractFingerprint", contractFingerprint);
        material.put("bindingFingerprint", bindingFingerprint);
        material.put("behaviorFingerprint", behavior.fingerprint());
        // Keep the pre-S2 empty-state material byte-for-byte stable for v1 fingerprints.
        material.put("state", state.isEmpty() ? "empty" : state.fingerprintMaterial());
        return material;
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        return value.trim();
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }
}
