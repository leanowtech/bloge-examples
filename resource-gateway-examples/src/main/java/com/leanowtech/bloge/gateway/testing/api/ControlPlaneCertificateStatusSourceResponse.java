package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * Strict transport envelope binding one optional successor to an exact signed source head.
 *
 * <p>A successful source response always carries a source-head attestation. A missing publication
 * therefore means "the supplied cursor is the exact head", not merely "the source chose not to
 * return data". The HTTP adapter validates that cursor-dependent claim before exposing the
 * response. Keeping this envelope separate from the signed objects preserves their immutable
 * fingerprints while preventing a source from hiding backlog behind an empty response.</p>
 *
 * @param schemaVersion response protocol version
 * @param sourceHead exact signed external publication-log head
 * @param publication optional immediate successor to the request cursor
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ControlPlaneCertificateStatusSourceResponse(
        String schemaVersion,
        ControlPlaneCertificateStatusSourceHead sourceHead,
        ControlPlaneCertificateStatusPublication publication) {

    /** Current source response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateStatusSourceResponse.v2";

    /** Validates cursor-independent scope and head consistency. */
    public ControlPlaneCertificateStatusSourceResponse {
        schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
        sourceHead = Objects.requireNonNull(sourceHead, "sourceHead");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid();
        }
        if (publication != null) {
            var head = sourceHead.material();
            var candidate = publication.material();
            if (!head.deploymentScopeId().equals(candidate.deploymentScopeId())
                    || !head.policyFingerprint().equals(candidate.policyFingerprint())
                    || candidate.sequence() > head.headSequence()
                    || candidate.sequence() == head.headSequence()
                    && !publication.materialFingerprint().equals(
                    head.headPublicationFingerprint())) {
                throw invalid();
            }
        }
    }

    /** @return whether this response carries one successor candidate */
    public boolean hasPublication() {
        return publication != null;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate status source response is invalid");
    }
}
