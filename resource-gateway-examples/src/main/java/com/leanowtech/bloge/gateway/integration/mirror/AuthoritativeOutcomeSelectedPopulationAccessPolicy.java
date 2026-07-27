package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-owned separation-of-duties policy for selected-population completeness operations.
 *
 * <p>Selection, deletion, and assessment use different purposes and workload groups. Possessing
 * one authority role never grants another, preventing the outcome producer from choosing its own
 * denominator or manufacturing legal exclusions.</p>
 *
 * @param selectionAuthorityGroups groups allowed to register selected populations
 * @param deletionAuthorityGroups groups allowed to append legal dispositions
 * @param assessmentProjectorGroups groups allowed to request completeness projections
 */
public record AuthoritativeOutcomeSelectedPopulationAccessPolicy(
        Set<String> selectionAuthorityGroups,
        Set<String> deletionAuthorityGroups,
        Set<String> assessmentProjectorGroups
) {
    /** Dedicated pre-treatment selection-authority purpose. */
    public static final String SELECTION_PURPOSE =
            "MIRROR_OUTCOME_SELECTION";
    /** Dedicated retention/deletion-authority purpose. */
    public static final String DISPOSITION_PURPOSE =
            "MIRROR_OUTCOME_DISPOSITION";
    /** Governance purpose allowed to project completeness. */
    public static final String ASSESSMENT_PURPOSE =
            "MIRROR_FIDELITY_GOVERNANCE";
    /** Dedicated operator purpose for reviewed continuous-assessment quarantine recovery. */
    public static final String REMEDIATION_PURPOSE =
            "MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ADMIN";
    /** Default customer selection-authority workload group. */
    public static final String DEFAULT_SELECTION_GROUP =
            "RESOURCE_GATEWAY_OUTCOME_SELECTION_AUTHORITY";
    /** Default customer legal-deletion-authority workload group. */
    public static final String DEFAULT_DELETION_GROUP =
            "RESOURCE_GATEWAY_OUTCOME_DELETION_AUTHORITY";
    /** Default governed completeness projector workload group. */
    public static final String DEFAULT_ASSESSMENT_GROUP =
            "RESOURCE_GATEWAY_FIDELITY_PROJECTOR";
    private static final Pattern GROUP =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}");

    /** Creates a conservative three-role default policy. */
    public static AuthoritativeOutcomeSelectedPopulationAccessPolicy
    defaults() {
        return new
                AuthoritativeOutcomeSelectedPopulationAccessPolicy(
                Set.of(DEFAULT_SELECTION_GROUP),
                Set.of(DEFAULT_DELETION_GROUP),
                Set.of(DEFAULT_ASSESSMENT_GROUP));
    }

    /** Validates all finite non-empty authority group sets. */
    public AuthoritativeOutcomeSelectedPopulationAccessPolicy {
        selectionAuthorityGroups = groups(
                selectionAuthorityGroups);
        deletionAuthorityGroups = groups(
                deletionAuthorityGroups);
        assessmentProjectorGroups = groups(
                assessmentProjectorGroups);
    }

    /** @return whether a workload may register a selected denominator */
    public boolean mayRegister(
            IntegrationRequestContext identity) {
        return permits(
                identity,
                SELECTION_PURPOSE,
                selectionAuthorityGroups);
    }

    /** @return whether a workload may issue one legal member disposition */
    public boolean mayDispose(
            IntegrationRequestContext identity) {
        return permits(
                identity,
                DISPOSITION_PURPOSE,
                deletionAuthorityGroups);
    }

    /** @return whether a workload may request a completeness projection */
    public boolean mayAssess(
            IntegrationRequestContext identity) {
        return permits(
                identity,
                ASSESSMENT_PURPOSE,
                assessmentProjectorGroups);
    }

    /** @return whether a workload may requeue one exact reviewed quarantine */
    public boolean mayRemediate(
            IntegrationRequestContext identity) {
        return permits(
                identity,
                REMEDIATION_PURPOSE,
                assessmentProjectorGroups);
    }

    private static boolean permits(
            IntegrationRequestContext identity,
            String purpose,
            Set<String> groups) {
        return identity != null
                && purpose.equals(identity.purpose())
                && ("SERVICE".equals(identity.actorType())
                || "WORKLOAD".equals(
                identity.actorType()))
                && identity.groups().stream()
                .anyMatch(groups::contains);
    }

    private static Set<String> groups(
            Set<String> values) {
        if (values == null
                || values.isEmpty()
                || values.size() > 64
                || values.stream().anyMatch(value ->
                value == null
                        || !GROUP.matcher(
                        value.trim()).matches())) {
            throw new IllegalArgumentException(
                    "selected-population authority groups are invalid");
        }
        return Set.copyOf(
                values.stream()
                        .map(String::trim)
                        .toList());
    }
}
