package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainFidelityServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private DomainFidelityProfileIntegrity integrity;
    private DatabaseDomainFidelityRepository repository;
    private DomainFidelityService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        integrity =
                DomainFidelityTestFixtures.integrity(mapper);
        repository =
                new DatabaseDomainFidelityRepository(
                        new JdbcTemplate(database),
                        mapper,
                        integrity);
        repository.init();
        service = new DomainFidelityService(
                repository,
                DomainFidelityTestFixtures.policy(),
                integrity,
                mapper,
                MirrorOperationObservability.noop(),
                DomainFidelityTestFixtures.CLOCK);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void mintsScopeOwnerTimeAndContentAddressAndRecoversExactRetry() {
        IntegrationRequestContext owner =
                DomainFidelityTestFixtures.ownerIdentity(
                        "support");
        DomainFidelityInventoryRegistrationRequest request =
                DomainFidelityTestFixtures.registration(
                        1,
                        "",
                        DomainFidelityTestFixtures.units());

        DomainFidelityInventory first =
                service.registerInventory(request, owner);
        DomainFidelityInventory retry =
                service.registerInventory(request, owner);

        assertThat(retry).isEqualTo(first);
        assertThat(first.scope())
                .isEqualTo(
                        DomainFidelityTestFixtures.scope(
                                "support"));
        assertThat(first.provenance().approvedBy())
                .isEqualTo(owner.actorId());
        assertThat(first.provenance().approvedAt())
                .isEqualTo(
                        DomainFidelityTestFixtures.NOW);
        assertThat(first.provenance().purpose())
                .isEqualTo(
                        DomainFidelityPolicy
                                .GOVERNANCE_PURPOSE);
        assertThat(first.fingerprint())
                .matches("sha256:[a-f0-9]{64}");
        first.verify(mapper);
    }

    @Test
    void signsProjectsAndReadsOnlyInsideTheAuthenticatedScope() {
        IntegrationRequestContext owner =
                DomainFidelityTestFixtures.ownerIdentity(
                        "support");
        DomainFidelityInventory inventory =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        owner);
        DomainFidelityProfile profile =
                service.projectVerified(
                        inventory.artifactRef(),
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support"));

        assertThat(profile.profileSeal().signed()).isTrue();
        assertThat(service.findProfile(
                profile.profileFingerprint(),
                DomainFidelityTestFixtures
                        .readerIdentity("support")))
                .isEqualTo(profile);
        assertThat(service.findLatestProfile(
                inventory.domainId(),
                DomainFidelityTestFixtures
                        .readerIdentity("support")))
                .isEqualTo(profile);
        assertThatThrownBy(() ->
                service.findProfile(
                        profile.profileFingerprint(),
                        DomainFidelityTestFixtures
                                .readerIdentity("other")))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.PROFILE_NOT_FOUND"));
    }

    @Test
    void projectsScenarioRunsInsideTheGovernedTransactionBoundary() {
        DomainFidelityInventory inventory =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        DomainFidelityTestFixtures
                                .ownerIdentity("support"));
        IntegrationRequestContext projector =
                DomainFidelityTestFixtures
                        .projectorIdentity("support");
        ScenarioRehearsalDomainFidelitySource source =
                mock(ScenarioRehearsalDomainFidelitySource.class);
        List<String> runs = List.of("scenario-run");
        when(source.measurements(
                inventory, runs, projector))
                .thenReturn(
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory));

        DomainFidelityProfile profile =
                service.projectScenario(
                        inventory.artifactRef(),
                        runs,
                        source,
                        projector);

        verify(source).measurements(
                inventory, runs, projector);
        assertThat(profile.profileSeal().signed()).isTrue();
        assertThat(repository.findProfile(
                inventory.scope(),
                profile.profileFingerprint()))
                .contains(profile);
    }

    @Test
    void projectsShadowComparisonsInsideTheGovernedTransactionBoundary() {
        DomainFidelityInventory inventory =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        DomainFidelityTestFixtures
                                .ownerIdentity("support"));
        IntegrationRequestContext projector =
                DomainFidelityTestFixtures
                        .projectorIdentity("support");
        ReadOnlyShadowDomainFidelitySource source =
                mock(
                        ReadOnlyShadowDomainFidelitySource.class);
        ReadOnlyShadowComparison comparison =
                mock(ReadOnlyShadowComparison.class);
        List<ReadOnlyShadowComparison> comparisons =
                List.of(comparison);
        when(source.measurements(
                inventory, comparisons, projector))
                .thenReturn(
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory));

        DomainFidelityProfile profile =
                service.projectShadow(
                        inventory.artifactRef(),
                        comparisons,
                        source,
                        projector);

        verify(source).measurements(
                inventory, comparisons, projector);
        assertThat(profile.profileSeal().signed()).isTrue();
        assertThat(repository.findProfile(
                inventory.scope(),
                profile.profileFingerprint()))
                .contains(profile);
    }

    @Test
    void rejectsUnauthorizedOwnerAndProjectorRoles() {
        IntegrationRequestContext unauthorizedOwner =
                new IntegrationRequestContext(
                        "tenant-a",
                        "support",
                        "refunds",
                        "staging",
                        "sg",
                        "HUMAN",
                        "owner-without-role",
                        "",
                        DomainFidelityPolicy
                                .GOVERNANCE_PURPOSE,
                        "correlation",
                        Set.of(),
                        "CONFIDENTIAL",
                        "");
        assertCode(
                () -> service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        unauthorizedOwner),
                "RG.MIRROR.FIDELITY.OWNER_FORBIDDEN");

        DomainFidelityInventory inventory =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        DomainFidelityTestFixtures
                                .ownerIdentity("support"));
        IntegrationRequestContext humanProjector =
                new IntegrationRequestContext(
                        "tenant-a",
                        "support",
                        "refunds",
                        "staging",
                        "sg",
                        "HUMAN",
                        "owner-a",
                        "",
                        DomainFidelityPolicy.PROJECTION_PURPOSE,
                        "correlation",
                        Set.of(
                                DomainFidelityPolicy
                                        .DEFAULT_PROJECTOR_GROUP),
                        "CONFIDENTIAL",
                        "");
        assertCode(
                () -> service.projectVerified(
                        inventory.artifactRef(),
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        humanProjector),
                "RG.MIRROR.FIDELITY.PROJECTOR_FORBIDDEN");
    }

    @Test
    void rejectsProjectionAgainstAnOldDenominatorHead() {
        IntegrationRequestContext owner =
                DomainFidelityTestFixtures.ownerIdentity(
                        "support");
        DomainFidelityInventory first =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        owner);
        service.registerInventory(
                DomainFidelityTestFixtures.registration(
                        2,
                        first.fingerprint(),
                        DomainFidelityTestFixtures.units()),
                owner);

        assertCode(
                () -> service.projectVerified(
                        first.artifactRef(),
                        DomainFidelityTestFixtures
                                .passingMeasurements(first),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")),
                "RG.MIRROR.FIDELITY.INVENTORY_NOT_CURRENT");
    }

    @Test
    void failsClosedWhenTheManagedSigningAuthorityIsUnavailable() {
        DomainFidelityInventory inventory =
                service.registerInventory(
                        DomainFidelityTestFixtures.registration(
                                1,
                                "",
                                DomainFidelityTestFixtures.units()),
                        DomainFidelityTestFixtures
                                .ownerIdentity("support"));
        VisualEvidenceSigner unavailable =
                new VisualEvidenceSigner() {
                    @Override
                    public VisualRunEvidenceSeal seal(
                            String materialFingerprint) {
                        throw new IllegalStateException(
                                "provider offline");
                    }

                    @Override
                    public Verification verify(
                            VisualRunEvidenceSeal seal,
                            String actualMaterialFingerprint) {
                        return Verification.unavailable(
                                "provider offline");
                    }

                    @Override
                    public Optional<VerificationKey> key(
                            String keyId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean available() {
                        return false;
                    }
                };
        DomainFidelityProfileIntegrity unavailableIntegrity =
                new DomainFidelityProfileIntegrity(
                        mapper,
                        unavailable,
                        DomainFidelityTestFixtures.CLOCK);
        DomainFidelityService unavailableService =
                new DomainFidelityService(
                        repository,
                        DomainFidelityTestFixtures.policy(),
                        unavailableIntegrity,
                        mapper,
                        MirrorOperationObservability.noop(),
                        DomainFidelityTestFixtures.CLOCK);

        assertCode(
                () -> unavailableService.projectVerified(
                        inventory.artifactRef(),
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        DomainFidelityTestFixtures
                                .projectorIdentity("support")),
                "RG.MIRROR.FIDELITY.UNAVAILABLE");
    }

    private static void assertCode(
            Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(code));
    }
}
