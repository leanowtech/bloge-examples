package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Server-resolved, exact-reference input to one controlled World fidelity calibration. */
public record WorldFidelityRequest(
        Access access,
        Target target,
        SampleSet sampleSet,
        ComparatorSpec comparator,
        String requestFingerprint
) {
    public static final String ALGORITHM_VERSION = "world-fidelity-comparator.v1";
    public static final int MAX_SAMPLES = 4096;
    public WorldFidelityRequest {
        access = Objects.requireNonNull(access, "access");
        target = Objects.requireNonNull(target, "target");
        sampleSet = Objects.requireNonNull(sampleSet, "sampleSet");
        comparator = Objects.requireNonNull(comparator, "comparator");
        requestFingerprint = fingerprint(requestFingerprint);
        if (!requestFingerprint.equals(sampleSet.fingerprint())) {
            throw new WorldFidelityException(WorldFidelityException.Code.SOURCE_DRIFT);
        }
    }

    public static WorldFidelityRequest of(Access access, Target target, SampleSet sampleSet,
                                           ComparatorSpec comparator, ObjectMapper mapper) {
        return new WorldFidelityRequest(access, target, sampleSet, comparator,
                ProtocolFingerprint.ofBounded(mapper, sampleSet.requests(), 16 * 1024 * 1024));
    }

    public record Access(String tenantId, String scope, String purpose, Environment environment,
                         boolean sourceAuthorized) {
        public Access {
            tenantId = text(tenantId, 256);
            scope = text(scope, 512);
            purpose = text(purpose, 256);
            environment = Objects.requireNonNull(environment, "environment");
        }
    }

    public enum Environment { NON_PRODUCTION, PRODUCTION }

    public record Target(String contractId, String contractFingerprint, String implementationFingerprint,
                         String worldSliceFingerprint, String policyFingerprint) {
        public static Target forPublishedSlice(WorldSlice slice, String implementationFingerprint,
                                               String policyFingerprint) {
            if (slice == null || !slice.bindingValid()) {
                throw new WorldFidelityException(WorldFidelityException.Code.SOURCE_DRIFT);
            }
            return new Target(slice.logicalContractId(), slice.contractFingerprint(), implementationFingerprint,
                    slice.fingerprint(), policyFingerprint);
        }

        public Target {
            contractId = text(contractId, 256);
            contractFingerprint = WorldFidelityRunner.fingerprint(contractFingerprint);
            implementationFingerprint = WorldFidelityRunner.fingerprint(implementationFingerprint);
            worldSliceFingerprint = WorldFidelityRunner.fingerprint(worldSliceFingerprint);
            policyFingerprint = WorldFidelityRunner.fingerprint(policyFingerprint);
        }

        public String fingerprint() {
            return com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.ofText(
                    contractId + "\u0000" + contractFingerprint + "\u0000" + implementationFingerprint
                            + "\u0000" + worldSliceFingerprint + "\u0000" + policyFingerprint);
        }
    }

    public record AuthorizedTarget(String tenantId, String scope, Environment environment,
                                   String contractId, String contractFingerprint,
                                   String implementationFingerprint, String worldSliceFingerprint,
                                   String policyFingerprint, boolean publishedWorld,
                                   boolean implementationAvailable) {
        public AuthorizedTarget {
            tenantId = text(tenantId, 256);
            scope = text(scope, 512);
            environment = Objects.requireNonNull(environment, "environment");
            contractId = text(contractId, 256);
            contractFingerprint = WorldFidelityRunner.fingerprint(contractFingerprint);
            implementationFingerprint = WorldFidelityRunner.fingerprint(implementationFingerprint);
            worldSliceFingerprint = WorldFidelityRunner.fingerprint(worldSliceFingerprint);
            policyFingerprint = WorldFidelityRunner.fingerprint(policyFingerprint);
        }
    }

    public record SampleSet(String sampleSetId, long revision, List<Sample> samples, String fingerprint,
                            boolean complete, boolean fresh) {
        public static SampleSet of(String sampleSetId, long revision, List<Sample> samples, ObjectMapper mapper) {
            List<Sample> ordered = samples == null ? List.of() : samples.stream()
                    .sorted(Comparator.comparing(Sample::sampleId)).toList();
            return new SampleSet(sampleSetId, revision, ordered,
                    ProtocolFingerprint.ofBounded(mapper, ordered.stream().map(Sample::request).toList(),
                            16 * 1024 * 1024), true, true);
        }
        public SampleSet {
            sampleSetId = text(sampleSetId, 256);
            if (revision < 1 || samples == null || samples.isEmpty() || samples.size() > MAX_SAMPLES) {
                throw new WorldFidelityException(WorldFidelityException.Code.DENOMINATOR_UNKNOWN);
            }
            samples = samples.stream().sorted(Comparator.comparing(Sample::sampleId)).toList();
            if (samples.stream().map(Sample::sampleId).distinct().count() != samples.size()) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
            fingerprint = WorldFidelityRunner.fingerprint(fingerprint);
            if (!complete || !fresh) {
                throw new WorldFidelityException(WorldFidelityException.Code.DENOMINATOR_UNKNOWN);
            }
        }

        public List<Object> requests() {
            return samples.stream().map(Sample::request).toList();
        }
    }

    public record Sample(String sampleId, Object request, String sampleFingerprint) {
        public static Sample of(String sampleId, Object request, ObjectMapper mapper) {
            return new Sample(sampleId, request, ProtocolFingerprint.of(mapper, request));
        }
        public Sample {
            sampleId = text(sampleId, 256);
            if (request == null) throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            sampleFingerprint = WorldFidelityRunner.fingerprint(sampleFingerprint);
        }
        @Override public String toString() { return "Sample{" + sampleId + '}'; }
    }

    public record ComparatorSpec(String version, String policyFingerprint, Map<String, BigDecimal> tolerances,
                                 Map<String, Object> responseSchema, boolean complete, boolean fresh) {
        public ComparatorSpec {
            version = text(version, 128);
            policyFingerprint = WorldFidelityRunner.fingerprint(policyFingerprint);
            tolerances = tolerances == null ? Map.of() : new TreeMap<>(tolerances);
            if (tolerances.size() > 256 || tolerances.entrySet().stream().anyMatch(e -> e.getKey() == null
                    || e.getKey().isBlank() || e.getValue() == null || e.getValue().signum() < 0)) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
            tolerances = Map.copyOf(tolerances);
            responseSchema = responseSchema == null ? Map.of() : ProtocolJsonValue.freezeMap(responseSchema);
            if (responseSchema.size() > 256) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
            if (!complete || !fresh) throw new WorldFidelityException(WorldFidelityException.Code.COMPARATOR_UNKNOWN);
        }
    }

    static String fingerprint(String value) {
        return WorldFidelityRunner.fingerprint(value);
    }

    static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        return value.trim();
    }
}
