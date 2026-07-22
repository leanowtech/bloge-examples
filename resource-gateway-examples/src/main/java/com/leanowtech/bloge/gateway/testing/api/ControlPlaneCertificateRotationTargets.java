package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;
import java.util.Set;

/** Stable product target identities for the fifteen authenticated control-plane transports. */
public final class ControlPlaneCertificateRotationTargets {

    /** Bootstrap-root complete-chain publisher. */
    public static final String BOOTSTRAP_ROOT_PUBLISHER =
            "gateway.testing.external-sequence-anchor.bootstrap-root-publication.transport";
    /** Recovery-fleet signed inventory source. */
    public static final String RECOVERY_FLEET_INVENTORY =
            "gateway.testing.external-sequence-anchor."
                    + "bootstrap-root-recovery-fleet-dynamic-inventory.transport";
    /** Recovery-fleet inventory managed trust-root source. */
    public static final String RECOVERY_FLEET_INVENTORY_TRUST_ROOTS =
            "gateway.testing.external-sequence-anchor."
                    + "bootstrap-root-recovery-fleet-dynamic-inventory.trust-roots.transport";

    /** Test-secret notary transport. */
    public static final String TEST_SECRET_NOTARY = testSecretPrefix() + ".transport";
    /** Test-secret managed receipt-key transport. */
    public static final String TEST_SECRET_MANAGED_TRUST =
            testSecretPrefix() + ".managed-trust.transport";
    /** Test-secret managed bootstrap-root transport. */
    public static final String TEST_SECRET_BOOTSTRAP_ROOTS =
            testSecretPrefix() + ".managed-trust.bootstrap-roots.transport";

    /** Suite-stability notary transport. */
    public static final String SUITE_STABILITY_NOTARY = suiteStabilityPrefix() + ".transport";
    /** Suite-stability managed receipt-key transport. */
    public static final String SUITE_STABILITY_MANAGED_TRUST =
            suiteStabilityPrefix() + ".managed-trust.transport";
    /** Suite-stability managed bootstrap-root transport. */
    public static final String SUITE_STABILITY_BOOTSTRAP_ROOTS =
            suiteStabilityPrefix() + ".managed-trust.bootstrap-roots.transport";

    /** Recovery-fleet notary transport. */
    public static final String RECOVERY_FLEET_NOTARY = recoveryFleetPrefix() + ".transport";
    /** Recovery-fleet managed receipt-key transport. */
    public static final String RECOVERY_FLEET_MANAGED_TRUST =
            recoveryFleetPrefix() + ".managed-trust.transport";
    /** Recovery-fleet managed bootstrap-root transport. */
    public static final String RECOVERY_FLEET_BOOTSTRAP_ROOTS =
            recoveryFleetPrefix() + ".managed-trust.bootstrap-roots.transport";

    /** Physical provider-inventory notary transport. */
    public static final String PHYSICAL_PROVIDER_INVENTORY_NOTARY =
            physicalProviderInventoryPrefix() + ".transport";
    /** Physical provider-inventory managed receipt-key transport. */
    public static final String PHYSICAL_PROVIDER_INVENTORY_MANAGED_TRUST =
            physicalProviderInventoryPrefix() + ".managed-trust.transport";
    /** Physical provider-inventory managed bootstrap-root transport. */
    public static final String PHYSICAL_PROVIDER_INVENTORY_BOOTSTRAP_ROOTS =
            physicalProviderInventoryPrefix() + ".managed-trust.bootstrap-roots.transport";

    private static final List<String> VALUES = List.of(
            BOOTSTRAP_ROOT_PUBLISHER,
            RECOVERY_FLEET_INVENTORY,
            RECOVERY_FLEET_INVENTORY_TRUST_ROOTS,
            TEST_SECRET_NOTARY,
            TEST_SECRET_MANAGED_TRUST,
            TEST_SECRET_BOOTSTRAP_ROOTS,
            SUITE_STABILITY_NOTARY,
            SUITE_STABILITY_MANAGED_TRUST,
            SUITE_STABILITY_BOOTSTRAP_ROOTS,
            RECOVERY_FLEET_NOTARY,
            RECOVERY_FLEET_MANAGED_TRUST,
            RECOVERY_FLEET_BOOTSTRAP_ROOTS,
            PHYSICAL_PROVIDER_INVENTORY_NOTARY,
            PHYSICAL_PROVIDER_INVENTORY_MANAGED_TRUST,
            PHYSICAL_PROVIDER_INVENTORY_BOOTSTRAP_ROOTS);
    private static final Set<String> VALUE_SET = Set.copyOf(VALUES);

    private ControlPlaneCertificateRotationTargets() {
    }

    /** @return all fifteen stable target identities in product order */
    public static List<String> values() {
        return VALUES;
    }

    /** @return whether the supplied identity is one of the fifteen product transports */
    public static boolean contains(String targetId) {
        return targetId != null && VALUE_SET.contains(targetId.trim());
    }

    private static String testSecretPrefix() {
        return "gateway.testing.test-secrets.authority.http.jwks.cohort."
                + "signed-inventory.remote.external-anchor";
    }

    private static String suiteStabilityPrefix() {
        return "gateway.testing.stability-jobs.authority.http.jwks.cohort."
                + "signed-inventory.remote.external-anchor";
    }

    private static String recoveryFleetPrefix() {
        return "gateway.testing.external-sequence-anchor."
                + "bootstrap-root-recovery-fleet-dynamic-inventory.external-anchor";
    }

    private static String physicalProviderInventoryPrefix() {
        return TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties
                .EXTERNAL_ANCHOR_PREFIX;
    }
}
