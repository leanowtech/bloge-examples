package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;

/** Opt-in production persistence; the default demo remains in-memory and isolated. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "resource-gateway.world-draft", name = "persistence", havingValue = "database")
@ConditionalOnBean(JdbcTemplate.class)
public class WorldDraftDatabaseConfiguration {
    @Bean
    @ConditionalOnMissingBean
    WorldDraftAuditSink worldDraftAuditSink(JdbcTemplate jdbc) {
        return new DatabaseWorldDraftAuditSink(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    WorldDraftPayloadProtector worldDraftPayloadProtector(
            @Value("${resource-gateway.world-draft.encryption.active-key-id:}") String activeKeyId,
            @Value("${resource-gateway.world-draft.encryption.key-ring:}") String keyRing) {
        return WorldDraftPayloadProtector.fromConfiguration(activeKeyId, keyRing);
    }

    @Bean
    @ConditionalOnMissingBean
    WorldDraftCandidateRepository worldDraftCandidateRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                                                  WorldDraftAuditSink audit) {
        return new DatabaseWorldDraftCandidateRepository(jdbc, mapper, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    WorldDraftRedactedPayloadVault worldDraftRedactedPayloadVault(JdbcTemplate jdbc, ObjectMapper mapper,
                                                                  WorldDraftAuditSink audit,
                                                                  WorldDraftPayloadProtector protector) {
        return new DatabaseWorldDraftRedactedPayloadVault(
                jdbc, mapper, Clock.systemUTC(), java.time.Duration.ofDays(30), audit, protector);
    }

    @Bean
    @ConditionalOnMissingBean
    WorldDraftRedactor worldDraftRedactor() {
        return WorldDraftRedactor.schemaGuided();
    }

    @Bean
    @ConditionalOnBean(WorldDraftSourceRepository.class)
    ReplayPayloadWorldDraftSourceAdapter replayPayloadWorldDraftSourceAdapter(
            List<WorldDraftSourceRepository> repositories) {
        return new ReplayPayloadWorldDraftSourceAdapter(required(repositories,
                WorldDraftSourceRef.Kind.REPLAY_PAYLOAD));
    }

    @Bean
    @ConditionalOnBean(WorldDraftSourceRepository.class)
    CapabilityCorpusTrajectoryWorldDraftSourceAdapter capabilityCorpusTrajectoryWorldDraftSourceAdapter(
            List<WorldDraftSourceRepository> repositories) {
        return new CapabilityCorpusTrajectoryWorldDraftSourceAdapter(required(repositories,
                WorldDraftSourceRef.Kind.CAPABILITY_CORPUS_TRAJECTORY));
    }

    @Bean
    @ConditionalOnBean(WorldDraftSourceRepository.class)
    GoldenCaptureWorldDraftSourceAdapter goldenCaptureWorldDraftSourceAdapter(
            List<WorldDraftSourceRepository> repositories) {
        return new GoldenCaptureWorldDraftSourceAdapter(required(repositories,
                WorldDraftSourceRef.Kind.GOLDEN_CAPTURE));
    }

    @Bean
    @ConditionalOnBean(WorldDraftSourceRepository.class)
    RunEvidenceWorldDraftSourceAdapter runEvidenceWorldDraftSourceAdapter(
            List<WorldDraftSourceRepository> repositories) {
        return new RunEvidenceWorldDraftSourceAdapter(required(repositories,
                WorldDraftSourceRef.Kind.RUN_EVIDENCE_PAYLOAD));
    }

    @Bean
    @ConditionalOnBean({ReplayPayloadWorldDraftSourceAdapter.class,
            CapabilityCorpusTrajectoryWorldDraftSourceAdapter.class,
            GoldenCaptureWorldDraftSourceAdapter.class,
            RunEvidenceWorldDraftSourceAdapter.class})
    GovernedWorldDraftSourceRouter governedWorldDraftSourceRouter(
            ReplayPayloadWorldDraftSourceAdapter replay,
            CapabilityCorpusTrajectoryWorldDraftSourceAdapter trajectory,
            GoldenCaptureWorldDraftSourceAdapter golden,
            RunEvidenceWorldDraftSourceAdapter evidence) {
        return new GovernedWorldDraftSourceRouter(List.of(replay, trajectory, golden, evidence));
    }

    @Bean
    ServerOwnedWorldDraftMaterializer serverOwnedWorldDraftMaterializer() {
        return new ServerOwnedWorldDraftMaterializer(
                ServerOwnedWorldDraftMaterializer.bloge(new WorldFragmentTestKit()));
    }

    @Bean
    @ConditionalOnBean(GovernedCatalogRepository.class)
    WorldDraftAssetRepository worldDraftAssetRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                                         GovernedCatalogRepository catalog,
                                                         WorldDraftAuditSink audit) {
        return new DatabaseWorldDraftAssetRepository(jdbc, mapper, catalog, audit);
    }

    @Bean
    WorldDraftAuthorityReceiptRepository worldDraftAuthorityReceiptRepository(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        return new DatabaseWorldDraftAuthorityReceiptRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean({WorldDraftAssetRepository.class, WorldDraftRedactedPayloadVault.class})
    WorldDraftPublishedBehaviorRuntime worldDraftPublishedBehaviorRuntime(
            WorldDraftAssetRepository assets, WorldDraftRedactedPayloadVault vault) {
        return new WorldDraftPublishedBehaviorRuntime(assets, vault, new WorldFragmentTestKit());
    }

    @Bean
    @ConditionalOnBean({WorldDraftCandidateRepository.class, WorldDraftAssetRepository.class,
            WorldDraftAuthorityReceiptRepository.class, WorldDraftPublicationAuthority.class,
            WorldDraftRedactedPayloadVault.class})
    WorldDraftPromotionTransaction worldDraftPromotionTransaction(
            DataSource dataSource,
            WorldDraftCandidateRepository candidates,
            WorldDraftAssetRepository assets,
            WorldDraftAuthorityReceiptRepository receipts,
            WorldDraftPublicationAuthority publication,
            WorldDraftRedactedPayloadVault vault) {
        return new DatabaseWorldDraftPromotionTransaction(dataSource, candidates, assets, receipts, publication, vault);
    }

    /** No local authority is installed here: approval must come from the deployment authority. */
    @Bean
    @ConditionalOnBean({GovernedWorldDraftSourceRouter.class, WorldDraftCandidateRepository.class,
            WorldDraftRedactor.class, WorldDraftRedactedPayloadVault.class,
            ServerOwnedWorldDraftMaterializer.class, WorldDraftApprovalAuthority.class,
            WorldDraftPublicationAuthority.class, WorldDraftAssetRepository.class,
            WorldDraftAuthorityReceiptRepository.class, WorldDraftPromotionTransaction.class,
            GovernedCatalogRepository.class})
    WorldDraftCandidateService worldDraftCandidateService(
            GovernedWorldDraftSourceRouter source,
            WorldDraftCandidateRepository candidates,
            WorldDraftRedactor redactor,
            WorldDraftRedactedPayloadVault vault,
            ServerOwnedWorldDraftMaterializer materializer,
            WorldDraftApprovalAuthority approval,
            WorldDraftPublicationAuthority publication,
            WorldDraftAssetRepository assets,
            WorldDraftAuthorityReceiptRepository receipts,
            WorldDraftPromotionTransaction promotion) {
        return new WorldDraftCandidateService(source, candidates, redactor, vault,
                materializer, approval, publication, assets, receipts, promotion, Clock.systemUTC());
    }

    private static WorldDraftSourceRepository required(List<WorldDraftSourceRepository> repositories,
                                                        WorldDraftSourceRef.Kind kind) {
        if (repositories == null) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        List<WorldDraftSourceRepository> matches = repositories.stream()
                .filter(repository -> repository != null && repository.kind() == kind).toList();
        if (matches.size() != 1) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
        return matches.get(0);
    }
}
