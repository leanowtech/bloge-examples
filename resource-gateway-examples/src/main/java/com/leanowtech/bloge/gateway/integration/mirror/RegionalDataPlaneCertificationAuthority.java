package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;

/** Deployment-owned authority that binds an exact regional certification to Mirror run trust. */
public interface RegionalDataPlaneCertificationAuthority {
    /**
     * Requires a currently active certification covering one complete execution window.
     *
     * @param scope authenticated enterprise scope
     * @param isolationDecisionRef exact v2 isolation decision used by the run
     * @param isolationAttestationRef exact isolation attestation used by the run
     * @param executionStartedAt execution-window start
     * @param executionCompletedAt execution-window end
     * @throws TrustException when any contract, authority, freshness, rotation, or write guard fails
     */
    void require(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef isolationDecisionRef,
            MirrorArtifactRef isolationAttestationRef,
            Instant executionStartedAt,
            Instant executionCompletedAt);

    /** @return whether this deployment can currently attempt regional certification */
    boolean available();

    /** @return payload-free capability descriptor */
    Descriptor descriptor();

    /** @return fail-closed authority for deployments without regional certification */
    static RegionalDataPlaneCertificationAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Payload-free capability projection. */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String status,
            String contractSchemaVersion,
            String certificationSchemaVersion
    ) {
        /** Current descriptor protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.regionalDataPlaneCertificationAuthorityDescriptor.v1";

        /** Validates the bounded descriptor. */
        public Descriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            status = status == null ? "" : status.trim();
            contractSchemaVersion = contractSchemaVersion == null
                    ? "" : contractSchemaVersion.trim();
            certificationSchemaVersion = certificationSchemaVersion == null
                    ? "" : certificationSchemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !status.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException(
                        "regional certification authority descriptor is invalid");
            }
        }

        /** @return descriptor for a missing deployment adapter */
        public static Descriptor unavailable() {
            return new Descriptor("", false, "UNAVAILABLE",
                    RegionalDataPlaneDeploymentContract.SCHEMA_VERSION,
                    RegionalDataPlaneCertification.SCHEMA_VERSION);
        }
    }

    /** Stable payload-free trust denial. */
    final class TrustException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;

        /** @param reasonCode stable machine-readable reason */
        public TrustException(String reasonCode) {
            super("regional data-plane certification rejected: " + normalized(reasonCode));
            this.reasonCode = normalized(reasonCode);
            if (!this.reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException(
                        "regional certification reasonCode is invalid");
            }
        }

        /** @return stable machine-readable reason */
        public String reasonCode() {
            return reasonCode;
        }
    }

    /** Missing-adapter singleton. */
    final class Unavailable implements RegionalDataPlaneCertificationAuthority {
        private static final Unavailable INSTANCE = new Unavailable();

        private Unavailable() {
        }

        @Override
        public void require(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef isolationDecisionRef,
                MirrorArtifactRef isolationAttestationRef,
                Instant executionStartedAt,
                Instant executionCompletedAt) {
            throw new TrustException("REGIONAL_CERTIFICATION_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Descriptor descriptor() {
            return Descriptor.unavailable();
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
