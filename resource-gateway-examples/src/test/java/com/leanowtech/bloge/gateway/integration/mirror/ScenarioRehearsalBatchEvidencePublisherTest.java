package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                material.request(),
                material.manifest(),
                material.job(),
                material.items()))
                .thenReturn(sealed);
        when(sealed.verified()).thenReturn(true);
        when(sealed.bundle()).thenReturn(bundle);
        when(batches.create(bundle)).thenReturn(bundle);
        ScenarioRehearsalBatchEvidencePublisher publisher =
                new ScenarioRehearsalBatchEvidencePublisher(
                        children, integrity, batches);

        assertThat(publisher.publish(
                material.request(),
                material.manifest(),
                material.job(),
                material.items())).isSameAs(bundle);
        verify(batches).create(bundle);
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
                                        .class));

        assertThatThrownBy(() -> publisher.publish(
                material.request(),
                material.manifest(),
                material.job(),
                material.items()))
                .isInstanceOf(IllegalStateException.class);
        verify(integrity, never())
                .seal(any(), any(), any(), any());
    }
}
