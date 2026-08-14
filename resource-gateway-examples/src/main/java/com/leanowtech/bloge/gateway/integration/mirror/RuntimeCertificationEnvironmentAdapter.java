package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Customer-owned boundary that maps portable runtime-certification scenarios to real
 * infrastructure actions and observations.
 *
 * <p>Resource Gateway deliberately supplies no implementation. A Kubernetes, cloud, database, or
 * on-premises Adapter remains deployment-owned and must independently revalidate the signed
 * authorization and durably suppress replay before applying a fault.</p>
 */
public interface RuntimeCertificationEnvironmentAdapter {
    /** @return current payload-free Adapter capability and safety descriptor */
    RuntimeCertificationReport.AdapterDescriptor descriptor();

    /**
     * Executes or exact-replays one authorized scenario.
     *
     * @param request complete exact execution coordinates
     * @return terminal payload-free observation for the requested scenario
     */
    RuntimeCertificationReport.ScenarioResult execute(ScenarioExecution request);

    /**
     * Exact request passed across the customer Adapter boundary.
     *
     * @param runId stable certification run id
     * @param journalEpoch fencing epoch owned by the current harness worker
     * @param manifest exact certification profile
     * @param authorization complete externally signed single-use approval
     * @param regionalDataPlaneCertificationRef exact active regional certification
     * @param isolationDecisionRef exact v2 isolation decision
     * @param isolationAttestationRef exact underlying deployment attestation
     * @param requirement exact scenario and invariant denominator
     * @param requestedAt request time
     * @param deadline hard Adapter completion deadline
     */
    record ScenarioExecution(
            String runId,
            long journalEpoch,
            RuntimeCertificationManifest manifest,
            RuntimeCertificationExecutionAuthorization authorization,
            MirrorArtifactRef regionalDataPlaneCertificationRef,
            MirrorArtifactRef isolationDecisionRef,
            MirrorArtifactRef isolationAttestationRef,
            RuntimeCertificationManifest.ScenarioRequirement requirement,
            Instant requestedAt,
            Instant deadline
    ) {
        /** Validates an exact bounded Adapter request. */
        public ScenarioExecution {
            runId = RegionalDataPlaneDeploymentContract.identifier(runId, "runId");
            if (journalEpoch < 1) {
                throw new IllegalArgumentException("journalEpoch must be positive");
            }
            manifest = Objects.requireNonNull(manifest, "manifest");
            authorization = Objects.requireNonNull(authorization, "authorization");
            regionalDataPlaneCertificationRef =
                    RuntimeCertificationExecutionAuthorization.requireKind(
                            regionalDataPlaneCertificationRef,
                            RegionalDataPlaneCertification.ARTIFACT_KIND,
                            "regionalDataPlaneCertificationRef");
            isolationDecisionRef = RuntimeCertificationExecutionAuthorization.requireKind(
                    isolationDecisionRef,
                    MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                    "isolationDecisionRef");
            isolationAttestationRef = RuntimeCertificationExecutionAuthorization.requireKind(
                    isolationAttestationRef,
                    MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                    "isolationAttestationRef");
            requirement = Objects.requireNonNull(requirement, "requirement");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
            deadline = Objects.requireNonNull(deadline, "deadline");
            if (!deadline.isAfter(requestedAt)) {
                throw new IllegalArgumentException("scenario deadline must follow request time");
            }
        }
    }
}
