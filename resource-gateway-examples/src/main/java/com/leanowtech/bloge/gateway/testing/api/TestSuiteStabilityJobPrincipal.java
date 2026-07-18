package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credential-free execution authority frozen when a stability job is submitted.
 *
 * <p>This record contains identity coordinates and governance claims, never bearer credentials or
 * authentication headers. A worker reconstructs only this already-authenticated scope; delegated
 * authorization remains visible through {@code delegatedBy} and {@code delegationGrantId} for
 * later re-authorization adapters.</p>
 *
 * @param tenantId verified tenant
 * @param organizationId verified organization
 * @param projectId verified project, possibly blank
 * @param environmentId verified non-production environment
 * @param region verified region, possibly blank
 * @param actorType authenticated actor type
 * @param actorId authenticated actor identity
 * @param delegatedBy delegating actor, possibly blank
 * @param purpose normalized test-execution purpose
 * @param correlationId submission correlation identity
 * @param groups bounded governance groups
 * @param clearance maximum data classification clearance
 * @param delegationGrantId delegated grant identity, possibly blank
 */
public record TestSuiteStabilityJobPrincipal(
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String actorType,
        String actorId,
        String delegatedBy,
        String purpose,
        String correlationId,
        Set<String> groups,
        String clearance,
        String delegationGrantId) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Normalizes and bounds the durable principal snapshot. */
    public TestSuiteStabilityJobPrincipal {
        tenantId = normalized(tenantId);
        organizationId = normalized(organizationId);
        projectId = normalized(projectId);
        environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
        region = normalized(region);
        actorType = normalized(actorType).toUpperCase(Locale.ROOT);
        actorId = normalized(actorId);
        delegatedBy = normalized(delegatedBy);
        purpose = normalized(purpose).toUpperCase(Locale.ROOT);
        correlationId = normalized(correlationId);
        clearance = normalized(clearance).toUpperCase(Locale.ROOT);
        delegationGrantId = normalized(delegationGrantId);
        LinkedHashSet<String> normalizedGroups = new LinkedHashSet<>();
        if (groups != null) {
            groups.stream().map(TestSuiteStabilityJobPrincipal::normalized)
                    .filter(value -> !value.isBlank()).forEach(normalizedGroups::add);
        }
        groups = Set.copyOf(normalizedGroups);
        if (!validRequired(tenantId) || !validRequired(organizationId)
                || (!projectId.isBlank() && !validRequired(projectId))
                || !Set.of("test", "staging").contains(environmentId)
                || (!region.isBlank() && !validRequired(region))
                || !validRequired(actorType) || !validRequired(actorId)
                || (!delegatedBy.isBlank() && !validRequired(delegatedBy))
                || !Set.of("TEST_EXECUTION", "TEST_REPLAY").contains(purpose)
                || !validRequired(correlationId)
                || groups.size() > 64 || groups.stream().anyMatch(value -> !validRequired(value))
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(clearance)
                || (!delegationGrantId.isBlank() && !validRequired(delegationGrantId))) {
            throw new IllegalArgumentException("Invalid suite-stability job principal");
        }
    }

    /**
     * Freezes a verified request identity without any transport credential.
     *
     * @param identity already authenticated integration identity
     * @return durable principal snapshot
     */
    public static TestSuiteStabilityJobPrincipal from(IntegrationRequestContext identity) {
        IntegrationRequestContext source = java.util.Objects.requireNonNull(identity, "identity");
        source.requireComplete();
        return new TestSuiteStabilityJobPrincipal(
                source.tenantId(), source.organizationId(), source.projectId(),
                source.environmentId(), source.region(), source.actorType(), source.actorId(),
                source.delegatedBy(), source.purpose(), source.correlationId(), source.groups(),
                source.clearance(), source.delegationGrantId());
    }

    /**
     * Reconstructs the credential-free worker identity carried by this immutable snapshot.
     *
     * @return scoped integration identity
     */
    public IntegrationRequestContext toContext() {
        return new IntegrationRequestContext(
                tenantId, organizationId, projectId, environmentId, region, actorType, actorId,
                delegatedBy, purpose, correlationId, groups, clearance, delegationGrantId);
    }

    private static boolean validRequired(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
