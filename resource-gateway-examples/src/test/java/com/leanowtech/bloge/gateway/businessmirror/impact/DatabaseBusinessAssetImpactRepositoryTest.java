package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabaseBusinessAssetImpactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseBusinessAssetImpactRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DatabasePackageCompilationFactRepository facts;
    private DatabaseBusinessAssetImpactRepository impacts;

    @BeforeEach
    void setUp() {
        var dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        facts = new DatabasePackageCompilationFactRepository(jdbc, mapper);
        facts.init();
        impacts = new DatabaseBusinessAssetImpactRepository(jdbc, mapper);
        impacts.init();
    }

    @Test
    void advancesCurrentHeadDetectsStalenessAndIgnoresHistoricalRows() {
        PackageCompilationReceipt revisionOne = receipt(1, '1');
        append(revisionOne);
        BusinessAssetImpactRepository.ProjectionResult first = transactions.execute(
                status -> impacts.project(SCOPE, revisionOne));

        assertThat(first).isNotNull();
        assertThat(first.replayed()).isFalse();
        assertThat(first.sourceCount()).isEqualTo(5);
        assertThat(first.pathCount()).isEqualTo(10);
        assertThat(queryResource().items()).singleElement().satisfies(item -> {
            assertThat(item.compilationRevision()).isEqualTo(1);
            assertThat(item.matches()).singleElement().satisfies(match ->
                    assertThat(match.paths()).extracting(path -> path.impactedRef().id())
                            .containsExactly("trip-query", "refund-solution",
                                    "refund-workflow", "support-console"));
        });
        assertThat(queryResource().stalePackageIds()).isEmpty();

        PackageCompilationReceipt revisionTwo = receipt(2, '2');
        append(revisionTwo);
        assertThat(queryResource().items()).singleElement()
                .extracting(BusinessAssetImpactRepository.StoredPackageImpact::compilationRevision)
                .isEqualTo(1L);
        assertThat(queryResource().stalePackageIds()).containsExactly("refund-package");
        assertThat(impacts.staleSnapshots(SCOPE, "", 10))
                .containsExactly(new BusinessAssetImpactRepository.SnapshotCoordinate(
                        "refund-package", 2));

        transactions.executeWithoutResult(status -> impacts.project(SCOPE, revisionTwo));
        assertThat(queryResource().stalePackageIds()).isEmpty();
        assertThat(queryResource().items()).singleElement()
                .extracting(BusinessAssetImpactRepository.StoredPackageImpact::compilationRevision)
                .isEqualTo(2L);
        assertThat(count("business_mirror_asset_impact_projections")).isEqualTo(10);
    }

    @Test
    void exactlyReplaysAndFencesOlderOrDriftedCoordinates() {
        PackageCompilationReceipt first = receipt(1, '1');
        append(first);
        transactions.executeWithoutResult(status -> impacts.project(SCOPE, first));

        BusinessAssetImpactRepository.ProjectionResult replay = transactions.execute(
                status -> impacts.project(SCOPE, first));
        assertThat(replay).isNotNull();
        assertThat(replay.replayed()).isTrue();
        assertThat(count("business_mirror_asset_impact_projections")).isEqualTo(5);

        PackageCompilationReceipt second = receipt(2, '2');
        append(second);
        transactions.executeWithoutResult(status -> impacts.project(SCOPE, second));
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> impacts.project(SCOPE, first)))
                .isInstanceOf(DatabaseBusinessAssetImpactRepository.StaleProjectionException.class);

        PackageCompilationReceipt drifted = receipt(2, '3');
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> impacts.project(SCOPE, drifted)))
                .isInstanceOf(DatabaseBusinessAssetImpactRepository.ProjectionDriftException.class);
    }

    @Test
    void isolatesEveryQueryAndFreshnessDecisionByCompleteScope() {
        PackageCompilationReceipt receipt = receipt(1, '1');
        append(receipt);
        transactions.executeWithoutResult(status -> impacts.project(SCOPE, receipt));
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                SCOPE.tenantId(), "another-organization", SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region());
        BusinessAssetSelector selector = new BusinessAssetSelector(
                BusinessAssetRef.Kind.RESOURCE, "trip-api", "customer-registry");

        assertThat(impacts.query(other, selector, "", 10).items()).isEmpty();
        assertThat(impacts.query(other, selector, "", 10).stalePackageIds()).isEmpty();
        assertThat(impacts.staleSnapshots(other, "", 10)).isEmpty();
    }

    @Test
    void detectsStoredProjectionFingerprintDriftBeforeReturningImpact() {
        PackageCompilationReceipt receipt = receipt(1, '1');
        append(receipt);
        transactions.executeWithoutResult(status -> impacts.project(SCOPE, receipt));
        jdbc.update("""
                UPDATE business_mirror_asset_impact_projections
                SET paths_json = '[]'
                WHERE package_id = ? AND source_id = ?
                """, "refund-package", "trip-api");

        assertThatThrownBy(this::queryResource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored business asset impact projection drifted");
    }

    @Test
    void durablyAdmitsExactlyOnceAndFencesExpiredProjectionLeases() {
        PackageCompilationReceipt receipt = receipt(1, '1');
        append(receipt);

        boolean admitted = Boolean.TRUE.equals(transactions.execute(
                status -> impacts.enqueue(SCOPE, receipt)));
        boolean replayed = Boolean.TRUE.equals(transactions.execute(
                status -> impacts.enqueue(SCOPE, receipt)));
        assertThat(admitted).isTrue();
        assertThat(replayed).isFalse();
        BusinessAssetImpactRepository.ProjectionLease first = transactions.execute(
                status -> impacts.claim("worker-a", Duration.ofMinutes(5)).orElseThrow());
        assertThat(first).isNotNull();
        Optional<BusinessAssetImpactRepository.ProjectionLease> competing = transactions.execute(
                status -> impacts.claim("worker-b", Duration.ofMinutes(5)));
        assertThat(competing).isEmpty();

        BusinessAssetImpactRepository.ProjectionRelease released = transactions.execute(
                status -> impacts.release(first, "RG.IMPACT.RETRY", 3));
        assertThat(released).isNotNull();
        assertThat(released.status())
                .isEqualTo(BusinessAssetImpactRepository.ProjectionJobStatus.PENDING);
        jdbc.update("UPDATE business_mirror_asset_impact_outbox SET available_at = CURRENT_TIMESTAMP");
        BusinessAssetImpactRepository.ProjectionLease second = transactions.execute(
                status -> impacts.claim("worker-b", Duration.ofMinutes(5)).orElseThrow());
        assertThat(second).isNotNull();
        assertThat(second.leaseEpoch()).isGreaterThan(first.leaseEpoch());
        boolean staleCompletion = Boolean.TRUE.equals(
                transactions.execute(status -> impacts.complete(first)));
        assertThat(staleCompletion).isFalse();

        transactions.executeWithoutResult(status -> {
            impacts.project(SCOPE, receipt);
            assertThat(impacts.complete(second)).isTrue();
        });
        assertThat(jdbc.queryForObject(
                "SELECT status FROM business_mirror_asset_impact_outbox", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void quarantinesPoisonProjectionAfterTheBoundedAttemptBudget() {
        PackageCompilationReceipt receipt = receipt(1, '1');
        append(receipt);
        transactions.executeWithoutResult(status -> impacts.enqueue(SCOPE, receipt));
        BusinessAssetImpactRepository.ProjectionLease first = transactions.execute(
                status -> impacts.claim("worker-a", Duration.ofMinutes(5)).orElseThrow());
        transactions.executeWithoutResult(status -> impacts.release(
                first, "RG.IMPACT.FAILED", 2));
        jdbc.update("UPDATE business_mirror_asset_impact_outbox SET available_at = CURRENT_TIMESTAMP");
        BusinessAssetImpactRepository.ProjectionLease second = transactions.execute(
                status -> impacts.claim("worker-b", Duration.ofMinutes(5)).orElseThrow());

        BusinessAssetImpactRepository.ProjectionRelease released = transactions.execute(
                status -> impacts.release(second, "RG.IMPACT.FAILED", 2));

        assertThat(released).isNotNull();
        assertThat(released.status())
                .isEqualTo(BusinessAssetImpactRepository.ProjectionJobStatus.QUARANTINED);
        Optional<BusinessAssetImpactRepository.ProjectionLease> quarantined = transactions.execute(
                status -> impacts.claim("worker-c", Duration.ofMinutes(5)));
        assertThat(quarantined).isEmpty();
    }

    private BusinessAssetImpactRepository.ImpactQuery queryResource() {
        return impacts.query(SCOPE, new BusinessAssetSelector(
                BusinessAssetRef.Kind.RESOURCE, "trip-api", "customer-registry"), "", 10);
    }

    private void append(PackageCompilationReceipt receipt) {
        transactions.executeWithoutResult(status -> {
            assertThat(facts.reserveRevision(SCOPE, receipt.packageId()))
                    .isEqualTo(receipt.compilationRevision());
            facts.append(SCOPE, receipt);
        });
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private PackageCompilationReceipt receipt(long revision, char material) {
        String sourceFingerprint = fingerprint(material);
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", material);
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "trip-query", (char) (material + 1));
        BusinessAssetRef solution = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "refund-solution", (char) (material + 2));
        BusinessAssetRef workflow = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "refund-workflow", (char) (material + 3));
        BusinessAssetRef channel = asset(BusinessAssetRef.Layer.L3_APPLICATION,
                BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-console", (char) (material + 4));
        List<BusinessAssetRef> assets = List.of(resource, operator, solution, workflow, channel);
        List<BusinessAssetLink> links = List.of(
                link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS),
                link(operator, solution, BusinessAssetLink.Relation.USES),
                link(solution, workflow, BusinessAssetLink.Relation.DELIVERED_BY),
                link(workflow, channel, BusinessAssetLink.Relation.EXPOSED_ON));
        BusinessAssetLinkClosure closure = new BusinessAssetLinkClosure("",
                "refund-package-links", revision, "", SCOPE, "refund-package",
                assets, links, NOW.plusSeconds(revision)).seal(mapper);
        PackageReadinessReport readiness = new PackageReadinessReport("",
                "refund-package-readiness", revision, "", SCOPE, "refund-package",
                revision, sourceFingerprint, PackageReadinessReport.Status.READY,
                List.of(), NOW.plusSeconds(revision)).seal(mapper);
        DomainCapabilityPackageDraft.BusinessDefinition definition =
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", ref("PROBLEM_TAXONOMY", "trip-problems", 'a'),
                        "TRIP.REFUND", "Resolve refund requests", "Correct refund decision",
                        DomainCapabilityPackageDraft.RiskClass.HIGH, "refund-owner", List.of());
        DomainCapabilityPackageSnapshot snapshot = new DomainCapabilityPackageSnapshot("",
                "refund-package", revision, "", SCOPE, revision, sourceFingerprint,
                definition, ref("CONTRACT", "refund-contract", 'b'),
                ref("CAPABILITY_CLOSURE", "refund-capabilities", 'c'),
                List.of(ref("MIRROR_PLAN", "refund-plan", 'd')), closure.artifactRef(),
                readiness.artifactRef(), List.of(ref("CAPABILITY", "trip-query", 'e')),
                List.of(), "business-mirror-compiler-v1",
                ref("PACKAGE_COMPILATION_POLICY", "default", 'f'), provenance(),
                NOW.plusSeconds(revision)).seal(mapper);
        return new PackageCompilationReceipt("", fingerprint((char) (material + 5)),
                "refund-package", revision, sourceFingerprint, revision, readiness, closure,
                snapshot, "authority-generation-" + revision, NOW.plusSeconds(revision));
    }

    private static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char value) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(value),
                "customer-registry", SCOPE);
    }

    private static BusinessAssetLink link(
            BusinessAssetRef source,
            BusinessAssetRef target,
            BusinessAssetLink.Relation relation) {
        return new BusinessAssetLink("", source, target, relation, "",
                BusinessAssetLink.Risk.HIGH, "refund-owner", provenance());
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "business-asset-impact-test", null, null, null, null,
                List.of(), "refund-owner", NOW.minusSeconds(3600), NOW.plusSeconds(86_400), "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        char exact = Character.toLowerCase(value);
        if (exact < 'a' || exact > 'f') {
            exact = (char) ('a' + Math.floorMod(exact, 6));
        }
        return "sha256:" + String.valueOf(exact).repeat(64);
    }
}
