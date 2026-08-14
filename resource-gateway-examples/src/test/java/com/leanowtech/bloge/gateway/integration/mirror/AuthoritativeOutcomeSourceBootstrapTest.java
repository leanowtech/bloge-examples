package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceBootstrapTest {
    private EmbeddedDatabase database;
    private DatabaseAuthoritativeOutcomeSourceCheckpointRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        repository = new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                new JdbcTemplate(database),
                new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(database),
                () -> Instant.parse("2026-08-03T00:00:00Z"));
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void registersAndExactlyReplaysDeploymentBaseline() {
        var first = new AuthoritativeOutcomeSourceBootstrap(repository, source(
                AuthoritativeOutcomeSourceTestFixtures.liveRegistration()));
        var restarted = new AuthoritativeOutcomeSourceBootstrap(repository, source(
                AuthoritativeOutcomeSourceTestFixtures.liveRegistration()));

        first.initialize();
        restarted.initialize();

        assertThat(first.ready()).isTrue();
        assertThat(restarted.ready()).isTrue();
        assertThat(repository.find(AuthoritativeOutcomeSourceTestFixtures.liveKey()))
                .isPresent();
    }

    @Test
    void refusesBaselineRewindInsideAnExistingGeneration() {
        var first = new AuthoritativeOutcomeSourceBootstrap(repository, source(
                AuthoritativeOutcomeSourceTestFixtures.liveRegistration()));
        first.initialize();
        var conflicting = new AuthoritativeOutcomeSourceCheckpointRepository.Registration(
                AuthoritativeOutcomeSourceTestFixtures.liveKey(),
                AuthoritativeOutcomeSourceTestFixtures.fingerprint('f'),
                AuthoritativeOutcomeSourceTestFixtures.cursor("rewind", 'e'));

        assertThatThrownBy(() -> new AuthoritativeOutcomeSourceBootstrap(
                repository, source(conflicting)).initialize())
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository
                        .Reason.CONTENT_CONFLICT);
    }

    private static AuthoritativeOutcomeSource source(
            AuthoritativeOutcomeSourceCheckpointRepository.Registration registration) {
        return new AuthoritativeOutcomeSource() {
            @Override
            public AuthoritativeOutcomeSourceCheckpointRepository.Registration
            liveRegistration() {
                return registration;
            }

            @Override
            public FetchResult fetch(Position position) {
                return FetchResult.withoutPage(FetchStatus.NO_CHANGE, "NO_NEW_FACTS");
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION,
                        true, true, true, true, true, true);
            }
        };
    }
}
