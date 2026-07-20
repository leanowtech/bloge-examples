package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorReceiptTrustStore;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityExternalSequenceAnchor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExternalSequenceAnchor;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolStudioExternalAnchorTrustCapabilityTest {

    @Test
    void projectsDomainIsolatedManagedTrustAndReevaluatesCurrentReadiness() {
        ToolStudioIntegrationService service =
                new ToolStudioIntegrationService(null, null, null, null);
        service.configureTestability(new TestabilityAvailability(
                true, WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE));
        AtomicBoolean ready = new AtomicBoolean(true);
        TestSuiteStabilityExternalSequenceAnchor suiteAnchor = anchor(ready);
        TestSecretAuthorityExternalSequenceAnchor secretAnchor =
                TestSecretAuthorityExternalSequenceAnchor.adapt(anchor(ready));
        service.configureSuiteStabilityExternalSequenceAnchors(provider(suiteAnchor));
        service.configureTestSecretAuthorityExternalSequenceAnchors(provider(secretAnchor));

        assertThat(service.capabilities().payload().features())
                .containsEntry("managedSuiteStabilityExternalNotaryTrust", true)
                .containsEntry("restartFreeSuiteStabilityExternalNotaryKeyRotation", true)
                .containsEntry("durableSuiteStabilityExternalNotaryTrustFloor", true)
                .containsEntry("suiteStabilityExternalNotaryTrustReady", true)
                .containsEntry("managedTestSecretExternalNotaryTrust", true)
                .containsEntry("restartFreeTestSecretExternalNotaryKeyRotation", true)
                .containsEntry("durableTestSecretExternalNotaryTrustFloor", true)
                .containsEntry("testSecretExternalNotaryTrustReady", true);

        ready.set(false);

        assertThat(service.capabilities().payload().features())
                .containsEntry("managedSuiteStabilityExternalNotaryTrust", true)
                .containsEntry("suiteStabilityExternalNotaryTrustReady", false)
                .containsEntry("managedTestSecretExternalNotaryTrust", true)
                .containsEntry("testSecretExternalNotaryTrustReady", false);
    }

    private static TestSuiteStabilityExternalSequenceAnchor anchor(AtomicBoolean ready) {
        return new TestSuiteStabilityExternalSequenceAnchor() {
            @Override
            public void accept(Head head) {
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, true, true,
                        4, 3, 1, 4, Map.of(
                        "managedTrustPublication", true,
                        "restartFreeNotaryKeyRotation", true,
                        "durableTrustPublicationFloor", true));
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(Snapshot.SCHEMA_VERSION, true, "AVAILABLE",
                        Instant.parse("2026-07-20T00:00:00Z"), 1, 0, 0, 4, 3, 1, 4);
            }

            @Override
            public ExternalSequenceAnchorReceiptTrustStore.Snapshot trustSnapshot() {
                return new ExternalSequenceAnchorReceiptTrustStore.Snapshot(
                        ExternalSequenceAnchorReceiptTrustStore.Snapshot.SCHEMA_VERSION,
                        ready.get(), ready.get() ? "AVAILABLE" : "UNAVAILABLE",
                        7, 4, ready.get() ? 4 : 0,
                        Instant.parse("2026-07-20T00:00:00Z"), 2, ready.get() ? 0 : 1);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(value));
        return provider;
    }
}
