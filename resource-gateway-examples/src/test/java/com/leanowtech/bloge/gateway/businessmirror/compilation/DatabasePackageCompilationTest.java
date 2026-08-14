package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DatabaseDomainCapabilityPackageDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DatabaseDomainCapabilityPackageSaveReceiptRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageAuthoringService;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageSaveCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationReceiptRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabasePackageCompilationTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T05:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DomainCapabilityPackageAuthoringService authoring;
    private PackageCompilationService compilation;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        authoring = authoring(dataSource);
        compilation = compilation(dataSource);
    }

    @Test
    void atomicallyPersistsBlockedFactsAndExactlyReplaysAcrossRestart() {
        create("cancellation-fee", "package:create:compile");

        PackageCompilationCoordinator.Outcome first = transactions.execute(status -> compilation.compile(
                "cancellation-fee", 1, "package:compile:response-loss", identity()));
        PackageCompilationService restarted = compilation(dataSource);
        PackageCompilationCoordinator.Outcome replay = transactions.execute(status -> restarted.compile(
                "cancellation-fee", 1, "package:compile:response-loss", identity()));

        assertThat(first).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(first.receipt().readiness().status().name()).isEqualTo("BLOCKED");
        assertThat(first.receipt().snapshot()).isNull();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(restarted.find("cancellation-fee", 1, identity())).isEqualTo(first.receipt());
        assertThat(count("business_mirror_package_compilations")).isEqualTo(1);
        assertThat(count("business_mirror_package_readiness_reports")).isEqualTo(1);
        assertThat(count("business_mirror_package_asset_link_closures")).isEqualTo(1);
        assertThat(count("business_mirror_package_snapshots")).isZero();
    }

    @Test
    void rejectsKeyDriftWithoutPublishingAnotherCompilation() {
        create("key-drift", "package:create:key-drift");
        transactions.execute(status -> compilation.compile(
                "key-drift", 1, "package:compile:key-drift", identity()));
        transactions.execute(status -> authoring.save("key-drift", 1,
                draft("key-drift", 1, "v2"), "package:save:key-drift", identity()));

        assertThatThrownBy(() -> transactions.execute(status -> compilation.compile(
                "key-drift", 2, "package:compile:key-drift", identity())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo(
                                "RG.BUSINESS_MIRROR.COMPILATION_IDEMPOTENCY_CONFLICT"));
        assertThat(count("business_mirror_package_compilations")).isEqualTo(1);
    }

    @Test
    void allocatesMonotonicRevisionsAcrossConcurrentKeysAndReplicas() throws Exception {
        create("concurrent-compilation", "package:create:concurrent-compilation");
        PackageCompilationService first = compilation(dataSource);
        PackageCompilationService second = compilation(dataSource);
        TransactionTemplate leftTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate rightTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<PackageCompilationCoordinator.Outcome> left = CompletableFuture.supplyAsync(
                () -> compileAfter(start, leftTx, first, "package:compile:concurrent:left"));
        CompletableFuture<PackageCompilationCoordinator.Outcome> right = CompletableFuture.supplyAsync(
                () -> compileAfter(start, rightTx, second, "package:compile:concurrent:right"));
        start.countDown();

        List<PackageCompilationCoordinator.Outcome> outcomes = List.of(
                left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        assertThat(outcomes).extracting(value -> value.receipt().compilationRevision())
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(outcomes).extracting(PackageCompilationCoordinator.Outcome::replayed)
                .containsOnly(false);
        assertThat(count("business_mirror_package_compilations")).isEqualTo(2);
    }

    @Test
    void rollsBackRevisionFactsAndReceiptWhenOuterTransactionFails() {
        create("compile-rollback", "package:create:compile-rollback");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            compilation.compile("compile-rollback", 1,
                    "package:compile:rollback", identity());
            throw new IllegalStateException("response failed before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("business_mirror_package_compilations")).isZero();
        assertThat(count("business_mirror_package_readiness_reports")).isZero();
        assertThat(count("business_mirror_package_compile_receipts")).isZero();
        PackageCompilationCoordinator.Outcome retry = transactions.execute(status -> compilation.compile(
                "compile-rollback", 1, "package:compile:rollback", identity()));
        assertThat(retry).isNotNull();
        assertThat(retry.receipt().compilationRevision()).isEqualTo(1);
    }

    @Test
    void failsClosedWhenAppendOnlyFactFingerprintColumnDrifts() {
        create("fact-integrity", "package:create:fact-integrity");
        transactions.execute(status -> compilation.compile(
                "fact-integrity", 1, "package:compile:fact-integrity", identity()));
        jdbc.update("""
                UPDATE business_mirror_package_readiness_reports SET fact_fingerprint = ?
                WHERE package_id = ? AND compilation_revision = ?
                """, fingerprint('f'), "fact-integrity", 1);

        assertThatThrownBy(() -> compilation.find("fact-integrity", 1, identity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Package compilation fact index drifted");
    }

    @Test
    void appendsAndRehydratesAReadySnapshotWithItsReferencedFacts() {
        DatabasePackageCompilationFactRepository facts =
                new DatabasePackageCompilationFactRepository(jdbc, mapper);
        facts.init();
        Instant completedAt = CLOCK.instant();
        String packageId = "ready-package";
        String sourceFingerprint = fingerprint('1');
        PackageReadinessReport readiness = new PackageReadinessReport("", packageId + "-readiness",
                1, "", SCOPE, packageId, 4, sourceFingerprint, null, List.of(), completedAt)
                .seal(mapper);
        BusinessAssetLinkClosure closure = new BusinessAssetLinkClosure("", packageId + "-links",
                1, "", SCOPE, packageId, List.of(), List.of(), completedAt).seal(mapper);
        DomainCapabilityPackageDraft.BusinessDefinition definition =
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", ref("PROBLEM_TAXONOMY", "cancellation", '2'),
                        "TRIP.CANCELLATION.FEE", "resolve disputed fee",
                        "correct resolution", DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of("service-quality"));
        DomainCapabilityPackageSnapshot snapshot = new DomainCapabilityPackageSnapshot("",
                packageId, 1, "", SCOPE, 4, sourceFingerprint, definition,
                ref("CONTRACT", "package-contract", '3'),
                ref("CAPABILITY_CLOSURE", "capability-closure", '4'),
                List.of(ref("MIRROR_PLAN", "mirror-plan", '5')), closure.artifactRef(),
                readiness.artifactRef(), List.of(ref("CAPABILITY", "trip-query", '6')),
                List.of(), PackageCompiler.COMPILER_VERSION,
                ref("PACKAGE_COMPILATION_POLICY", "default", '7'), provenance(), completedAt)
                .seal(mapper);
        PackageCompilationReceipt receipt = new PackageCompilationReceipt("", fingerprint('8'),
                packageId, 4, sourceFingerprint, 1, readiness, closure, snapshot,
                "authority-generation-ready", completedAt);

        PackageCompilationReceipt stored = transactions.execute(status -> {
            assertThat(facts.reserveRevision(SCOPE, packageId)).isEqualTo(1);
            facts.append(SCOPE, receipt);
            return facts.find(SCOPE, packageId, 1).orElseThrow();
        });

        assertThat(stored).isEqualTo(receipt);
        assertThat(stored.snapshot()).isNotNull();
        assertThat(count("business_mirror_package_snapshots")).isEqualTo(1);
    }

    private PackageCompilationCoordinator.Outcome compileAfter(
            CountDownLatch start,
            TransactionTemplate transaction,
            PackageCompilationService target,
            String key) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return transaction.execute(status -> target.compile(
                "concurrent-compilation", 1, key, identity()));
    }

    private void create(String packageId, String key) {
        transactions.execute(status -> authoring.create(
                draft(packageId, 0, "v1"), key, identity()));
    }

    private DomainCapabilityPackageAuthoringService authoring(DataSource source) {
        JdbcTemplate local = new JdbcTemplate(source);
        var drafts = new DatabaseDomainCapabilityPackageDraftRepository(local, mapper);
        drafts.init();
        var receipts = new DatabaseDomainCapabilityPackageSaveReceiptRepository(local, mapper);
        receipts.init();
        return new DomainCapabilityPackageAuthoringService(drafts,
                new DomainCapabilityPackageSaveCoordinator(receipts, mapper), mapper);
    }

    private PackageCompilationService compilation(DataSource source) {
        JdbcTemplate local = new JdbcTemplate(source);
        var drafts = new DatabaseDomainCapabilityPackageDraftRepository(local, mapper);
        drafts.init();
        var facts = new DatabasePackageCompilationFactRepository(local, mapper);
        facts.init();
        var receipts = new DatabasePackageCompilationReceiptRepository(local, mapper);
        receipts.init();
        var compiler = new PackageCompiler(mapper, new UnavailablePackageCompilationAuthority());
        var coordinator = new PackageCompilationCoordinator(
                receipts, facts, compiler, mapper, CLOCK);
        return new PackageCompilationService(drafts, facts, coordinator);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static DomainCapabilityPackageDraft draft(
            String packageId, long revision, String assumption) {
        return new DomainCapabilityPackageDraft("", packageId, revision, SCOPE,
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", null, "", "", "",
                        DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of()),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(),
                List.of(assumption), null, provenance(), DomainCapabilityPackageDraft.Lifecycle.DRAFT);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "alice", "",
                "BUSINESS_MIRROR_AUTHORING", "compile-correlation", Set.of("business-mirror-authors"),
                "CONFIDENTIAL", "");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "business-mirror-compilation-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static MirrorArtifactRef ref(String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(fingerprint));
    }
}
