package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/** Canonical producer and verifier for Package registry-ingest bundles. */
public final class PackageRegistryIngestBundleIntegrity {
    /** Maximum canonical bundle accepted for hashing. */
    public static final int MAXIMUM_BUNDLE_BYTES = 32 * 1024 * 1024;

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public PackageRegistryIngestBundleIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** @return content-addressed immutable bundle */
    public PackageRegistryIngestBundle address(PackageRegistryIngestBundle value) {
        PackageRegistryIngestBundle material = Objects.requireNonNull(value, "value")
                .withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_BUNDLE_BYTES));
    }

    /** @return whether an untrusted bundle has exact content address and constituent closure */
    public boolean canonicalVerified(PackageRegistryIngestBundle value) {
        if (value == null || value.bundleFingerprint().isBlank()) {
            return false;
        }
        try {
            value.packageSnapshot().verify(mapper);
            value.readinessReport().verify(mapper);
            value.businessAssetLinkClosure().verify(mapper);
            value.evidenceIndex().verify(mapper);
            return value.bundleFingerprint().equals(address(value).bundleFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
