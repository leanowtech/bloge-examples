package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchProtocolTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void manifestSealBindsScopeOrderTimeoutAndStableRunIdentity() {
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        "tenant-a",
                        "org-a",
                        "project-a",
                        "test",
                        "sg");
        String requestId = "batch-a";
        String childRequestId = "batch-a:plan:000";
        String batchId =
                ScenarioRehearsalBatchIdentity.derive(
                        mapper, scope, requestId);
        MirrorArtifactRef plan = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "plan-a",
                1,
                "sha256:" + "a".repeat(64));
        ScenarioRehearsalBatchManifest sealed =
                ScenarioRehearsalBatchManifestIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchManifest(
                                "",
                                batchId,
                                "",
                                scope,
                                requestId,
                                List.of(
                                        new ScenarioRehearsalBatchManifest
                                                .Entry(
                                                0,
                                                "entry-a",
                                                plan,
                                                childRequestId,
                                                ScenarioRehearsalRunIdentity
                                                        .derive(
                                                                mapper,
                                                                scope,
                                                                childRequestId),
                                                3,
                                                Duration.ofMinutes(2))),
                                3));

        ScenarioRehearsalBatchManifestIntegrity.verify(
                mapper, sealed);
        assertThat(sealed.reference().fingerprint())
                .isEqualTo(sealed.manifestFingerprint());
        assertThat(sealed.entries().getFirst().executionTimeout())
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void requestRejectsDuplicatePlanOrEntryIdentity() {
        MirrorArtifactRef plan = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "plan-a",
                1,
                "sha256:" + "a".repeat(64));

        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchRequest(
                        "",
                        "batch-a",
                        List.of(
                                new ScenarioRehearsalBatchRequest.Entry(
                                        "entry-a", plan),
                                new ScenarioRehearsalBatchRequest.Entry(
                                        "entry-b", plan))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void principalReconstructsOnlyTheFixedExecutionPurpose() {
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        "tenant-a",
                        "org-a",
                        "project-a",
                        "test",
                        "sg");
        ScenarioRehearsalBatchPrincipal principal =
                new ScenarioRehearsalBatchPrincipal(
                        scope,
                        "SERVICE",
                        "client-a",
                        "",
                        java.util.Set.of("qa"),
                        "CONFIDENTIAL",
                        "");

        assertThat(principal.toExecutionContext("batch-correlation"))
                .satisfies(context -> {
                    assertThat(context.purpose())
                            .isEqualTo("MIRROR_REHEARSAL");
                    assertThat(context.tenantId())
                            .isEqualTo(scope.tenantId());
                    assertThat(context.correlationId())
                            .isEqualTo("batch-correlation");
                });
    }
}
