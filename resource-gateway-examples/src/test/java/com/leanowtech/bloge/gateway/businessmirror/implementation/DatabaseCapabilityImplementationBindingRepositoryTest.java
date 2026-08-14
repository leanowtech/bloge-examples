package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityImplementationBindingRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private static final Instant AT = Instant.now().minusSeconds(1);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private DatabaseCapabilityImplementationBindingRepository repository;
    private DatabaseVisualEvidenceSigner signer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:implementation-binding-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseCapabilityImplementationBindingRepository(jdbc, mapper);
        repository.init();
        signer = new DatabaseVisualEvidenceSigner(jdbc);
    }

    @Test
    void createsAndExactlyReplaysAnImmutableSignedBinding() {
        StoredCapabilityImplementationBinding value = stored("binding-1", 'a');

        var created = repository.create(value);
        var replayed = repository.create(value);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.binding()).isEqualTo(value);
        replayed.binding().verify(mapper, signer);
    }

    @Test
    void rejectsBindingIdDriftAndIsolatesCompleteScope() {
        repository.create(stored("binding-1", 'a'));
        assertThatThrownBy(() -> repository.create(stored("binding-1", 'b')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different command material");
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                SCOPE.tenantId(), "other-org", SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region());
        assertThat(repository.find(other, "binding-1")).isEmpty();
    }

    @Test
    void bindingProtocolRejectsWritableOrOutOfRegionRuntime() {
        assertThatThrownBy(() -> binding("binding-unsafe", 'a', false, List.of("sg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe or incomplete");
        assertThatThrownBy(() -> binding("binding-region", 'a', true, List.of("us")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe or incomplete");
        assertThatThrownBy(() -> binding("binding-region-count", 'a', true,
                java.util.stream.IntStream.range(0, 129)
                        .mapToObj(index -> "r" + index).toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe or incomplete");
    }

    @Test
    void rejectsIndexedColumnDriftInsteadOfTrustingStoredJsonAlone() {
        StoredCapabilityImplementationBinding value = stored("binding-index-drift", 'a');
        repository.create(value);
        jdbc.update("UPDATE rg_bm_implementation_binding SET proposal_revision = 2 "
                + "WHERE binding_id = ?", value.binding().bindingId());

        assertThatThrownBy(() -> repository.find(SCOPE, value.binding().bindingId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index integrity check failed");
    }

    private StoredCapabilityImplementationBinding stored(String id, char request) {
        CapabilityImplementationBinding binding = binding(id, 'c', true, List.of("sg"));
        VisualRunEvidenceSeal seal = signer.seal(
                binding.fingerprint(), "implementation-binding-test:" + id);
        return new StoredCapabilityImplementationBinding("", fingerprint(request), binding, seal);
    }

    private CapabilityImplementationBinding binding(
            String id, char value, boolean readOnly, List<String> regions) {
        return new CapabilityImplementationBinding("", id, 1, "", SCOPE,
                ref("CAPABILITY_PROPOSAL_DRAFT", "proposal-1", '1'),
                ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation-1", '2'),
                ref("CAPABILITY", "refund-lookup", '3'), fingerprint('4'),
                "runtime:refund:v1", fingerprint('5'), "1.0.0", fingerprint(value),
                "trip-platform", regions, readOnly, true, AT.minusSeconds(1),
                AT.plusSeconds(3600), AT).seal(mapper);
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
