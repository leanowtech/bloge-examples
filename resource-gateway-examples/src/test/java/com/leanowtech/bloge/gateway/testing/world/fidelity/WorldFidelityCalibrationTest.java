package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldFidelityCalibrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT = fp("contract");
    private static final String IMPLEMENTATION = fp("implementation");
    private static final String WORLD = fp("world");
    private static final String POLICY = fp("policy");

    @Test
    void equivalentAndLatencyOnlyDifferencePasses() {
        AtomicInteger calls = new AtomicInteger();
        WorldFidelityRequest request = request(List.of(sample("a", 1)), schema());
        WorldFidelityRunner real = ignored -> execution(json("name", "A", "count", 1), 200, 4);
        WorldFidelityRunner world = ignored -> {
            calls.incrementAndGet();
            return execution(json("name", "A", "count", 1), 200, 11);
        };

        WorldFidelityReport report = service().calibrate(request, authority(), real, world);

        assertThat(report.outcome()).describedAs("paths=%s", report.observations().getFirst().differencePaths())
                .isEqualTo(WorldFidelityReport.Outcome.EQUIVALENT);
        assertThat(report.observations().getFirst().differencePaths()).isEmpty();
        assertThat(report.observations().getFirst().latencyDeltaMillis()).isEqualTo(7);
        assertThat(calls).hasValue(1);
    }

    @Test
    void requiredFieldAndTypeDifferencesAreVisibleWithoutPayload() {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), schema());
        WorldFidelityReport report = service().calibrate(request, authority(),
                ignored -> execution(json("name", "A", "count", 1), 200, 1),
                ignored -> execution(json("count", "wrong"), 200, 2));

        assertThat(report.outcome()).isEqualTo(WorldFidelityReport.Outcome.DIFFERENT);
        assertThat(report.observations().getFirst().differencePaths())
                .contains("/worldResponse/name", "/worldResponse/count/$type");
        assertThat(report.toString()).doesNotContain("wrong").doesNotContain("A");
    }

    @Test
    void errorStatusRetryAndStateTransitionSemanticsAreCompared() {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), Map.of());
        WorldFidelityRunner.Execution real = new WorldFidelityRunner.Execution(null, "NOT_FOUND", 404, true,
                List.of(new WorldFidelityRunner.StateTransition("/ride", "COMMITTED", fp("state-a"))), 1);
        WorldFidelityRunner.Execution world = new WorldFidelityRunner.Execution(null, "VALIDATION", 422, false,
                List.of(), 1);

        WorldFidelityReport report = service().calibrate(request, authority(), ignored -> real, ignored -> world);

        assertThat(report.outcome()).isEqualTo(WorldFidelityReport.Outcome.DIFFERENT);
        assertThat(report.observations().getFirst().differencePaths())
                .contains("/status", "/errorClass", "/retryable", "/stateTransitions/$size");
    }

    @Test
    void productionAndAllAdmissionFailuresRunNeitherSide() {
        AtomicInteger realCalls = new AtomicInteger();
        AtomicInteger worldCalls = new AtomicInteger();
        WorldFidelityRunner real = ignored -> {
            realCalls.incrementAndGet();
            return execution(json("ok", true), 200, 1);
        };
        WorldFidelityRunner world = ignored -> {
            worldCalls.incrementAndGet();
            return execution(json("ok", true), 200, 1);
        };
        WorldFidelityRequest production = request(List.of(sample("a", 1)), Map.of(),
                WorldFidelityRequest.Environment.PRODUCTION, true);

        assertThatThrownBy(() -> service().calibrate(production, authority(), real, world))
                .isInstanceOf(WorldFidelityException.class)
                .satisfies(error -> assertThat(((WorldFidelityException) error).code())
                        .isEqualTo(WorldFidelityException.Code.ADMISSION_DENIED));
        assertThat(realCalls).hasValue(0);
        assertThat(worldCalls).hasValue(0);
        assertThatThrownBy(() -> service().calibrate(request(List.of(sample("a", 1)), Map.of()),
                (access, target) -> new WorldFidelityRequest.AuthorizedTarget("other", access.scope(),
                        WorldFidelityRequest.Environment.NON_PRODUCTION, target.contractId(), target.contractFingerprint(),
                        target.implementationFingerprint(), target.worldSliceFingerprint(), target.policyFingerprint(), true, true),
                real, world)).isInstanceOf(WorldFidelityException.class);
        assertThat(realCalls).hasValue(0);

        WorldFidelityRequest unauthorized = request(List.of(sample("a", 1)), Map.of(),
                WorldFidelityRequest.Environment.NON_PRODUCTION, false);
        assertThatThrownBy(() -> service().calibrate(unauthorized, authority(), real, world))
                .isInstanceOf(WorldFidelityException.class);
        assertThat(realCalls).hasValue(0);
    }

    @Test
    void timeoutAndOneSideFailureAreFailClosed() {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), Map.of());
        WorldFidelityReport report = service().calibrate(request, authority(),
                ignored -> { throw new java.util.concurrent.TimeoutException("secret-payload"); },
                ignored -> execution(json("ok", true), 200, 1));

        assertThat(report.outcome()).isEqualTo(WorldFidelityReport.Outcome.DIFFERENT);
        assertThat(report.observations().getFirst().realErrorClass()).isEqualTo("TIMEOUT");
        assertThat(report.toString()).doesNotContain("secret-payload");
    }

    @Test
    void bothRunnerFailuresAreUnknownAndAlwaysGateBlocked() {
        WorldFidelityRequest request = request(List.of(sample("both-fail", 1)), Map.of());
        WorldFidelityReport report = service().calibrate(request, authority(), ignored -> {
            throw new IllegalStateException("real-secret-payload");
        }, ignored -> {
            throw new IllegalStateException("world-secret-payload");
        });

        assertThat(report.outcome()).isEqualTo(WorldFidelityReport.Outcome.UNKNOWN);
        assertThat(report.gateBlocked()).isTrue();
        assertThat(report.toString()).doesNotContain("secret-payload");
    }

    @Test
    void wrongPurposeIsRejectedBeforeAuthorityOrEitherRunner() {
        WorldFidelityRequest valid = request(List.of(sample("wrong-purpose", 1)), Map.of());
        AtomicInteger authorityCalls = new AtomicInteger();
        AtomicInteger realCalls = new AtomicInteger();
        AtomicInteger worldCalls = new AtomicInteger();
        WorldFidelityRequest wrongPurpose = new WorldFidelityRequest(
                new WorldFidelityRequest.Access("tenant-a", "support", "OTHER_PURPOSE",
                        WorldFidelityRequest.Environment.NON_PRODUCTION, true), valid.target(), valid.sampleSet(),
                valid.comparator(), valid.requestFingerprint());

        assertThatThrownBy(() -> service().calibrate(wrongPurpose, (access, target) -> {
            authorityCalls.incrementAndGet();
            return authority().authorize(access, target);
        }, ignored -> {
            realCalls.incrementAndGet();
            return execution(null, 200, 1);
        }, ignored -> {
            worldCalls.incrementAndGet();
            return execution(null, 200, 1);
        })).isInstanceOf(WorldFidelityException.class);
        assertThat(authorityCalls).hasValue(0);
        assertThat(realCalls).hasValue(0);
        assertThat(worldCalls).hasValue(0);
    }

    @Test
    void tamperedSampleDenominatorAndPolicyFailBeforeEitherRunner() {
        AtomicInteger realCalls = new AtomicInteger();
        AtomicInteger worldCalls = new AtomicInteger();
        WorldFidelityRequest.Sample sample = sample("a", 1);
        String tampered = fp("tampered-sample-set");
        WorldFidelityRequest.SampleSet set = new WorldFidelityRequest.SampleSet("samples", 1, List.of(sample),
                tampered, true, true);
        WorldFidelityRequest.Target target = new WorldFidelityRequest.Target("ride.lookup", CONTRACT,
                IMPLEMENTATION, WORLD, POLICY);
        WorldFidelityRequest request = new WorldFidelityRequest(
                new WorldFidelityRequest.Access("tenant-a", "support",
                        WorldFidelityCalibrationService.CALIBRATION_PURPOSE,
                        WorldFidelityRequest.Environment.NON_PRODUCTION, true), target, set,
                comparator(Map.of()), tampered);
        assertThatThrownBy(() -> service().calibrate(request, authority(), ignored -> {
            realCalls.incrementAndGet();
            return execution(json("ok", true), 200, 1);
        }, ignored -> {
            worldCalls.incrementAndGet();
            return execution(json("ok", true), 200, 1);
        })).isInstanceOf(WorldFidelityException.class);
        assertThat(realCalls).hasValue(0);
        assertThat(worldCalls).hasValue(0);

        WorldFidelityRequest good = request(List.of(sample), Map.of());
        WorldFidelityRequest badPolicy = new WorldFidelityRequest(good.access(), good.target(), good.sampleSet(),
                new WorldFidelityRequest.ComparatorSpec("cmp.v1", fp("other-policy"), Map.of(), Map.of(), true, true),
                good.requestFingerprint());
        assertThatThrownBy(() -> service().calibrate(badPolicy, authority(), ignored -> execution(null, 200, 1),
                ignored -> execution(null, 200, 1))).isInstanceOf(WorldFidelityException.class);
    }

    @Test
    void malformedProtocolValuesAndUnknownComparatorFailClosed() {
        assertThatThrownBy(() -> new WorldFidelityRequest.Target("contract", "bad", IMPLEMENTATION, WORLD, POLICY))
                .isInstanceOf(WorldFidelityException.class);
        assertThatThrownBy(() -> new WorldFidelityRequest.ComparatorSpec("cmp.v1", POLICY, Map.of(), Map.of(), false, true))
                .isInstanceOf(WorldFidelityException.class);
        assertThatThrownBy(() -> new WorldFidelityRunner.Execution(null, "secret-payload", 500, false, List.of(), 0))
                .isInstanceOf(WorldFidelityException.class)
                .hasMessage(WorldFidelityException.Code.INVALID_INPUT.name());
        assertThatThrownBy(() -> new WorldFidelityRunner.StateTransition("/a", "payload text", fp("state")))
                .isInstanceOf(WorldFidelityException.class);
    }

    @Test
    void reportRoundTripIsDeterministicAndPayloadFree() throws Exception {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), schema());
        WorldFidelityReport report = service().calibrate(request, authority(),
                ignored -> execution(json("name", "PAYLOAD-CANARY", "count", 1), 200, 1),
                ignored -> execution(json("name", "PAYLOAD-CANARY", "count", 1), 200, 1));
        String encoded = MAPPER.writeValueAsString(report);
        assertThat(encoded).doesNotContain("PAYLOAD-CANARY");
        assertThat(report.verify(MAPPER)).isTrue();
        assertThat(report.seal(MAPPER).reportFingerprint()).isEqualTo(report.reportFingerprint());
        WorldFidelityReport tampered = new WorldFidelityReport(report.algorithmVersion(), report.requestFingerprint(),
                fp("wrong-target"), report.sampleSetFingerprint(), report.implementationFingerprint(),
                report.worldSliceFingerprint(), report.contractFingerprint(), report.comparatorFingerprint(),
                report.observations(), report.outcome(), report.reportFingerprint());
        assertThat(tampered.verify(MAPPER)).isFalse();
    }

    @Test
    void repeatedCalibrationIsDeterministicAndRequestPayloadNeverEntersReport() {
        WorldFidelityRequest request = request(List.of(sample("b", 2), sample("a", 1)), schema());
        List<String> fingerprints = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            WorldFidelityReport report = service().calibrate(request, authority(),
                    ignored -> execution(json("name", "CANARY", "count", 1), 200, 1),
                    ignored -> execution(json("name", "CANARY", "count", 1), 200, 1));
            fingerprints.add(report.reportFingerprint());
            assertThat(report.toString()).doesNotContain("CANARY");
        }
        assertThat(fingerprints).containsOnly(fingerprints.getFirst());
    }

    @Test
    void eachRunnerReceivesAnIndependentCanonicalRequestSnapshot() {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), Map.of());
        AtomicInteger worldSawOriginal = new AtomicInteger();
        WorldFidelityReport report = service().calibrate(request, authority(), value -> {
            ((JsonNode) value).deepCopy();
            ((ObjectNode) value).put("mutated", "PAYLOAD-CANARY");
            return execution(json("ok", true), 200, 1);
        }, value -> {
            if (((JsonNode) value).has("mutated")) worldSawOriginal.set(0);
            else worldSawOriginal.set(1);
            return execution(json("ok", true), 200, 1);
        });
        assertThat(report.outcome()).isEqualTo(WorldFidelityReport.Outcome.EQUIVALENT);
        assertThat(worldSawOriginal).hasValue(1);
    }

    @Test
    void concurrentCalibrationInvocationsDoNotShareRunState() throws Exception {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), Map.of());
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
            start.await();
            return service().calibrate(request, authority(), ignored -> execution(json("ok", true), 200, 1),
                    ignored -> execution(json("ok", true), 200, 1)).reportFingerprint();
        }));
        start.countDown();
        List<String> fingerprints = new java.util.ArrayList<>();
        for (var future : futures) fingerprints.add(future.get());
        executor.shutdownNow();
        assertThat(fingerprints).containsOnly(fingerprints.getFirst());
    }

    @Test
    void driftLifecycleUsesCasAndExternalReceipt() throws Exception {
        WorldFidelityRequest request = request(List.of(sample("a", 1)), Map.of());
        WorldFidelityReport report = service().calibrate(request, authority(),
                ignored -> execution(json("ok", true), 200, 1),
                ignored -> execution(json("ok", false), 200, 1));
        InMemoryWorldFidelityDriftRepository repository = new InMemoryWorldFidelityDriftRepository();
        WorldFidelityDriftService drift = new WorldFidelityDriftService(repository);
        drift.observe("tenant-a", report);
        drift.transition("tenant-a", report.targetFingerprint(), WorldFidelityDriftRepository.DriftState.SUSPECTED,
                WorldFidelityDriftRepository.DriftState.CONFIRMED);
        assertThat(drift.publicationAllowed("tenant-a", report.targetFingerprint())).isFalse();
        assertThat(drift.evidenceCeiling("tenant-a", report.targetFingerprint()))
                .isEqualTo(WorldFidelityDriftService.EvidenceCeiling.EXPLORATORY);
        String receiptFp = ProtocolFingerprint.ofText("receipt-1\u0000tenant-a\u0000"
                + report.targetFingerprint() + "\u0000" + report.reportFingerprint()
                + "\u0000reviewer\u0000" + WorldFidelityDriftService.APPROVAL_PURPOSE);
        WorldFidelityDriftService.WorldFidelityApprovalReceipt receipt = new WorldFidelityDriftService.WorldFidelityApprovalReceipt(
                "receipt-1", "tenant-a", report.targetFingerprint(), report.reportFingerprint(), "reviewer",
                WorldFidelityDriftService.APPROVAL_PURPOSE, receiptFp);
        drift.acceptDivergence("tenant-a", report.targetFingerprint(), receipt,
                (tenant, target, value) -> tenant.equals(value.tenantId()) && target.equals(value.targetFingerprint())
                        && value.purpose().equals(WorldFidelityDriftService.APPROVAL_PURPOSE));
        assertThat(drift.publicationAllowed("tenant-a", report.targetFingerprint())).isTrue();
        assertThatThrownBy(() -> drift.acceptDivergence("tenant-a", report.targetFingerprint(), receipt,
                (tenant, target, value) -> true)).isInstanceOf(WorldFidelityException.class);

        WorldFidelityDriftService.WorldFidelityApprovalReceipt wrongReport = new WorldFidelityDriftService.WorldFidelityApprovalReceipt(
                "receipt-2", "tenant-a", report.targetFingerprint(), fp("other-report"), "reviewer",
                WorldFidelityDriftService.APPROVAL_PURPOSE,
                ProtocolFingerprint.ofText("receipt-2\u0000tenant-a\u0000" + report.targetFingerprint()
                        + "\u0000" + fp("other-report") + "\u0000reviewer\u0000"
                        + WorldFidelityDriftService.APPROVAL_PURPOSE));
        assertThatThrownBy(() -> drift.acceptDivergence("tenant-a", report.targetFingerprint(), wrongReport,
                (tenant, target, value) -> true)).isInstanceOf(WorldFidelityException.class);

        assertThatThrownBy(() -> drift.transition("tenant-a", report.targetFingerprint(),
                WorldFidelityDriftRepository.DriftState.CURRENT, WorldFidelityDriftRepository.DriftState.CONFIRMED))
                .isInstanceOf(WorldFidelityException.class);

        CountDownLatch ready = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        Runnable transition = () -> {
            ready.countDown();
            try {
                ready.await();
                if (repository.compareAndSet("tenant-b", report.targetFingerprint(), null,
                        new WorldFidelityDriftRepository.DriftAnnotation(
                                WorldFidelityDriftRepository.DriftState.CURRENT, report.reportFingerprint(),
                                report.targetFingerprint(), report.contractFingerprint(), report.worldSliceFingerprint(),
                                report.implementationFingerprint(), report.sampleSetFingerprint()))) successes.incrementAndGet();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(transition);
        Thread second = new Thread(transition);
        first.start(); second.start(); first.join(); second.join();
        assertThat(successes).hasValue(1);
    }

    @Test
    void inMemoryHistoryIsIndexedByTenantAndTargetBeforeReportFingerprint() {
        InMemoryWorldFidelityDriftRepository repository = new InMemoryWorldFidelityDriftRepository();
        WorldFidelityReport first = calibrated("history-first", true);
        WorldFidelityReport second = calibrated("history-second", true);

        repository.append("tenant-a", first);
        repository.append("tenant-a", second);

        assertThat(repository.history("tenant-a", first.targetFingerprint()))
                .containsExactlyInAnyOrder(first, second);
        assertThat(repository.history("tenant-b", first.targetFingerprint())).isEmpty();
        assertThat(repository.history("tenant-a", fp("other-target"))).isEmpty();
    }

    @Test
    void observationsCannotAutomaticallyClearSuspectedConfirmedRemediatingOrAccepted() {
        InMemoryWorldFidelityDriftRepository repository = new InMemoryWorldFidelityDriftRepository();
        WorldFidelityDriftService drift = new WorldFidelityDriftService(repository);
        WorldFidelityReport initial = calibrated("state-0", true);
        drift.observe("tenant-a", initial);
        drift.observe("tenant-a", calibrated("state-1", false));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.SUSPECTED);
        drift.transition("tenant-a", initial.targetFingerprint(), WorldFidelityDriftRepository.DriftState.SUSPECTED,
                WorldFidelityDriftRepository.DriftState.CONFIRMED);
        drift.observe("tenant-a", calibrated("state-2", true));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.CONFIRMED);
        drift.transition("tenant-a", initial.targetFingerprint(), WorldFidelityDriftRepository.DriftState.CONFIRMED,
                WorldFidelityDriftRepository.DriftState.REMEDIATING);
        drift.observe("tenant-a", calibrated("state-3", true));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.REMEDIATING);
        drift.transition("tenant-a", initial.targetFingerprint(), WorldFidelityDriftRepository.DriftState.REMEDIATING,
                WorldFidelityDriftRepository.DriftState.CURRENT);
        drift.observe("tenant-a", calibrated("state-4", true));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.CURRENT);
        drift.observe("tenant-a", calibrated("state-5", false));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.SUSPECTED);
        drift.transition("tenant-a", initial.targetFingerprint(), WorldFidelityDriftRepository.DriftState.SUSPECTED,
                WorldFidelityDriftRepository.DriftState.CONFIRMED);
        WorldFidelityDriftRepository.DriftAnnotation confirmed = repository.current("tenant-a",
                initial.targetFingerprint()).orElseThrow();
        drift.acceptDivergence("tenant-a", initial.targetFingerprint(), receipt(confirmed), (t, target, value) -> true);
        drift.observe("tenant-a", calibrated("state-6", false));
        assertState(repository, initial, WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE);
    }

    @Test
    void failedAtomicAcceptanceDoesNotBurnReceiptAndConcurrentReuseWinsOnce() throws Exception {
        InMemoryWorldFidelityDriftRepository delegate = new InMemoryWorldFidelityDriftRepository();
        AtomicInteger failOnce = new AtomicInteger(1);
        WorldFidelityDriftRepository repository = new WorldFidelityDriftRepository() {
            @Override public java.util.Optional<DriftAnnotation> current(String tenant, String target) {
                return delegate.current(tenant, target);
            }
            @Override public void append(String tenant, WorldFidelityReport report) { delegate.append(tenant, report); }
            @Override public boolean compareAndSet(String tenant, String target, DriftState expected, DriftAnnotation next) {
                return delegate.compareAndSet(tenant, target, expected, next);
            }
            @Override public boolean compareAndSetAndConsumeReceipt(String tenant, String target, DriftState expected,
                                                                      DriftAnnotation next, String receiptFingerprint) {
                return failOnce.getAndDecrement() > 0 ? false
                        : delegate.compareAndSetAndConsumeReceipt(tenant, target, expected, next, receiptFingerprint);
            }
            @Override public boolean consumeReceipt(String tenant, String fingerprint) {
                return delegate.consumeReceipt(tenant, fingerprint);
            }
            @Override public java.util.List<WorldFidelityReport> history(String tenant, String target) {
                return delegate.history(tenant, target);
            }
        };
        WorldFidelityDriftService drift = new WorldFidelityDriftService(repository);
        WorldFidelityReport report = calibrated("receipt-race", false);
        drift.observe("tenant-a", report);
        drift.transition("tenant-a", report.targetFingerprint(), WorldFidelityDriftRepository.DriftState.SUSPECTED,
                WorldFidelityDriftRepository.DriftState.CONFIRMED);
        WorldFidelityDriftRepository.DriftAnnotation annotation = delegate.current("tenant-a",
                report.targetFingerprint()).orElseThrow();
        WorldFidelityDriftService.WorldFidelityApprovalReceipt approval = receipt(annotation);
        assertThatThrownBy(() -> drift.acceptDivergence("tenant-a", report.targetFingerprint(), approval,
                (tenant, target, value) -> true)).isInstanceOf(WorldFidelityException.class)
                .extracting(error -> ((WorldFidelityException) error).code())
                .isEqualTo(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        assertThat(delegate.current("tenant-a", report.targetFingerprint()).orElseThrow().state())
                .isEqualTo(WorldFidelityDriftRepository.DriftState.CONFIRMED);
        drift.acceptDivergence("tenant-a", report.targetFingerprint(), approval, (tenant, target, value) -> true);

        InMemoryWorldFidelityDriftRepository concurrentRepository = new InMemoryWorldFidelityDriftRepository();
        WorldFidelityDriftService concurrentDrift = new WorldFidelityDriftService(concurrentRepository);
        WorldFidelityReport concurrentReport = calibrated("receipt-concurrent", false);
        concurrentDrift.observe("tenant-a", concurrentReport);
        concurrentDrift.transition("tenant-a", concurrentReport.targetFingerprint(),
                WorldFidelityDriftRepository.DriftState.SUSPECTED, WorldFidelityDriftRepository.DriftState.CONFIRMED);
        WorldFidelityDriftService.WorldFidelityApprovalReceipt concurrentApproval = receipt(
                concurrentRepository.current("tenant-a", concurrentReport.targetFingerprint()).orElseThrow());
        AtomicInteger successes = new AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (var future : pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(
                    () -> accept(concurrentDrift, concurrentReport, concurrentApproval),
                    () -> accept(concurrentDrift, concurrentReport, concurrentApproval)))) {
                if (future.get()) successes.incrementAndGet();
            }
        }
        assertThat(successes).hasValue(1);
    }

    private static WorldFidelityCalibrationService service() { return new WorldFidelityCalibrationService(MAPPER); }

    private static WorldFidelityCalibrationService.WorldFidelitySourceAuthority authority() {
        return (access, target) -> new WorldFidelityRequest.AuthorizedTarget(access.tenantId(), access.scope(),
                WorldFidelityRequest.Environment.NON_PRODUCTION, target.contractId(), target.contractFingerprint(),
                target.implementationFingerprint(), target.worldSliceFingerprint(), target.policyFingerprint(), true, true);
    }

    private static WorldFidelityRequest request(List<WorldFidelityRequest.Sample> samples,
                                                Map<String, Object> schema) {
        return request(samples, schema, WorldFidelityRequest.Environment.NON_PRODUCTION, true);
    }

    private static WorldFidelityRequest request(List<WorldFidelityRequest.Sample> samples,
                                                Map<String, Object> schema,
                                                WorldFidelityRequest.Environment environment,
                                                boolean authorized) {
        String sampleFingerprint = ProtocolFingerprint.of(MAPPER, samples.stream()
                .sorted(java.util.Comparator.comparing(WorldFidelityRequest.Sample::sampleId))
                .map(WorldFidelityRequest.Sample::request).toList());
        WorldFidelityRequest.SampleSet sampleSet = new WorldFidelityRequest.SampleSet("samples", 1, samples,
                sampleFingerprint, true, true);
        WorldFidelityRequest.Target target = new WorldFidelityRequest.Target("ride.lookup", CONTRACT, IMPLEMENTATION, WORLD, POLICY);
        return new WorldFidelityRequest(new WorldFidelityRequest.Access("tenant-a", "support",
                WorldFidelityCalibrationService.CALIBRATION_PURPOSE, environment, authorized),
                target, sampleSet, new WorldFidelityRequest.ComparatorSpec("cmp.v1", POLICY,
                Map.of("/count", new BigDecimal("1")), schema, true, true), sampleFingerprint);
    }

    private static WorldFidelityRequest.ComparatorSpec comparator(Map<String, Object> schema) {
        return new WorldFidelityRequest.ComparatorSpec("cmp.v1", POLICY,
                Map.of("/count", new BigDecimal("1")), schema, true, true);
    }

    private static WorldFidelityRequest.Sample sample(String id, int value) {
        ObjectNode request = JsonNodeFactory.instance.objectNode().put("id", id).put("value", value);
        return new WorldFidelityRequest.Sample(id, request, ProtocolFingerprint.of(MAPPER, request));
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "required", List.of("name", "count"),
                "properties", Map.of("name", Map.of("type", "string"), "count", Map.of("type", "integer")));
    }

    private static WorldFidelityRunner.Execution execution(JsonNode response, int status, long duration) {
        return new WorldFidelityRunner.Execution(response, "", status, false, List.of(), duration);
    }

    private static ObjectNode json(String key, Object value, Object... rest) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set(key, MAPPER.valueToTree(value));
        for (int i = 0; i < rest.length; i += 2) node.set(String.valueOf(rest[i]), MAPPER.valueToTree(rest[i + 1]));
        return node;
    }

    private static String fp(String value) { return ProtocolFingerprint.ofText(value); }

    private static WorldFidelityReport calibrated(String sampleId, boolean equal) {
        WorldFidelityRequest request = request(List.of(sample(sampleId, 1)), Map.of());
        return service().calibrate(request, authority(), ignored -> execution(json("ok", true), 200, 1),
                ignored -> execution(json("ok", equal), 200, 1));
    }

    private static void assertState(InMemoryWorldFidelityDriftRepository repository, WorldFidelityReport report,
                                    WorldFidelityDriftRepository.DriftState expected) {
        assertThat(repository.current("tenant-a", report.targetFingerprint()).orElseThrow().state())
                .isEqualTo(expected);
    }

    private static WorldFidelityDriftService.WorldFidelityApprovalReceipt receipt(
            WorldFidelityDriftRepository.DriftAnnotation annotation) {
        String id = "receipt-" + annotation.reportFingerprint().substring(7, 15);
        String receiptFingerprint = ProtocolFingerprint.ofText(id + "\u0000tenant-a\u0000"
                + annotation.targetFingerprint() + "\u0000" + annotation.reportFingerprint()
                + "\u0000reviewer\u0000" + WorldFidelityDriftService.APPROVAL_PURPOSE);
        return new WorldFidelityDriftService.WorldFidelityApprovalReceipt(id, "tenant-a",
                annotation.targetFingerprint(), annotation.reportFingerprint(), "reviewer",
                WorldFidelityDriftService.APPROVAL_PURPOSE, receiptFingerprint);
    }

    private static boolean accept(WorldFidelityDriftService drift, WorldFidelityReport report,
                                  WorldFidelityDriftService.WorldFidelityApprovalReceipt receipt) {
        try {
            drift.acceptDivergence("tenant-a", report.targetFingerprint(), receipt, (tenant, target, value) -> true);
            return true;
        } catch (WorldFidelityException ignored) {
            return false;
        }
    }
}
