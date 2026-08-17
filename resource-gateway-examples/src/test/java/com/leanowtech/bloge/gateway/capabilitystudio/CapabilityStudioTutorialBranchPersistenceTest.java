package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioTutorialBranchPersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(mapper);
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:capability-studio-persistence-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository().init();
    }

    @Test
    void seedsOnlyWhenTheHeadDoesNotExist() {
        CapabilityStudioTutorialBranchAuthority first = authority();
        CapabilityStudioTutorialBranchAuthority second = authority();

        assertThat(first.current()).isEqualTo(second.current());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_studio_tutorial_branch_heads", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_studio_tutorial_branch_revisions", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aNewAuthorityReconstructsTheExistingHeadAndImmutableHistory() {
        CapabilityStudioTutorialBranchAuthority first = authority();
        CapabilityStudioTutorialBranchAuthority.State revisionOne = first.current();
        CapabilityStudioTutorialBranchAuthority.State revisionTwo = first.save(request(
                "首个持久化变更", 1000L, 1L));
        CapabilityStudioTutorialBranchAuthority.State revisionThree = first.save(request(
                "第二个持久化变更", 1200L, 2L));

        CapabilityStudioTutorialBranchAuthority recreated = authority();
        assertThat(recreated.current()).isEqualTo(revisionThree);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_studio_tutorial_branch_revisions", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT condition_text FROM capability_studio_tutorial_branch_revisions "
                        + "WHERE branch_id = ? AND revision = 1", String.class,
                CapabilityStudioTutorialBranchAuthority.BRANCH_ID))
                .isEqualTo(revisionOne.behavior().condition());
        assertThat(jdbc.queryForObject(
                "SELECT condition_text FROM capability_studio_tutorial_branch_revisions "
                        + "WHERE branch_id = ? AND revision = 2", String.class,
                CapabilityStudioTutorialBranchAuthority.BRANCH_ID))
                .isEqualTo(revisionTwo.behavior().condition());
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM capability_studio_tutorial_branch_heads WHERE branch_id = ?",
                Long.class, CapabilityStudioTutorialBranchAuthority.BRANCH_ID))
                .isEqualTo(3L);
    }

    @Test
    void staleRetryOfAlreadyCommittedContentIsIdempotent() {
        CapabilityStudioTutorialBranchAuthority first = authority();
        CapabilityStudioTutorialBranchAuthority.State committed = first.save(request(
                "客户端没有收到响应", 1100L, 1L));

        CapabilityStudioTutorialBranchAuthority recreated = authority();
        CapabilityStudioTutorialBranchAuthority.State retried = recreated.save(request(
                "客户端没有收到响应", 1100L, 1L));

        assertThat(retried).isEqualTo(committed);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_studio_tutorial_branch_revisions", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void concurrentSameExpectedRevisionAllowsOnlyOneDifferentContent() throws Exception {
        CapabilityStudioTutorialBranchAuthority first = authority();
        CapabilityStudioTutorialBranchAuthority second = authority();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Outcome>> calls = List.of(
                    () -> runAfter(ready, go, () -> first.save(request("并发 A", 1001L, 1L))),
                    () -> runAfter(ready, go, () -> second.save(request("并发 B", 1002L, 1L))));
            List<Future<Outcome>> futures = new ArrayList<>();
            calls.forEach(call -> futures.add(executor.submit(call)));
            ready.await();
            go.countDown();
            List<Outcome> outcomes = futures.stream().map(this::get).toList();

            assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.failure()
                    instanceof CapabilityStudioTutorialBranchException)
                    .hasSize(1);
            assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst()
                    .orElseThrow().failure())
                    .isInstanceOf(CapabilityStudioTutorialBranchException.class)
                    .extracting("code")
                    .isEqualTo("RG.CAPABILITY_STUDIO.REVISION_CONFLICT");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM capability_studio_tutorial_branch_revisions", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT revision FROM capability_studio_tutorial_branch_heads WHERE branch_id = ?",
                    Long.class, CapabilityStudioTutorialBranchAuthority.BRANCH_ID))
                    .isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void baselineDriftFailsClosedWithoutReseedingOrOverwriting() {
        authority();
        CapabilityStudioGoldenDemoPack.ExactRef driftedBaselineRef =
                new CapabilityStudioGoldenDemoPack.ExactRef(
                        pack.canonicalBaseline().ref().kind(),
                        pack.canonicalBaseline().ref().id(),
                        pack.canonicalBaseline().ref().revision(),
                        "sha256:" + "f".repeat(64));
        CapabilityStudioGoldenDemoPack drifted = new CapabilityStudioGoldenDemoPack(
                pack.schemaVersion(), pack.packId(), pack.revision(), pack.packFingerprint(),
                pack.displayName(), pack.owner(), pack.readiness(),
                new CapabilityStudioGoldenDemoPack.CanonicalBaseline(
                        pack.canonicalBaseline().id(),
                        driftedBaselineRef,
                        pack.canonicalBaseline().immutable(),
                        pack.canonicalBaseline().assetRefs(),
                        pack.canonicalBaseline().scenarioRefs()),
                pack.apiCapabilities(), pack.featureCapabilities(), pack.toolCapabilities(),
                pack.supportingRefs(), pack.scenarios(),
                new CapabilityStudioGoldenDemoPack.TutorialBranch(
                        pack.tutorialBranch().id(), pack.tutorialBranch().ref(), driftedBaselineRef,
                        pack.tutorialBranch().behaviorOverrides()));

        assertThatThrownBy(() -> new CapabilityStudioTutorialBranchAuthority(
                repository(), drifted, mapper, transactions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical baseline drift");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM capability_studio_tutorial_branch_revisions", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameRevisionCannotBeInsertedWithDifferentContent() {
        authority();
        CapabilityStudioTutorialBranchRepository repository = repository();
        CapabilityStudioTutorialBranchRepository.StoredBranch different =
                new CapabilityStudioTutorialBranchRepository.StoredBranch(
                        CapabilityStudioTutorialBranchAuthority.BRANCH_ID, 1,
                        "sha256:" + "0".repeat(64),
                        pack.canonicalBaseline().ref().fingerprint(),
                        CapabilityStudioTutorialBranchAuthority.DEPENDENCY_ID,
                        "不同内容", CapabilityStudioTutorialBranchAuthority.BEHAVIOR_TIMEOUT, 700);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                repository.insertRevision(different)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CapabilityStudioTutorialBranchRepository repository() {
        return new CapabilityStudioTutorialBranchRepository(jdbc);
    }

    private CapabilityStudioTutorialBranchAuthority authority() {
        return new CapabilityStudioTutorialBranchAuthority(
                repository(), pack, mapper, transactions);
    }

    private CapabilityStudioTutorialBranchBehaviorUpdateRequest request(
            String condition, long duration, long expectedRevision) {
        return new CapabilityStudioTutorialBranchBehaviorUpdateRequest(
                condition, CapabilityStudioTutorialBranchAuthority.BEHAVIOR_TIMEOUT,
                duration, expectedRevision);
    }

    private Outcome runAfter(CountDownLatch ready, CountDownLatch go, Callable<?> call)
            throws Exception {
        ready.countDown();
        go.await();
        try {
            return Outcome.success(call.call());
        } catch (Throwable failure) {
            return Outcome.failure(failure);
        }
    }

    private Outcome get(Future<Outcome> future) {
        try {
            return future.get();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private record Outcome(boolean succeeded, Object value, Throwable failure) {
        static Outcome success(Object value) {
            return new Outcome(true, value, null);
        }

        static Outcome failure(Throwable failure) {
            return new Outcome(false, null, failure);
        }
    }
}
