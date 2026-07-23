package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * H2-backed append-only terminal quarantine-review repository.
 *
 * <p>The complete enterprise scope and observation id form the primary key. Duplicated command,
 * review, admission, disposition, reviewer, and time columns are compared with canonical JSON on
 * every read so index drift cannot change governance behavior.</p>
 */
public class DatabaseCapabilityObservationReviewRepository
        implements CapabilityObservationReviewRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_capability_observation_reviews (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                observation_id VARCHAR(512) NOT NULL,
                admission_fingerprint VARCHAR(71) NOT NULL,
                command_fingerprint VARCHAR(71) NOT NULL,
                review_fingerprint VARCHAR(71) NOT NULL,
                disposition VARCHAR(64) NOT NULL,
                reviewed_by VARCHAR(255) NOT NULL,
                reviewed_at VARCHAR(64) NOT NULL,
                review_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_capability_observation_reviews (
                tenant_id, organization_id, project_id, environment_id, region,
                observation_id, admission_fingerprint, command_fingerprint,
                review_fingerprint, disposition, reviewed_by, reviewed_at, review_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT admission_fingerprint, command_fingerprint, review_fingerprint,
                   disposition, reviewed_by, reviewed_at, review_json
            FROM mirror_capability_observation_reviews
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND observation_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityCorpusIntegrity integrity;

    /**
     * Creates the durable review repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity review content-addressing boundary
     */
    public DatabaseCapabilityObservationReviewRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CapabilityCorpusIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the append-only review table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public CapabilityObservationReview append(
            CapabilityObservationReview review) {
        CapabilityObservationReview exact = verify(review);
        CapabilitySnapshot.Scope scope = exact.scope();
        String observationId = exact.observationRef().id();
        Optional<CapabilityObservationReview> existing =
                find(scope, observationId);
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), exact);
        }
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    observationId,
                    exact.admissionRef().fingerprint(),
                    exact.sourceCommandFingerprint(),
                    exact.reviewFingerprint(),
                    exact.disposition().name(),
                    exact.reviewedBy(),
                    exact.reviewedAt().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            CapabilityObservationReview stored = find(scope, observationId)
                    .orElseThrow(() -> concurrent);
            return sameOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<CapabilityObservationReview> find(
            CapabilitySnapshot.Scope scope, String observationId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(observationId);
        List<CapabilityObservationReview> found = jdbc.query(
                SELECT_EXACT,
                (result, rowNumber) -> deserialize(
                        exactScope,
                        exactId,
                        result.getString("admission_fingerprint"),
                        result.getString("command_fingerprint"),
                        result.getString("review_fingerprint"),
                        result.getString("disposition"),
                        result.getString("reviewed_by"),
                        result.getString("reviewed_at"),
                        result.getString("review_json")),
                exactScope.tenantId(),
                exactScope.organizationId(),
                exactScope.projectId(),
                exactScope.environmentId(),
                exactScope.region(),
                exactId);
        return found.stream().findFirst();
    }

    private CapabilityObservationReview deserialize(
            CapabilitySnapshot.Scope expectedScope,
            String expectedObservationId,
            String expectedAdmissionFingerprint,
            String expectedCommandFingerprint,
            String expectedReviewFingerprint,
            String expectedDisposition,
            String expectedReviewer,
            String expectedReviewedAt,
            String json) {
        try {
            CapabilityObservationReview review = verify(
                    mapper.readValue(json, CapabilityObservationReview.class));
            if (!expectedScope.equals(review.scope())
                    || !expectedObservationId.equals(
                    review.observationRef().id())
                    || !expectedAdmissionFingerprint.equals(
                    review.admissionRef().fingerprint())
                    || !expectedCommandFingerprint.equals(
                    review.sourceCommandFingerprint())
                    || !expectedReviewFingerprint.equals(
                    review.reviewFingerprint())
                    || !expectedDisposition.equals(review.disposition().name())
                    || !expectedReviewer.equals(review.reviewedBy())
                    || !expectedReviewedAt.equals(review.reviewedAt().toString())) {
                throw new Violation(Reason.STORED_STATE_CORRUPT);
            }
            return review;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new Violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private CapabilityObservationReview verify(
            CapabilityObservationReview review) {
        try {
            CapabilityObservationReview exact =
                    Objects.requireNonNull(review, "review");
            if (!integrity.reviewVerified(exact)) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private static CapabilityObservationReview sameOrConflict(
            CapabilityObservationReview stored,
            CapabilityObservationReview candidate) {
        if (stored.sourceCommandFingerprint().equals(
                candidate.sourceCommandFingerprint())
                && stored.reviewFingerprint().equals(
                candidate.reviewFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.REVIEW_CONFLICT);
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException("observationId is invalid");
        }
        return exact;
    }
}
