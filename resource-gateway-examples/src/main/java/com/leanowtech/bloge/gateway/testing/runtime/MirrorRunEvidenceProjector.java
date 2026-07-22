package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects shared test-kernel evidence into the portable payload-free mirror evidence protocol.
 *
 * <p>The projector hashes each node input/output, attempt input/output, edge value, and detached
 * request context independently. It also proves an exact closure between external delegate
 * attempts and sealed {@link MirrorResolution} values before a signing authority may see the
 * result. Internal test metadata, diagnostics, assertions, and business payloads are never copied
 * into the portable protocol.</p>
 */
public final class MirrorRunEvidenceProjector {
    /** Maximum canonical business value admitted to one payload fingerprint. */
    public static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024 * 1024;
    /** Current honest limitation until a deployment isolation attestation is integrated. */
    public static final String DEPLOYMENT_EGRESS_NOT_ATTESTED =
            "DEPLOYMENT_EGRESS_NOT_ATTESTED";
    /** Current limitation when the shared test kernel already classified evidence as exploratory. */
    public static final String SHARED_TEST_EVIDENCE_EXPLORATORY =
            "SHARED_TEST_EVIDENCE_EXPLORATORY";

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper used for every payload fingerprint */
    public MirrorRunEvidenceProjector(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Creates one complete payload-free value from an admitted mirror execution.
     *
     * @param request exact authenticated mirror request
     * @param execution terminal shared-kernel result
     * @param resolutions sealed resolver provenance completed with the terminal run id
     * @param engineConfiguration structural independent-engine facts
     * @return unsigned portable evidence ready for the integrity boundary
     */
    public MirrorRunEvidence project(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(execution, "execution");
        TestRunEvidence source = Objects.requireNonNull(execution.evidence(), "execution.evidence");
        MirrorPlan plan = request.compiledPlan().plan();
        validateSource(plan, execution, source);
        List<MirrorResolution> exactResolutions = resolutions == null
                ? List.of() : List.copyOf(resolutions);
        validateResolutionClosure(plan, source.nodeTrace(), exactResolutions);
        IndependentTestEngineFactory.Configuration configuration = Objects.requireNonNull(
                engineConfiguration, "engineConfiguration");

        List<String> isolationLimitations = List.of(DEPLOYMENT_EGRESS_NOT_ATTESTED);
        MirrorRunEvidence.IsolationFacts isolation = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                configuration.interceptorTypes(), configuration.listenerTypes(),
                configuration.durableStores(), configuration.productionContextCarriers(),
                configuration.productionExtensionListeners(),
                plan.policy().realExternalCallsAllowed(),
                plan.policy().externalCredentialsAllowed(),
                plan.policy().networkEgressAllowed(), false, null, isolationLimitations);
        Set<String> limitations = new LinkedHashSet<>(isolationLimitations);
        if (source.evidenceClass() == TestRunEvidence.EvidenceClass.EXPLORATORY) {
            limitations.add(SHARED_TEST_EVIDENCE_EXPLORATORY);
        }
        return new MirrorRunEvidence("", source.runId(), request.requestId(),
                fingerprint(request.context().asMap()), plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(), plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(), projectBindings(plan), plan.scope(),
                plan.policy().authorizedPurpose(), MirrorRunEvidence.Status.valueOf(source.status().name()),
                MirrorRunEvidence.EvidenceClass.EXPLORATORY,
                source.semanticResultFingerprint(), source.startedAt(), source.completedAt(),
                projectNodes(source.nodeTrace()), projectEdges(source.edgeTrace()), exactResolutions,
                isolation, List.copyOf(limitations));
    }

    private static List<MirrorRunEvidence.ExternalBinding> projectBindings(MirrorPlan plan) {
        return plan.externalBindings().stream().map(binding ->
                new MirrorRunEvidence.ExternalBinding(binding.parentCapabilityRef(),
                        binding.dependencyNodeId(), binding.capabilityRef(),
                        binding.invocationSiteId(), binding.graphPath())).toList();
    }

    private void validateSource(
            MirrorPlan plan, TestExecutionResult execution, TestRunEvidence source) {
        if (execution.plan() == null
                || !plan.executionControlFingerprint().equals(execution.plan().planFingerprint())
                || !plan.executionControlFingerprint().equals(source.planFingerprint())
                || !plan.fixtureBundleRef().fingerprint().equals(source.fixtureBundleFingerprint())
                || !plan.policy().authorizedPurpose().equals(source.executionPurpose())
                || source.semanticResultFingerprint().isBlank()
                || !TestSemanticResultFingerprint.matches(mapper, source)) {
            throw new IllegalArgumentException(
                    "shared test evidence does not match the exact mirror generation");
        }
    }

    private void validateResolutionClosure(
            MirrorPlan plan,
            List<TestRunEvidence.NodeTrace> nodes,
            List<MirrorResolution> resolutions) {
        Map<String, MirrorPlan.ExternalBinding> bindings = new LinkedHashMap<>();
        for (MirrorPlan.ExternalBinding binding : plan.externalBindings()) {
            bindings.put(binding.invocationSiteId(), binding);
        }
        Map<Coordinate, AttemptProjection> expected = new LinkedHashMap<>();
        for (TestRunEvidence.NodeTrace node : nodes) {
            MirrorPlan.ExternalBinding binding = bindings.get(node.invocationSiteId());
            if (binding == null) {
                continue;
            }
            if (!binding.graphPath().equals(node.graphPath())) {
                throw new IllegalArgumentException(
                        "external node evidence graph path differs from its mirror binding");
            }
            for (TestRunEvidence.AttemptTrace attempt : node.attempts()) {
                Coordinate coordinate = new Coordinate(node.invocationSiteId(),
                        node.correlationKey(), node.occurrence(), attempt.attempt());
                AttemptProjection projection = new AttemptProjection(binding,
                        fingerprint(attempt.input()), fingerprint(attempt.output()));
                if (expected.put(coordinate, projection) != null) {
                    throw new IllegalArgumentException(
                            "external node evidence contains duplicate attempt coordinates");
                }
            }
        }
        Map<Coordinate, MirrorResolution> actual = new LinkedHashMap<>();
        for (MirrorResolution resolution : resolutions) {
            Coordinate coordinate = Coordinate.from(resolution);
            if (actual.put(coordinate, resolution) != null) {
                throw new IllegalArgumentException(
                        "mirror resolution evidence contains duplicate attempt coordinates");
            }
        }
        if (!expected.keySet().equals(actual.keySet())) {
            throw new IllegalArgumentException(
                    "external delegate attempts and mirror resolutions must form an exact closure");
        }
        expected.forEach((coordinate, attempt) -> {
            MirrorResolution resolution = actual.get(coordinate);
            if (!attempt.binding().capabilityRef().equals(resolution.capabilityRef())
                    || !attempt.binding().graphPath().equals(resolution.graphPath())
                    || !attempt.requestFingerprint().equals(resolution.requestFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror resolution identity differs from its external delegate attempt");
            }
            if (!resolution.outputFingerprint().isBlank()
                    && !attempt.outputFingerprint().equals(resolution.outputFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror resolution output differs from its external delegate attempt");
            }
        });
    }

    private List<MirrorRunEvidence.NodeTrace> projectNodes(
            List<TestRunEvidence.NodeTrace> values) {
        List<MirrorRunEvidence.NodeTrace> result = new ArrayList<>(values.size());
        for (TestRunEvidence.NodeTrace value : values) {
            List<MirrorRunEvidence.AttemptTrace> attempts = value.attempts().stream()
                    .map(attempt -> new MirrorRunEvidence.AttemptTrace(attempt.attempt(),
                            attempt.status(), attempt.fidelity(), fingerprint(attempt.input()),
                            fingerprint(attempt.output()), attempt.errorCode(), attempt.durationMs()))
                    .toList();
            result.add(new MirrorRunEvidence.NodeTrace(value.nodeId(), value.operatorRef(),
                    value.status(), value.fidelity(), fingerprint(value.input()),
                    fingerprint(value.output()), value.errorCode(), value.durationMs(),
                    value.invocationSiteId(), value.graphPath(), value.correlationKey(),
                    value.occurrence(), value.graphOccurrence(), attempts));
        }
        return result;
    }

    private List<MirrorRunEvidence.EdgeTrace> projectEdges(
            List<TestRunEvidence.EdgeTrace> values) {
        return values.stream().map(value -> new MirrorRunEvidence.EdgeTrace(value.edgeId(),
                value.status(), fingerprint(value.value()), value.graphPath(),
                value.correlationKey(), value.graphOccurrence(), value.fromInvocationSiteId(),
                value.toInvocationSiteId())).toList();
    }

    private String fingerprint(Object value) {
        return ProtocolFingerprint.ofBounded(mapper, value, MAXIMUM_PAYLOAD_BYTES);
    }

    private record Coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt
    ) {
        private static Coordinate from(MirrorResolution resolution) {
            return new Coordinate(resolution.invocationSiteId(), resolution.correlationKey(),
                    resolution.occurrence(), resolution.attempt());
        }
    }

    private record AttemptProjection(
            MirrorPlan.ExternalBinding binding,
            String requestFingerprint,
            String outputFingerprint
    ) {
    }
}
