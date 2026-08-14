package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL/H2 immutable implementation-binding repository with complete enterprise Scope keys. */
public class DatabaseCapabilityImplementationBindingRepository
        implements CapabilityImplementationBindingRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rg_bm_implementation_binding (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                binding_id VARCHAR(512) NOT NULL,
                proposal_id VARCHAR(512) NOT NULL,
                proposal_revision BIGINT NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                stored_json TEXT NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region,
                             binding_id)
            )
            """;
    private static final String INSERT = """
            INSERT INTO rg_bm_implementation_binding (
                tenant_id, organization_id, project_id, environment_id, region,
                binding_id, proposal_id, proposal_revision, request_fingerprint,
                stored_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String SELECT = """
            SELECT proposal_id, proposal_revision, request_fingerprint, created_at, stored_json
            FROM rg_bm_implementation_binding
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND binding_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCapabilityImplementationBindingRepository(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public CreateResult create(StoredCapabilityImplementationBinding stored) {
        Objects.requireNonNull(stored, "binding");
        var binding = stored.binding();
        CapabilitySnapshot.Scope scope = binding.scope();
        int inserted = jdbc.update(INSERT, scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), binding.bindingId(),
                binding.proposalDraftRef().id(), binding.proposalDraftRef().revision(),
                stored.requestFingerprint(), serialize(stored), binding.createdAt().toString());
        StoredCapabilityImplementationBinding exact = find(scope, binding.bindingId())
                .orElseThrow(() -> new IllegalStateException(
                        "implementation binding disappeared after immutable create"));
        if (!exact.equals(stored)) {
            throw new IllegalArgumentException(
                    "implementation binding id is bound to different command material");
        }
        return new CreateResult(exact, inserted == 1);
    }

    @Override
    public Optional<StoredCapabilityImplementationBinding> find(
            CapabilitySnapshot.Scope scope, String bindingId) {
        CapabilitySnapshot.Scope exactScope = Objects.requireNonNull(scope, "scope");
        String id = required(bindingId, "bindingId");
        List<StoredCapabilityImplementationBinding> values = jdbc.query(SELECT,
                (rs, row) -> {
                    StoredCapabilityImplementationBinding stored =
                            deserialize(rs.getString("stored_json"));
                    if (!stored.binding().proposalDraftRef().id()
                            .equals(rs.getString("proposal_id"))
                            || stored.binding().proposalDraftRef().revision()
                            != rs.getLong("proposal_revision")
                            || !stored.requestFingerprint()
                            .equals(rs.getString("request_fingerprint"))
                            || !stored.binding().createdAt().toString()
                            .equals(rs.getString("created_at"))) {
                        throw new IllegalStateException(
                                "stored implementation binding index integrity check failed");
                    }
                    return stored;
                },
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environmentId(), exactScope.region(), id);
        return values.stream().peek(value -> {
            if (!value.binding().scope().equals(exactScope)
                    || !value.binding().bindingId().equals(id)) {
                throw new IllegalStateException(
                        "stored implementation binding scope integrity check failed");
            }
        }).findFirst();
    }

    private String serialize(StoredCapabilityImplementationBinding value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("implementation binding serialization failed", failure);
        }
    }

    private StoredCapabilityImplementationBinding deserialize(String value) {
        try {
            return mapper.readValue(value, StoredCapabilityImplementationBinding.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("stored implementation binding is unreadable", failure);
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
