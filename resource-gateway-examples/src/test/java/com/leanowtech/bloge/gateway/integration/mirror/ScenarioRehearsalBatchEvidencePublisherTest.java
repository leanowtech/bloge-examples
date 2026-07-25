package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchEvidencePublisherTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesChildClosureBeforeSealingAndAppending() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalEvidenceRepository children =
                mock(ScenarioRehearsalEvidenceRepository.class);
        ScenarioRehearsalEvidenceBundle child =
                mock(ScenarioRehearsalEvidenceBundle.class);
        ScenarioRehearsalResult result =
                mock(ScenarioRehearsalResult.class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchEvidenceRepository batches =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchRetentionRepository retention =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                sealed = mock(
                ScenarioRehearsalBatchEvidenceIntegrityService
                        .SealResult.class);
        ScenarioRehearsalBatchItemPage.Item item =
                material.items().getFirst();
        when(children.find(
                material.job().scope(), item.runId()))
                .thenReturn(Optional.of(child));
        when(child.bundleFingerprint()).thenReturn(
                item.evidenceBundleFingerprint());
        when(child.result()).thenReturn(result);
        when(result.scope()).thenReturn(material.job().scope());
        when(result.requestId()).thenReturn(
                item.childRequestId());
        when(result.compiledPlanRef()).thenReturn(
                item.compiledPlanRef());
        when(result.outcome()).thenReturn(item.outcome());
        when(integrity.seal(
                eq(material.request()),
                eq(material.manifest()),
                eq(material.job()),
                eq(material.items()),
                isNull(),
                anyString()))
                .thenReturn(sealed);
        when(sealed.verified()).thenReturn(true);
        when(sealed.bundle()).thenReturn(bundle);
        ScenarioRehearsalBatchRetentionRepository.PreparedRegistration
                registration = registration(
                bundle,
                material,
                retainUntil(material),
                retention);
        when(batches.create(bundle)).thenReturn(bundle);
        ScenarioRehearsalBatchEvidencePublisher publisher =
                new ScenarioRehearsalBatchEvidencePublisher(
                        children, integrity, batches, retention);
        Instant retainUntil = retainUntil(material);

        assertThat(publisher.publish(
                material.request(),
                material.manifest(),
                material.job(),
                material.items(),
                retainUntil)).isSameAs(bundle);
        verify(batches).create(bundle);
        verify(retention).register(bundle, registration);
    }

    @Test
    void missingChildEvidenceFailsBeforeBatchSigning() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalEvidenceRepository children =
                mock(ScenarioRehearsalEvidenceRepository.class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        when(children.find(
                material.job().scope(),
                material.items().getFirst().runId()))
                .thenReturn(Optional.empty());
        ScenarioRehearsalBatchEvidencePublisher publisher =
                new ScenarioRehearsalBatchEvidencePublisher(
                        children,
                        integrity,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        mock(
                                ScenarioRehearsalBatchRetentionRepository
                                        .class));

        assertThatThrownBy(() -> publisher.publish(
                material.request(),
                material.manifest(),
                material.job(),
                material.items(),
                Instant.parse("2030-01-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
        verify(integrity, never())
                .seal(
                        any(), any(), any(), any(),
                        any(), anyString());
    }

    @Test
    void retentionRegistrationFailurePropagatesAfterEvidenceAppend() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalEvidenceRepository children =
                mock(ScenarioRehearsalEvidenceRepository.class);
        ScenarioRehearsalEvidenceBundle child =
                mock(ScenarioRehearsalEvidenceBundle.class);
        ScenarioRehearsalResult result =
                mock(ScenarioRehearsalResult.class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchEvidenceRepository batches =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchRetentionRepository retention =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                sealed = mock(
                ScenarioRehearsalBatchEvidenceIntegrityService
                        .SealResult.class);
        ScenarioRehearsalBatchItemPage.Item item =
                material.items().getFirst();
        when(children.find(
                material.job().scope(), item.runId()))
                .thenReturn(Optional.of(child));
        when(child.bundleFingerprint()).thenReturn(
                item.evidenceBundleFingerprint());
        when(child.result()).thenReturn(result);
        when(result.scope()).thenReturn(
                material.job().scope());
        when(result.requestId()).thenReturn(
                item.childRequestId());
        when(result.compiledPlanRef()).thenReturn(
                item.compiledPlanRef());
        when(result.outcome()).thenReturn(
                item.outcome());
        when(integrity.seal(
                eq(material.request()),
                eq(material.manifest()),
                eq(material.job()),
                eq(material.items()),
                isNull(),
                anyString()))
                .thenReturn(sealed);
        when(sealed.verified()).thenReturn(true);
        when(sealed.bundle()).thenReturn(bundle);
        when(batches.create(bundle)).thenReturn(bundle);
        Instant retainUntil =
                Instant.parse("2030-01-01T00:00:00Z");
        ScenarioRehearsalBatchRetentionRepository.PreparedRegistration
                registration = registration(
                bundle, material, retainUntil, retention);
        when(retention.register(
                bundle, registration))
                .thenThrow(new IllegalStateException(
                        "retention unavailable"));
        ScenarioRehearsalBatchEvidencePublisher publisher =
                new ScenarioRehearsalBatchEvidencePublisher(
                        children, integrity, batches, retention);

        assertThatThrownBy(() -> publisher.publish(
                material.request(),
                material.manifest(),
                material.job(),
                material.items(),
                retainUntil))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retention unavailable");
        verify(batches).create(bundle);
        verify(retention).register(
                bundle, registration);
    }

    private ScenarioRehearsalBatchRetentionRepository
    .PreparedRegistration registration(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchEvidenceTestFixtures.Material
                    material,
            Instant retainUntil,
            ScenarioRehearsalBatchRetentionRepository retention) {
        String fingerprint =
                "sha256:" + "a".repeat(64);
        Instant signedAt =
                material.job().completedAt().plusSeconds(1);
        ScenarioRehearsalBatchEvidenceIndex index =
                mock(ScenarioRehearsalBatchEvidenceIndex.class);
        ScenarioRehearsalBatchEvidenceAttestation attestation =
                mock(
                        ScenarioRehearsalBatchEvidenceAttestation
                                .class);
        ScenarioRehearsalBatchRetentionRepository
                .PreparedRegistration registration =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .PreparedRegistration.class);
        ScenarioRehearsalBatchRetentionEvent event =
                mock(
                        ScenarioRehearsalBatchRetentionEvent.class);
        when(bundle.bundleFingerprint())
                .thenReturn(fingerprint);
        when(bundle.index()).thenReturn(index);
        when(bundle.attestation()).thenReturn(attestation);
        when(index.job()).thenReturn(material.job());
        when(attestation.signedAt()).thenReturn(signedAt);
        when(registration.bundleFingerprint())
                .thenReturn(fingerprint);
        when(registration.event()).thenReturn(event);
        when(event.jobId()).thenReturn(
                material.job().jobId());
        when(retention.prepareRegistration(
                eq(bundle),
                eq(retainUntil),
                eq(signedAt),
                anyString()))
                .thenReturn(registration);
        return registration;
    }

    private static Instant retainUntil(
            ScenarioRehearsalBatchEvidenceTestFixtures.Material
                    material) {
        return material.job().completedAt()
                .plus(Duration.ofDays(30));
    }
}
