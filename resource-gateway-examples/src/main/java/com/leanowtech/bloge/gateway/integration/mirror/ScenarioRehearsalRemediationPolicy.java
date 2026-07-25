package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Server-owned policy for reviewed Scenario batch remediation.
 *
 * <p>Role membership comes only from authenticated identity groups. Request payloads cannot name
 * an owner, reviewer, lifetime, or clock tolerance. The first generation deliberately requires
 * human actors and two distinct identities; service accounts cannot approve their own generated
 * successor.</p>
 *
 * @param generation deployment-wide policy generation
 * @param planLifetime lifetime of one frozen remediation preview
 * @param maximumClockSkew tolerated application/database clock difference at persistence
 * @param ownerGroups groups allowed to preview, provide OWNER approval, and submit
 * @param independentReviewerGroups groups allowed to provide independent review
 */
public record ScenarioRehearsalRemediationPolicy(
        long generation,
        Duration planLifetime,
        Duration maximumClockSkew,
        Set<String> ownerGroups,
        Set<String> independentReviewerGroups
) {
    /** Purpose required by every reviewed business-remediation operation. */
    public static final String PURPOSE =
            "MIRROR_REHEARSAL_REMEDIATION";
    /** Default group authorizing the business owner role. */
    public static final String DEFAULT_OWNER_GROUP =
            "RESOURCE_GATEWAY_SCENARIO_OWNER";
    /** Default group authorizing independent review. */
    public static final String DEFAULT_REVIEWER_GROUP =
            "RESOURCE_GATEWAY_SCENARIO_INDEPENDENT_REVIEWER";
    private static final Pattern GROUP =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}");

    /** Conservative isolated-runtime policy. */
    public static ScenarioRehearsalRemediationPolicy defaults() {
        return new ScenarioRehearsalRemediationPolicy(
                1,
                Duration.ofDays(7),
                Duration.ofMinutes(2),
                Set.of(DEFAULT_OWNER_GROUP),
                Set.of(DEFAULT_REVIEWER_GROUP));
    }

    /** Validates bounded time policy and non-empty trusted group mappings. */
    public ScenarioRehearsalRemediationPolicy {
        if (generation < 1) {
            throw new IllegalArgumentException(
                    "Scenario remediation policy generation must be positive");
        }
        planLifetime = bounded(
                planLifetime,
                "planLifetime",
                Duration.ofMinutes(5),
                Duration.ofDays(30));
        maximumClockSkew = bounded(
                maximumClockSkew,
                "maximumClockSkew",
                Duration.ZERO,
                Duration.ofMinutes(10));
        ownerGroups = groups(ownerGroups, "ownerGroups");
        independentReviewerGroups = groups(
                independentReviewerGroups,
                "independentReviewerGroups");
    }

    /** @return whether the authenticated human may act as business owner */
    public boolean mayOwn(IntegrationRequestContext identity) {
        return human(identity)
                && identity.groups().stream()
                .anyMatch(ownerGroups::contains);
    }

    /** @return whether the authenticated human may fill the requested approval role */
    public boolean mayApprove(
            IntegrationRequestContext identity,
            ScenarioRehearsalRemediationApprovalCommand.Role role) {
        Objects.requireNonNull(role, "role");
        Set<String> allowed =
                role == ScenarioRehearsalRemediationApprovalCommand
                        .Role.OWNER
                        ? ownerGroups
                        : independentReviewerGroups;
        return human(identity)
                && identity.groups().stream()
                .anyMatch(allowed::contains);
    }

    /**
     * Returns a deterministic address of the complete authorization and lifetime policy.
     *
     * @param mapper canonical protocol mapper
     * @return canonical policy fingerprint with group sets sorted
     */
    public String fingerprint(ObjectMapper mapper) {
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("generation", generation);
        material.put("planLifetime", planLifetime);
        material.put("maximumClockSkew", maximumClockSkew);
        material.put("ownerGroups",
                List.copyOf(new TreeSet<>(ownerGroups)));
        material.put("independentReviewerGroups",
                List.copyOf(new TreeSet<>(
                        independentReviewerGroups)));
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                material,
                64 * 1024);
    }

    private static boolean human(
            IntegrationRequestContext identity) {
        if (identity == null) {
            return false;
        }
        return "USER".equals(identity.actorType())
                || "HUMAN".equals(identity.actorType());
    }

    private static Set<String> groups(
            Set<String> values,
            String field) {
        if (values == null
                || values.isEmpty()
                || values.size() > 64
                || values.stream().anyMatch(value ->
                value == null
                        || !GROUP.matcher(value.trim()).matches())) {
            throw new IllegalArgumentException(
                    field + " must contain bounded trusted group names");
        }
        return Set.copyOf(values.stream()
                .map(String::trim)
                .toList());
    }

    private static Duration bounded(
            Duration value,
            String field,
            Duration minimum,
            Duration maximum) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.compareTo(minimum) < 0
                || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported Scenario remediation bound");
        }
        return exact;
    }
}
