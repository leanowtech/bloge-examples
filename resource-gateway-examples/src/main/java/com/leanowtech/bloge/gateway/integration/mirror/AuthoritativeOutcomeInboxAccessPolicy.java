package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-owned workload authorization policy for outcome connector admission.
 *
 * <p>Transport purpose and connector group are both required. Evidence readers remain governed by
 * the central integration-purpose policy and exact enterprise scope; only workload identities can
 * append business-authority closures.</p>
 *
 * @param connectorGroups workload groups permitted to append outcome revisions
 */
public record AuthoritativeOutcomeInboxAccessPolicy(
        Set<String> connectorGroups
) {
    /** Dedicated connector purpose. */
    public static final String INGESTION_PURPOSE =
            "MIRROR_OUTCOME_INGESTION";
    /** Default independently operated customer-connector group. */
    public static final String DEFAULT_CONNECTOR_GROUP =
            "RESOURCE_GATEWAY_OUTCOME_CONNECTOR";
    private static final Pattern GROUP =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}");

    /** Conservative default policy with one explicit connector group. */
    public static AuthoritativeOutcomeInboxAccessPolicy defaults() {
        return new AuthoritativeOutcomeInboxAccessPolicy(
                Set.of(DEFAULT_CONNECTOR_GROUP));
    }

    /** Validates a finite non-empty group set. */
    public AuthoritativeOutcomeInboxAccessPolicy {
        if (connectorGroups == null
                || connectorGroups.isEmpty()
                || connectorGroups.size() > 64
                || connectorGroups.stream().anyMatch(value ->
                value == null
                        || !GROUP.matcher(
                        value.trim()).matches())) {
            throw new IllegalArgumentException(
                    "outcome connector groups are invalid");
        }
        connectorGroups = Set.copyOf(
                connectorGroups.stream()
                        .map(String::trim)
                        .toList());
    }

    /** @return whether an exact service/workload connector may append revisions */
    public boolean mayIngest(
            IntegrationRequestContext identity) {
        return identity != null
                && INGESTION_PURPOSE.equals(
                identity.purpose())
                && ("SERVICE".equals(identity.actorType())
                || "WORKLOAD".equals(
                identity.actorType()))
                && identity.groups().stream()
                .anyMatch(connectorGroups::contains);
    }
}
