package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityImplementationBehaviorFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void normalizesExecutionMechanismButRetainsObservableBusinessValues() {
        Object input = Map.of("refundId", "r-1");
        Object output = Map.of("approved", true);
        MirrorRunEvidence baseline = mock(MirrorRunEvidence.class);
        when(baseline.nodeTraces()).thenReturn(List.of(new MirrorRunEvidence.NodeTrace(
                "lookup", "refundLookup", "MOCKED", "OUTPUT_LEVEL",
                ProtocolFingerprint.of(mapper, input), ProtocolFingerprint.of(mapper, output),
                "", 1, "/root/lookup#PRIMARY", "/root", "", 1, 1,
                List.of(new MirrorRunEvidence.AttemptTrace(1, "MOCKED", "OUTPUT_LEVEL",
                        ProtocolFingerprint.of(mapper, input),
                        ProtocolFingerprint.of(mapper, output), "", 1)))));
        when(baseline.edgeTraces()).thenReturn(List.of());
        TestRunEvidence implementation = mock(TestRunEvidence.class);
        when(implementation.nodeTrace()).thenReturn(List.of(new TestRunEvidence.NodeTrace(
                "lookup", "refundLookup", "SUCCESS", "REAL", input, output, "", 7,
                "/root/lookup#PRIMARY", "/root", "", 1, 1,
                List.of(new TestRunEvidence.AttemptTrace(
                        1, "SUCCESS", "REAL", input, output, "", 7)))));
        when(implementation.edgeTrace()).thenReturn(List.of());

        String accepted = CapabilityImplementationBehaviorFingerprint.baseline(mapper, baseline);
        String conformant = CapabilityImplementationBehaviorFingerprint.implementation(
                mapper, implementation);
        when(implementation.nodeTrace()).thenReturn(List.of(new TestRunEvidence.NodeTrace(
                "lookup", "refundLookup", "SUCCESS", "REAL", input,
                Map.of("approved", false), "", 7, "/root/lookup#PRIMARY", "/root", "",
                1, 1, List.of(new TestRunEvidence.AttemptTrace(1, "SUCCESS", "REAL", input,
                Map.of("approved", false), "", 7)))));

        assertThat(conformant).isEqualTo(accepted);
        assertThat(CapabilityImplementationBehaviorFingerprint.implementation(
                mapper, implementation)).isNotEqualTo(accepted);
    }
}
