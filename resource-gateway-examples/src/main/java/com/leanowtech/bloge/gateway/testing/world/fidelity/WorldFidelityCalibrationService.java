package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * World-specific convergence adapter. It admits an exact authorized sample set once, then
 * sends the same in-memory canonical request to two injected server-owned runners.
 */
public final class WorldFidelityCalibrationService {
    public static final String CALIBRATION_PURPOSE = "WORLD_FIDELITY_CALIBRATION";
    private final ObjectMapper mapper;
    private final WorldFidelityComparator comparator;

    public WorldFidelityCalibrationService(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.comparator = new WorldFidelityComparator(mapper);
    }

    public WorldFidelityReport calibrate(WorldFidelityRequest request,
                                         WorldFidelitySourceAuthority authority,
                                         WorldFidelityRunner realRunner,
                                         WorldFidelityRunner worldRunner) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(realRunner, "realRunner");
        Objects.requireNonNull(worldRunner, "worldRunner");
        if (!CALIBRATION_PURPOSE.equals(request.access().purpose())) {
            throw new WorldFidelityException(WorldFidelityException.Code.ADMISSION_DENIED);
        }
        WorldFidelityRequest.AuthorizedTarget authorized;
        try {
            authorized = authority.authorize(request.access(), request.target());
        } catch (WorldFidelityException denied) {
            throw denied;
        } catch (RuntimeException denied) {
            throw new WorldFidelityException(WorldFidelityException.Code.ADMISSION_DENIED);
        }
        admit(request, authorized);
        String expectedSamples = ProtocolFingerprint.of(mapper, request.sampleSet().requests());
        if (!expectedSamples.equals(request.sampleSet().fingerprint())
                || !expectedSamples.equals(request.requestFingerprint())) {
            throw new WorldFidelityException(WorldFidelityException.Code.SOURCE_DRIFT);
        }
        List<Object> canonicalRequests = request.sampleSet().samples().stream()
                .map(sample -> canonicalRequest(sample, mapper)).toList();
        List<WorldFidelityReport.Observation> observations = new ArrayList<>();
        boolean executionFailure = false;
        boolean bothFailure = true;
        for (int index = 0; index < request.sampleSet().samples().size(); index++) {
            WorldFidelityRequest.Sample sample = request.sampleSet().samples().get(index);
            Object canonicalRequest = canonicalRequests.get(index);
            Attempt real = run(realRunner, copy(canonicalRequest));
            Attempt world = run(worldRunner, copy(canonicalRequest));
            executionFailure |= real.failed || world.failed;
            bothFailure &= real.failed && world.failed;
            observations.add(comparator.compare(sample, real.execution, world.execution, request.comparator()));
        }
        boolean hasDifferences = observations.stream().anyMatch(o -> !o.differencePaths().isEmpty());
        WorldFidelityReport.Outcome outcome = executionFailure
                ? bothFailure
                ? WorldFidelityReport.Outcome.UNKNOWN
                : hasDifferences ? WorldFidelityReport.Outcome.DIFFERENT : WorldFidelityReport.Outcome.UNKNOWN
                : hasDifferences ? WorldFidelityReport.Outcome.DIFFERENT : WorldFidelityReport.Outcome.EQUIVALENT;
        String comparatorFingerprint = ProtocolFingerprint.of(mapper, request.comparator());
        return new WorldFidelityReport(WorldFidelityRequest.ALGORITHM_VERSION,
                request.requestFingerprint(), request.target().fingerprint(), request.sampleSet().fingerprint(),
                request.target().implementationFingerprint(), request.target().worldSliceFingerprint(),
                request.target().contractFingerprint(), comparatorFingerprint, observations, outcome, "").seal(mapper);
    }

    private static void admit(WorldFidelityRequest request, WorldFidelityRequest.AuthorizedTarget actual) {
        WorldFidelityRequest.Access access = request.access();
        WorldFidelityRequest.Target target = request.target();
        if (!access.sourceAuthorized() || access.environment() != WorldFidelityRequest.Environment.NON_PRODUCTION
                || actual.environment() != WorldFidelityRequest.Environment.NON_PRODUCTION
                || !actual.tenantId().equals(access.tenantId()) || !actual.scope().equals(access.scope())
                || !actual.contractId().equals(target.contractId())
                || !actual.contractFingerprint().equals(target.contractFingerprint())
                || !actual.implementationFingerprint().equals(target.implementationFingerprint())
                || !actual.worldSliceFingerprint().equals(target.worldSliceFingerprint())
                || !actual.policyFingerprint().equals(target.policyFingerprint())
                || !request.comparator().policyFingerprint().equals(target.policyFingerprint())
                || !actual.publishedWorld() || !actual.implementationAvailable()) {
            throw new WorldFidelityException(WorldFidelityException.Code.ADMISSION_DENIED);
        }
    }

    private static Attempt run(WorldFidelityRunner runner, Object request) {
        try {
            WorldFidelityRunner.Execution execution = runner.run(request);
            return new Attempt(Objects.requireNonNull(execution, "execution"), false);
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new Attempt(new WorldFidelityRunner.Execution(null, "TIMEOUT", 598, true, List.of(), 0), true);
        } catch (Exception failure) {
            return new Attempt(new WorldFidelityRunner.Execution(null, "EXECUTION_FAILED", 599, false, List.of(), 0), true);
        }
    }

    private static Object canonicalRequest(WorldFidelityRequest.Sample sample, ObjectMapper mapper) {
        try {
            String fingerprint = ProtocolFingerprint.of(mapper, sample.request());
            if (!fingerprint.equals(sample.sampleFingerprint())) {
                throw new WorldFidelityException(WorldFidelityException.Code.SOURCE_DRIFT);
            }
            return mapper.readTree(mapper.writeValueAsBytes(sample.request()));
        } catch (WorldFidelityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
    }

    private static Object copy(Object request) {
        return request instanceof com.fasterxml.jackson.databind.JsonNode node ? node.deepCopy() : request;
    }

    private record Attempt(WorldFidelityRunner.Execution execution, boolean failed) {
    }

    @FunctionalInterface
    public interface WorldFidelitySourceAuthority {
        WorldFidelityRequest.AuthorizedTarget authorize(WorldFidelityRequest.Access access,
                                                        WorldFidelityRequest.Target target);
    }
}
