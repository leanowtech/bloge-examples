package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceIndex;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceProjector;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageEvidenceRepository;
import com.leanowtech.bloge.gateway.businessmirror.persistence.DatabasePackageGovernanceProjectionRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageGovernanceIntegrationServiceTest {
    private ObjectMapper mapper;
    private PackageCompilationReceipt receipt;
    private PackageCompilationFactRepository facts;
    private PackageEvidenceRepository evidence;
    private PackageGovernanceProjectionRepository projections;
    private TransactionTemplate transactions;
    private RecordingOutbox outbox;
    private PackageGovernanceIntegrationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:package-governance-service-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);

        DatabasePackageCompilationFactRepository factStore =
                new DatabasePackageCompilationFactRepository(jdbc, mapper);
        factStore.init();
        DatabasePackageEvidenceRepository evidenceStore =
                new DatabasePackageEvidenceRepository(jdbc, mapper);
        evidenceStore.init();
        DomainCapabilityPackageGovernanceProjectionIntegrity projectionIntegrity =
                new DomainCapabilityPackageGovernanceProjectionIntegrity(mapper);
        DatabasePackageGovernanceProjectionRepository projectionStore =
                new DatabasePackageGovernanceProjectionRepository(jdbc, mapper,
                        projectionIntegrity, transactionManager);
        projectionStore.init();

        receipt = PackageGovernanceProtocolFixtures.receipt();
        PackageEvidenceIndex index = PackageGovernanceProtocolFixtures.evidenceIndex();
        transactions.executeWithoutResult(status -> {
            factStore.append(receipt.snapshot().scope(), receipt);
            evidenceStore.append(index, "/business-mirror/?task=evidence");
        });
        evidence = evidenceStore;
        facts = factStore;
        projections = projectionStore;
        outbox = new RecordingOutbox();
        service = service(PackageGovernanceProtocolFixtures.trust(
                PackageGovernanceProtocolFixtures.signer()),
                Clock.fixed(PackageGovernanceProtocolFixtures.GOVERNED_AT.plusSeconds(1),
                        ZoneOffset.UTC));
    }

    @Test
    void exportsExactBundleIngestsOnceAndReportsCurrentExternalGovernance() {
        PackageRegistryIngestBundle exported = service.exportBundle(
                receipt.packageId(), receipt.compilationRevision(), context("CHANGE_SYNC"));
        DomainCapabilityPackageGovernanceProjection projection =
                PackageGovernanceProtocolFixtures.projection(
                        PackageGovernanceProtocolFixtures.signer());

        assertThat(exported).isEqualTo(PackageGovernanceProtocolFixtures.bundle());
        PackageGovernanceProjectionReceipt first = service.ingest(
                receipt.packageId(), projection, context("GOVERNANCE_GATE_FEEDBACK"));
        PackageGovernanceProjectionReceipt replay = service.ingest(
                receipt.packageId(), projection, context("GOVERNANCE_GATE_FEEDBACK"));
        DomainCapabilityPackageGovernanceView view = service.view(
                receipt.packageId(), context("GOVERNANCE_EVIDENCE_INGESTION"));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(view.freshness())
                .isEqualTo(DomainCapabilityPackageGovernanceView.Freshness.CURRENT);
        assertThat(view.projection()).isEqualTo(projection);
        assertThat(outbox.events).singleElement().satisfies(event -> {
            assertThat(event.eventType())
                    .isEqualTo("DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_CHANGED");
            assertThat(event.aggregate().fingerprint())
                    .isEqualTo(projection.projectionFingerprint());
            assertThat(event.toString().toLowerCase())
                    .doesNotContain("credential", "password", "secretvalue");
        });
    }

    @Test
    void evidenceRefreshMakesPreviouslyAcceptedProjectionStale() {
        DomainCapabilityPackageGovernanceProjection projection =
                PackageGovernanceProtocolFixtures.projection(
                        PackageGovernanceProtocolFixtures.signer());
        service.ingest(receipt.packageId(), projection, context("GOVERNANCE_GATE_FEEDBACK"));
        PackageEvidenceIndex refreshed = PackageEvidenceProjector.project(
                receipt, Optional.empty(), Optional.empty(), 4,
                PackageGovernanceProtocolFixtures.PROJECTED_AT.plusSeconds(30), mapper);
        transactions.executeWithoutResult(status -> evidence.append(
                refreshed, "/business-mirror/?task=evidence"));

        DomainCapabilityPackageGovernanceView view = service.view(
                receipt.packageId(), context("GOVERNANCE_EVIDENCE_INGESTION"));
        assertThat(view.freshness())
                .isEqualTo(DomainCapabilityPackageGovernanceView.Freshness.STALE);
        assertThat(view.reasonCode()).isEqualTo("CURRENT_RESOURCE_GATEWAY_FACTS_DIFFER");
    }

    @Test
    void rejectsUntrustedExpiredAndCrossScopeProjectionWithoutLeakingOtherScope() {
        DomainCapabilityPackageGovernanceProjection projection =
                PackageGovernanceProtocolFixtures.projection(
                        PackageGovernanceProtocolFixtures.signer());
        PackageGovernanceIntegrationService untrusted = service(
                PackageGovernanceProjectionTrust.unavailable(),
                Clock.fixed(PackageGovernanceProtocolFixtures.GOVERNED_AT.plusSeconds(1),
                        ZoneOffset.UTC));
        assertProblem(() -> untrusted.ingest(receipt.packageId(), projection,
                        context("GOVERNANCE_GATE_FEEDBACK")),
                "RG.BUSINESS_MIRROR.GOVERNANCE_TRUST_UNAVAILABLE");

        PackageGovernanceIntegrationService expired = service(
                PackageGovernanceProtocolFixtures.trust(
                        PackageGovernanceProtocolFixtures.signer()),
                Clock.fixed(projection.expiresAt().plusSeconds(1), ZoneOffset.UTC));
        assertProblem(() -> expired.ingest(receipt.packageId(), projection,
                        context("GOVERNANCE_GATE_FEEDBACK")),
                "RG.GOVERNANCE.PROJECTION_STALE");

        IntegrationRequestContext otherScope = new IntegrationRequestContext(
                "tenant-b", "mobility", "customer-service", "staging", "sg",
                "SERVICE", "aneke-sync", "", "GOVERNANCE_GATE_FEEDBACK",
                "correlation-other", Set.of(), "CONFIDENTIAL", "");
        assertProblem(() -> service.ingest(receipt.packageId(), projection, otherScope),
                "RG.BUSINESS_MIRROR.GOVERNANCE_PACKAGE_NOT_FOUND");
    }

    private PackageGovernanceIntegrationService service(
            PackageGovernanceProjectionTrust trust,
            Clock clock) {
        return new PackageGovernanceIntegrationService(facts, evidence, projections,
                new PackageRegistryIngestBundleIntegrity(mapper),
                new DomainCapabilityPackageGovernanceProjectionIntegrity(mapper),
                trust, outbox, clock);
    }

    private static IntegrationRequestContext context(String purpose) {
        return new IntegrationRequestContext("tenant-a", "mobility", "customer-service",
                "staging", "sg", "SERVICE", "aneke-sync", "", purpose,
                "correlation-a", Set.of(), "CONFIDENTIAL", "");
    }

    private static void assertProblem(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> assertThat(failure.problem().code()).isEqualTo(code));
    }

    private static final class RecordingOutbox implements IntegrationChangeEventOutbox {
        private final List<IntegrationChangeEvent> events = new ArrayList<>();

        @Override
        public IntegrationChangeEvent append(IntegrationChangeEvent event) {
            IntegrationChangeEvent stored = event.withStreamSequence(events.size() + 1L);
            events.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationChangeEvent> read(
                long afterSequence, long throughSequence, String tenantId,
                String environmentId, int limit) {
            return List.copyOf(events);
        }

        @Override
        public boolean hasAfter(
                long afterSequence, long throughSequence, String tenantId,
                String environmentId) {
            return events.size() > afterSequence;
        }

        @Override
        public long highWaterSequence() {
            return events.size();
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
