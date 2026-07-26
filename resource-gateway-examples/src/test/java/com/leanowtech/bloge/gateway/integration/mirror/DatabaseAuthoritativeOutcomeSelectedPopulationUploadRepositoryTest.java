package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepositoryTest {
    private static final Instant NOW =
            Instant.parse("2026-07-27T06:00:00Z");

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> databaseTime =
            new AtomicReference<>(NOW);

    private EmbeddedDatabase database;
    private DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
            repository;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
            fixture;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        repository =
                new DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
                        jdbc,
                        mapper,
                        new AuthoritativeOutcomeSelectedPopulationUploadPolicy(
                                2,
                                16 * 1024 * 1024,
                                32 * 1024 * 1024,
                                Duration.ofHours(1),
                                Duration.ofMinutes(2),
                                Duration.ofDays(1)),
                        new DataSourceTransactionManager(database),
                        databaseTime::get);
        repository.init();
        AuthoritativeOutcomeSelectedPopulationIntegrity integrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        fixture =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(integrity);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void stagesOutOfOrderChunksAndRecoversExactRetries() {
        AuthoritativeOutcomeSelectedPopulationUploadRequest request =
                uploadRequest("upload-1");

        AuthoritativeOutcomeSelectedPopulationUploadRepository.Admission
                created = repository.begin(request);
        AuthoritativeOutcomeSelectedPopulationUploadRepository.Admission
                replayed = repository.begin(request);
        AuthoritativeOutcomeSelectedPopulationUploadRepository.ChunkAdmission
                second = repository.stageChunk(
                        fixture.manifest().scope(),
                        "upload-1",
                        1,
                        fixture.chunks().get(1),
                        2_048);
        AuthoritativeOutcomeSelectedPopulationUploadRepository.ChunkAdmission
                secondReplay = repository.stageChunk(
                        fixture.manifest().scope(),
                        "upload-1",
                        1,
                        fixture.chunks().get(1),
                        2_048);
        AuthoritativeOutcomeSelectedPopulationUploadRepository.ChunkAdmission
                first = repository.stageChunk(
                        fixture.manifest().scope(),
                        "upload-1",
                        0,
                        fixture.chunks().getFirst(),
                        1_024);

        assertThat(created.idempotentReplay()).isFalse();
        assertThat(replayed.idempotentReplay()).isTrue();
        assertThat(second.idempotentReplay()).isFalse();
        assertThat(second.status().receivedChunkCount()).isOne();
        assertThat(second.status().nextMissingChunkIndex()).isZero();
        assertThat(secondReplay.idempotentReplay()).isTrue();
        assertThat(first.status().complete()).isTrue();
        assertThat(first.status().receivedChunkCount()).isEqualTo(2);
        assertThat(first.status().receivedBytes()).isEqualTo(3_072);

        assertThatThrownBy(() -> repository.stageChunk(
                fixture.manifest().scope(),
                "upload-1",
                0,
                fixture.chunks().get(1),
                2_048))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.CHUNK_INVALID);
    }

    @Test
    void fencesIncompleteAndStaleFinalizersThenPersistsTerminalReplay() {
        repository.begin(uploadRequest("upload-finalize"));
        repository.stageChunk(
                fixture.manifest().scope(),
                "upload-finalize",
                0,
                fixture.chunks().getFirst(),
                1_024);

        assertThatThrownBy(() -> repository.claimFinalize(
                fixture.manifest().scope(),
                "upload-finalize",
                "worker-a"))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.UPLOAD_INCOMPLETE);

        repository.stageChunk(
                fixture.manifest().scope(),
                "upload-finalize",
                1,
                fixture.chunks().get(1),
                2_048);
        AuthoritativeOutcomeSelectedPopulationUploadRepository.FinalizationClaim
                first = repository.claimFinalize(
                fixture.manifest().scope(),
                "upload-finalize",
                "worker-a");

        assertThat(first.requiresExecution()).isTrue();
        assertThat(first.epoch()).isOne();
        assertThatThrownBy(() -> repository.claimFinalize(
                fixture.manifest().scope(),
                "upload-finalize",
                "worker-b"))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.FINALIZATION_BUSY);

        databaseTime.set(first.leaseUntil());
        assertThatThrownBy(() -> repository.completeFinalize(
                first, admission()))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.FINALIZATION_FENCED);

        AuthoritativeOutcomeSelectedPopulationUploadRepository.FinalizationClaim
                takeover = repository.claimFinalize(
                fixture.manifest().scope(),
                "upload-finalize",
                "worker-b");
        assertThat(takeover.epoch()).isEqualTo(2);

        assertThatThrownBy(() -> repository.completeFinalize(
                first, admission()))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.FINALIZATION_FENCED);

        AuthoritativeOutcomeSelectedPopulationUploadRepository.Upload
                completed = repository.completeFinalize(
                takeover, admission());
        AuthoritativeOutcomeSelectedPopulationUploadRepository.FinalizationClaim
                terminalReplay = repository.claimFinalize(
                fixture.manifest().scope(),
                "upload-finalize",
                "worker-c");

        assertThat(completed.status().state())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.FINALIZED);
        assertThat(completed.admission()).contains(admission());
        assertThat(terminalReplay.requiresExecution()).isFalse();
        assertThat(terminalReplay.upload().admission())
                .contains(admission());
    }

    @Test
    void enforcesScopeQuotaAndExpiresAbandonedUploads() {
        repository.begin(uploadRequest(
                "upload-a", "population-a"));
        repository.begin(uploadRequest(
                "upload-b", "population-b"));

        assertThatThrownBy(() ->
                repository.begin(uploadRequest(
                        "upload-c", "population-c")))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Reason.ACTIVE_UPLOAD_QUOTA_EXCEEDED);

        databaseTime.set(NOW.plus(Duration.ofHours(2)));
        assertThat(repository.expireAndPurge(10))
                .isEqualTo(2);
        assertThat(repository.find(
                fixture.manifest().scope(),
                "upload-a"))
                .get()
                .extracting(value -> value.status().state())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.EXPIRED);
        assertThat(repository.begin(uploadRequest(
                "upload-c", "population-c"))
                .status().state())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.OPEN);
    }

    @Test
    void statusReadLazilyExpiresAndDestroysAbandonedPayload() {
        repository.begin(uploadRequest("upload-lazy-expiry"));
        repository.stageChunk(
                fixture.manifest().scope(),
                "upload-lazy-expiry",
                0,
                fixture.chunks().getFirst(),
                1_024);
        databaseTime.set(
                NOW.plus(Duration.ofHours(2)));

        AuthoritativeOutcomeSelectedPopulationUploadRepository.Upload
                expired = repository.find(
                fixture.manifest().scope(),
                "upload-lazy-expiry").orElseThrow();

        assertThat(expired.status().state())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.EXPIRED);
        assertThat(expired.status().receivedChunkCount())
                .isZero();
        assertThat(expired.status().receivedBytes())
                .isZero();
    }

    @Test
    void purgesTerminalIntentAfterRetentionWindow() {
        repository.begin(uploadRequest("upload-retained-terminal"));
        repository.abort(
                fixture.manifest().scope(),
                "upload-retained-terminal");
        databaseTime.set(
                NOW.plus(Duration.ofDays(2)));

        assertThat(repository.expireAndPurge(10))
                .isZero();
        assertThat(repository.find(
                fixture.manifest().scope(),
                "upload-retained-terminal"))
                .isEmpty();
    }

    @Test
    void cleanupNeverExceedsItsSharedExpiryAndPurgeBudget() {
        repository.begin(uploadRequest(
                "upload-old-terminal",
                "population-old-terminal"));
        repository.abort(
                fixture.manifest().scope(),
                "upload-old-terminal");
        repository.begin(uploadRequest(
                "upload-expiring",
                "population-expiring"));
        databaseTime.set(
                NOW.plus(Duration.ofDays(2)));

        assertThat(repository.expireAndPurge(1))
                .isOne();
        assertThat(repository.find(
                fixture.manifest().scope(),
                "upload-old-terminal"))
                .isPresent();

        assertThat(repository.expireAndPurge(1))
                .isZero();
        assertThat(repository.find(
                fixture.manifest().scope(),
                "upload-old-terminal"))
                .isEmpty();
    }

    @Test
    void serializesConcurrentScopeAdmissionAgainstHardQuota()
            throws Exception {
        AuthoritativeOutcomeSelectedPopulationUploadPolicy
                singleActiveUpload =
                new AuthoritativeOutcomeSelectedPopulationUploadPolicy(
                        1,
                        16 * 1024 * 1024,
                        32 * 1024 * 1024,
                        Duration.ofHours(1),
                        Duration.ofMinutes(2),
                        Duration.ofDays(1));
        CyclicBarrier lockRowRace = new CyclicBarrier(2);
        Runnable beforeLockRowInsert = () -> {
            try {
                lockRowRace.await();
            } catch (Exception interrupted) {
                throw new IllegalStateException(
                        "scope-lock race failed", interrupted);
            }
        };
        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                first =
                concurrentRepository(
                        singleActiveUpload,
                        beforeLockRowInsert);
        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                second =
                concurrentRepository(
                        singleActiveUpload,
                        beforeLockRowInsert);
        first.init();
        second.init();

        try (ExecutorService workers =
                     Executors.newFixedThreadPool(2)) {
            List<Future<Object>> outcomes =
                    workers.invokeAll(List.of(
                            admission(
                                    first,
                                    uploadRequest(
                                            "upload-concurrent-a",
                                            "population-concurrent-a")),
                            admission(
                                    second,
                                    uploadRequest(
                                            "upload-concurrent-b",
                                            "population-concurrent-b"))));

            List<Object> values = outcomes.stream()
                    .map(DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepositoryTest
                            ::completed)
                    .toList();
            assertThat(values)
                    .filteredOn(
                            AuthoritativeOutcomeSelectedPopulationUploadRepository
                                    .Admission.class::isInstance)
                    .hasSize(1);
            assertThat(values)
                    .filteredOn(
                            AuthoritativeOutcomeSelectedPopulationUploadRepository
                                    .Violation.class::isInstance)
                    .singleElement()
                    .extracting(value ->
                            ((AuthoritativeOutcomeSelectedPopulationUploadRepository
                                    .Violation) value).reason())
                    .isEqualTo(
                            AuthoritativeOutcomeSelectedPopulationUploadRepository
                                    .Reason.ACTIVE_UPLOAD_QUOTA_EXCEEDED);
        }
    }

    private DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
    concurrentRepository(
            AuthoritativeOutcomeSelectedPopulationUploadPolicy policy,
            Runnable beforeLockRowInsert) {
        return new
                DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
                new JdbcTemplate(database),
                mapper,
                policy,
                new DataSourceTransactionManager(database),
                databaseTime::get,
                beforeLockRowInsert);
    }

    private static Callable<Object> admission(
            DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                    target,
            AuthoritativeOutcomeSelectedPopulationUploadRequest request) {
        return () -> {
            try {
                return target.begin(request);
            } catch (AuthoritativeOutcomeSelectedPopulationUploadRepository
                     .Violation rejected) {
                return rejected;
            }
        };
    }

    private static Object completed(
            Future<Object> outcome) {
        try {
            return outcome.get();
        } catch (Exception failed) {
            throw new AssertionError(
                    "concurrent upload admission failed", failed);
        }
    }

    private AuthoritativeOutcomeSelectedPopulationUploadRequest
    uploadRequest(String uploadId) {
        return uploadRequest(
                uploadId,
                fixture.manifest().populationId());
    }

    private AuthoritativeOutcomeSelectedPopulationUploadRequest
    uploadRequest(
            String uploadId,
            String populationId) {
        AuthoritativeOutcomeSelectedPopulationManifest source =
                fixture.manifest();
        AuthoritativeOutcomeSelectedPopulationManifest manifest =
                new AuthoritativeOutcomeSelectedPopulationManifest(
                        "",
                        populationId,
                        source.revision(),
                        "",
                        source.scope(),
                        source.inventoryRef(),
                        source.cohortRef(),
                        source.samplingFrameRef(),
                        source.selectionPolicyRef(),
                        source.selectionAuthoritySetRef(),
                        source.selectionAttestationRef(),
                        source.selectedAt(),
                        source.strata(),
                        source.chunks(),
                        source.totalEligiblePopulation(),
                        source.totalSelectedPopulation(),
                        source.attestedAt(),
                        null);
        return new AuthoritativeOutcomeSelectedPopulationUploadRequest(
                "",
                uploadId,
                "",
                manifest);
    }

    private AuthoritativeOutcomeSelectedPopulationAdmission admission() {
        return new AuthoritativeOutcomeSelectedPopulationAdmission(
                "",
                new AuthoritativeOutcomeSelectedPopulationBundle(
                        "",
                        fixture.manifest(),
                        fixture.chunks(),
                        ""),
                false);
    }
}
