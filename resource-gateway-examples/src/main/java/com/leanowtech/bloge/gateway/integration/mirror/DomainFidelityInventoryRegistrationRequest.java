package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strict owner command for registering one immutable Domain Fidelity denominator revision.
 *
 * <p>The command deliberately cannot provide enterprise scope, approval identity, approval time,
 * lifecycle, provenance, or the resulting content address. Those trust facts are minted by the
 * authenticated application service. Revision one uses a blank predecessor fingerprint; every
 * later revision must compare-and-set against the current inventory fingerprint.</p>
 *
 * @param schemaVersion exact command protocol version
 * @param inventoryId stable inventory lineage identity
 * @param revision positive immutable revision
 * @param expectedPredecessorFingerprint blank for revision one, exact current head otherwise
 * @param domainId stable customer-business domain identity
 * @param taxonomyRef exact owner-governed Fidelity taxonomy
 * @param units complete ordered business coverage denominator
 * @param effectiveAt requested inclusive activation time
 * @param expiresAt requested exclusive owner-review horizon
 */
public record DomainFidelityInventoryRegistrationRequest(
        String schemaVersion,
        String inventoryId,
        long revision,
        String expectedPredecessorFingerprint,
        String domainId,
        MirrorArtifactRef taxonomyRef,
        List<DomainFidelityInventory.CoverageUnit> units,
        Instant effectiveAt,
        Instant expiresAt
) {
    /** Current strict registration-command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.domainFidelityInventoryRegistrationRequest.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates bounded public command fields without creating trusted approval facts. */
    public DomainFidelityInventoryRegistrationRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Domain Fidelity inventory registration schemaVersion");
        }
        inventoryId = identifier(inventoryId, "inventoryId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        expectedPredecessorFingerprint =
                expectedPredecessorFingerprint == null
                        ? "" : expectedPredecessorFingerprint.trim();
        if (!expectedPredecessorFingerprint.isBlank()
                && !FINGERPRINT.matcher(
                expectedPredecessorFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "expectedPredecessorFingerprint is invalid");
        }
        if ((revision == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "only revision one may omit the predecessor fingerprint");
        }
        domainId = identifier(domainId, "domainId");
        if (taxonomyRef == null
                || !"DOMAIN_FIDELITY_TAXONOMY".equals(
                taxonomyRef.kind())) {
            throw new IllegalArgumentException(
                    "taxonomyRef must reference DOMAIN_FIDELITY_TAXONOMY");
        }
        units = units == null ? List.of() : List.copyOf(units);
        if (units.isEmpty()
                || units.size()
                > DomainFidelityInventory.MAXIMUM_UNITS) {
            throw new IllegalArgumentException(
                    "units must contain between 1 and 4096 coverage obligations");
        }
        String previous = "";
        for (DomainFidelityInventory.CoverageUnit unit : units) {
            if (unit == null
                    || unit.unitId().compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "units must be non-null and ordered by unitId");
            }
            previous = unit.unitId();
        }
        effectiveAt = java.util.Objects.requireNonNull(
                effectiveAt, "effectiveAt");
        expiresAt = java.util.Objects.requireNonNull(
                expiresAt, "expiresAt");
        if (!expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after effectiveAt");
        }
    }

    private static String identifier(
            String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
