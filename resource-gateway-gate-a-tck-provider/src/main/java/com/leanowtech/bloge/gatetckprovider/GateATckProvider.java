package com.leanowtech.bloge.gatetckprovider;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier;

/**
 * Thin TCK Provider for role TCK_PROVIDER (Gate A A1.2 slice).
 *
 * <p>This class is a ServiceLoader-discoverable probe: it implements
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider} with no-arg constructor
 * and no runtime authority of its own. It does not mint, forge, or simulate
 * deployment-owned evidence.
 *
 * <p>The three abstract methods from the SPI return fail-closed stable implementations
 * or throw {@link UnsupportedOperationException} with stable reason codes.
 * Implementations must never fabricate evidence or mutable state.
 *
 * <p>Packaging contract (role TCK_PROVIDER, authority gate-a-protocol-authority-v1):
 * <ul>
 *   <li>META-INF/MANIFEST.MF</li>
 *   <li>META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider</li>
 *   <li>com/leanowtech/bloge/gatetckprovider/GateATckProvider.class</li>
 *   <li>META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties</li>
 *   <li>META-INF/gate-a/manifests/dependencies.json</li>
 * </ul>
 *
 * <p>No embedded SPI jars, schemas, or dependency jars are permitted in the JAR.
 */
public final class GateATckProvider
        implements CapabilityStudioStageAcceptanceAuthorityProvider {

    /** Stable reason code: evidence resolution authority is not owned by TCK Provider. */
    private static final String REASON_EVIDENCE_RESOLVER =
            "EVIDENCE_RESOLVER_NOT_OWNED";

    /** Stable reason code: evidence issuer policy is not owned by TCK Provider. */
    private static final String REASON_EVIDENCE_ISSUER_POLICY =
            "EVIDENCE_ISSUER_POLICY_NOT_OWNED";

    /** Stable reason code: owner signature authority is not owned by TCK Provider. */
    private static final String REASON_OWNER_AUTHORITY =
            "OWNER_AUTHORITY_NOT_OWNED";

    /**
     * Constructs the TCK Provider probe.
     *
     * <p>No-arg constructor required for ServiceLoader discovery.
     */
    public GateATckProvider() {}

    /**
     * Returns the deployment-owned exact-coordinate evidence and signature resolver.
     *
     * <p>TCK Provider does not own evidence resolution authority.
     * This implementation is fail-closed: it throws {@link UnsupportedOperationException}.
     *
     * @return never returns; always throws
     * @throws UnsupportedOperationException always, with stable reason code
     *         {@value #REASON_EVIDENCE_RESOLVER}
     */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
        throw new UnsupportedOperationException(REASON_EVIDENCE_RESOLVER);
    }

    /**
     * Returns the pinned evidence issuer policy.
     *
     * <p>TCK Provider does not own evidence issuer authority.
     * This implementation is fail-closed: it throws {@link UnsupportedOperationException}.
     *
     * @return never returns; always throws
     * @throws UnsupportedOperationException always, with stable reason code
     *         {@value #REASON_EVIDENCE_ISSUER_POLICY}
     */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy evidenceIssuerPolicy() {
        throw new UnsupportedOperationException(REASON_EVIDENCE_ISSUER_POLICY);
    }

    /**
     * Returns the organizational owner signature authority.
     *
     * <p>TCK Provider does not own organizational signature authority.
     * This implementation is fail-closed: it throws {@link UnsupportedOperationException}.
     *
     * @return never returns; always throws
     * @throws UnsupportedOperationException always, with stable reason code
     *         {@value #REASON_OWNER_AUTHORITY}
     */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
        throw new UnsupportedOperationException(REASON_OWNER_AUTHORITY);
    }
}
