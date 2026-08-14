package com.leanowtech.bloge.gateway.businessmirror.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.pilot.BusinessMirrorPilotAcceptanceManifest.ScenarioDenominator;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/** Canonical producer and verifier for pilot acceptance manifests and owner-frozen denominators. */
public final class BusinessMirrorPilotAcceptanceManifestIntegrity {
    /** Maximum canonical manifest bytes accepted for hashing. */
    public static final int MAXIMUM_MANIFEST_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public BusinessMirrorPilotAcceptanceManifestIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** @return independently content-addressed denominator */
    public ScenarioDenominator addressDenominator(ScenarioDenominator value) {
        ScenarioDenominator material = Objects.requireNonNull(value, "value").withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_MANIFEST_BYTES));
    }

    /** @return manifest whose denominator and top-level value are independently addressed */
    public BusinessMirrorPilotAcceptanceManifest address(
            BusinessMirrorPilotAcceptanceManifest value) {
        BusinessMirrorPilotAcceptanceManifest material = Objects.requireNonNull(value, "value")
                .withScenarioDenominator(addressDenominator(value.scenarioDenominator()))
                .withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_MANIFEST_BYTES));
    }

    /** @return whether both content addresses and all constructor invariants are valid */
    public boolean canonicalVerified(BusinessMirrorPilotAcceptanceManifest value) {
        if (value == null || value.manifestFingerprint().isBlank()
                || value.scenarioDenominator().denominatorFingerprint().isBlank()) {
            return false;
        }
        try {
            ScenarioDenominator denominator = addressDenominator(value.scenarioDenominator());
            return denominator.denominatorFingerprint()
                    .equals(value.scenarioDenominator().denominatorFingerprint())
                    && address(value).manifestFingerprint().equals(value.manifestFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
