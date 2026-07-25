package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-owned authorization, lifetime, and projection policy for Domain Fidelity.
 *
 * <p>Inventory authorship and source projection are independent duties. Human owners can change
 * the stable denominator; service principals can project only independently verified source
 * facts. Neither role can choose the statistical algorithm, sample floor, or freshness horizon
 * from an HTTP body.</p>
 *
 * @param generation deployment policy generation
 * @param ownerGroups human groups allowed to register inventory revisions
 * @param projectorGroups service groups allowed to publish verified profiles
 * @param minimumInventoryLifetime shortest accepted owner review horizon
 * @param maximumInventoryLifetime longest accepted owner review horizon
 * @param maximumPastActivationSkew tolerated client/server activation clock skew
 * @param maximumFutureActivation furthest accepted future activation
 * @param projectionPolicy fixed fail-closed profile projection policy
 */
public record DomainFidelityPolicy(
        long generation,
        Set<String> ownerGroups,
        Set<String> projectorGroups,
        Duration minimumInventoryLifetime,
        Duration maximumInventoryLifetime,
        Duration maximumPastActivationSkew,
        Duration maximumFutureActivation,
        DomainFidelityProfile.ProjectionPolicy projectionPolicy
) {
    /** Purpose required to alter the owner-approved denominator. */
    public static final String GOVERNANCE_PURPOSE =
            "MIRROR_FIDELITY_GOVERNANCE";
    /** Purpose required by independently verified source adapters. */
    public static final String PROJECTION_PURPOSE =
            "MIRROR_FIDELITY_PROJECTION";
    /** Default human owner group. */
    public static final String DEFAULT_OWNER_GROUP =
            "RESOURCE_GATEWAY_FIDELITY_OWNER";
    /** Default trusted source-adapter group. */
    public static final String DEFAULT_PROJECTOR_GROUP =
            "RESOURCE_GATEWAY_FIDELITY_PROJECTOR";
    private static final Pattern GROUP =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}");

    /** @return conservative first-generation policy */
    public static DomainFidelityPolicy defaults() {
        return new DomainFidelityPolicy(
                1,
                Set.of(DEFAULT_OWNER_GROUP),
                Set.of(DEFAULT_PROJECTOR_GROUP),
                Duration.ofDays(1),
                Duration.ofDays(730),
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                new DomainFidelityProfile.ProjectionPolicy(
                        30,
                        Duration.ofDays(30),
                        true,
                        DomainFidelityProfile.CONFIDENCE_METHOD));
    }

    /** Validates bounded policy values and non-empty trusted role mappings. */
    public DomainFidelityPolicy {
        if (generation < 1) {
            throw new IllegalArgumentException(
                    "Domain Fidelity policy generation must be positive");
        }
        ownerGroups = groups(ownerGroups, "ownerGroups");
        projectorGroups = groups(projectorGroups, "projectorGroups");
        minimumInventoryLifetime = duration(
                minimumInventoryLifetime,
                Duration.ofHours(1),
                Duration.ofDays(30),
                "minimumInventoryLifetime");
        maximumInventoryLifetime = duration(
                maximumInventoryLifetime,
                minimumInventoryLifetime,
                Duration.ofDays(3650),
                "maximumInventoryLifetime");
        maximumPastActivationSkew = duration(
                maximumPastActivationSkew,
                Duration.ZERO,
                Duration.ofMinutes(30),
                "maximumPastActivationSkew");
        maximumFutureActivation = duration(
                maximumFutureActivation,
                Duration.ZERO,
                Duration.ofDays(365),
                "maximumFutureActivation");
        projectionPolicy = Objects.requireNonNull(
                projectionPolicy, "projectionPolicy");
    }

    /** @return whether the authenticated human may alter a Fidelity denominator */
    public boolean mayOwn(IntegrationRequestContext identity) {
        return human(identity) && authorized(identity, ownerGroups);
    }

    /** @return whether the authenticated service may publish independently verified sources */
    public boolean mayProject(IntegrationRequestContext identity) {
        return identity != null
                && ("SERVICE".equals(identity.actorType())
                || "WORKLOAD".equals(identity.actorType()))
                && authorized(identity, projectorGroups);
    }

    /**
     * Validates a requested owner review window against server policy.
     *
     * @param now trusted application time
     * @param effectiveAt requested inclusive activation
     * @param expiresAt requested exclusive review horizon
     */
    public void requireInventoryWindow(
            Instant now, Instant effectiveAt, Instant expiresAt) {
        Instant current = Objects.requireNonNull(now, "now");
        Instant effective = Objects.requireNonNull(
                effectiveAt, "effectiveAt");
        Instant expiry = Objects.requireNonNull(
                expiresAt, "expiresAt");
        Duration lifetime = Duration.between(effective, expiry);
        if (effective.isBefore(
                current.minus(maximumPastActivationSkew))
                || effective.isAfter(
                current.plus(maximumFutureActivation))
                || lifetime.compareTo(
                minimumInventoryLifetime) < 0
                || lifetime.compareTo(
                maximumInventoryLifetime) > 0) {
            throw new IllegalArgumentException(
                    "inventory activation or review horizon violates server policy");
        }
    }

    private static boolean authorized(
            IntegrationRequestContext identity, Set<String> allowed) {
        return identity != null
                && identity.groups().stream().anyMatch(allowed::contains);
    }

    private static boolean human(
            IntegrationRequestContext identity) {
        return identity != null
                && ("USER".equals(identity.actorType())
                || "HUMAN".equals(identity.actorType()));
    }

    private static Set<String> groups(
            Set<String> values, String field) {
        if (values == null || values.isEmpty()
                || values.size() > 64
                || values.stream().anyMatch(value ->
                value == null
                        || !GROUP.matcher(value.trim()).matches())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Set.copyOf(values.stream().map(String::trim).toList());
    }

    private static Duration duration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.compareTo(minimum) < 0
                || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported bound");
        }
        return exact;
    }
}
