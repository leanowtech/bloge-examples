package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-authoritative, all-or-nothing quota and lease protocol for the isolated test runtime.
 *
 * <p>Every admission first locks a hashed request identity and every hashed quota subject in stable
 * order. It then verifies one policy generation, counts only database-clock-live permits, and
 * inserts all claims in one local transaction. Competing replicas therefore cannot each observe
 * spare capacity and over-admit the same tenant, suite, operator, or dependency. Raw subject names,
 * fixture data, business context, and lease tokens are never persisted.</p>
 */
public final class DatabaseTestRuntimeAdmissionControl {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(2);
    private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);
    private static final int MAXIMUM_SUBJECTS = 10_001;
    private static final int MAXIMUM_PURGE_BATCH = 10_000;
    private static final int ADMISSION_LOCK_STRIPES = 4_096;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate mutations;

    /**
     * Creates and initializes the admission authority on the isolated runtime datasource.
     *
     * @param jdbc test-runtime JDBC facade
     * @param transactionManager transaction manager for the same datasource
     */
    public DatabaseTestRuntimeAdmissionControl(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        mutations = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        initialize();
    }

    private void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_admission_locks (
                    lock_key VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_admission_subject_policies (
                    subject_key VARCHAR(71) PRIMARY KEY,
                    dimension VARCHAR(32) NOT NULL,
                    policy_generation BIGINT NOT NULL,
                    max_active BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_admission_leases (
                    admission_id VARCHAR(71) PRIMARY KEY,
                    intent_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    policy_generation BIGINT NOT NULL,
                    token_fingerprint VARCHAR(71) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_admission_claims (
                    admission_id VARCHAR(71) NOT NULL,
                    subject_key VARCHAR(71) NOT NULL,
                    dimension VARCHAR(32) NOT NULL,
                    PRIMARY KEY (admission_id, subject_key)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_admission_lease_expiry_idx
                ON rg_test_admission_leases (lease_expires_at, admission_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_admission_claim_subject_idx
                ON rg_test_admission_claims (subject_key, admission_id)
                """);
    }

    /**
     * Atomically acquires all requested dimensions or returns the first saturated dimension.
     *
     * @param request exact hashed admission and quota-subject intent
     * @return acquired permit, existing in-progress observation, or quota rejection
     */
    public AcquireResult acquire(AdmissionRequest request) {
        AdmissionRequest normalized = Objects.requireNonNull(request, "request");
        AcquireResult result = mutations.execute(status -> acquireInTransaction(normalized));
        if (result == null) {
            throw new IllegalStateException("Admission transaction returned no result");
        }
        return result;
    }

    private AcquireResult acquireInTransaction(AdmissionRequest request) {
        Instant observedAt = currentTime();
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add(admissionLock(request.admissionId()));
        request.subjects().stream().map(QuotaSubject::subjectFingerprint).forEach(lockKeys::add);
        lockKeys = lockKeys.stream().distinct().sorted().toList();
        ensureAndLock(lockKeys);

        StoredLease existing = storedLease(request.admissionId()).orElse(null);
        long nextEpoch = 0;
        if (existing != null) {
            requireSameIntent(existing, request);
            if (existing.leaseExpiresAt().isAfter(observedAt)) {
                return AcquireResult.alreadyActive(existing.leaseExpiresAt(), observedAt);
            }
            nextEpoch = Math.addExact(existing.leaseEpoch(), 1);
            jdbc.update("DELETE FROM rg_test_admission_claims WHERE admission_id = ?",
                    request.admissionId());
            jdbc.update("DELETE FROM rg_test_admission_leases WHERE admission_id = ?",
                    request.admissionId());
        }

        QuotaSubject tenant = request.subjects().stream()
                .filter(subject -> subject.dimension()
                        == TestRuntimeAdmissionPolicy.Dimension.TENANT)
                .findFirst().orElseThrow();
        long activeTenant = activeCount(tenant.subjectFingerprint(), observedAt);
        for (QuotaSubject subject : request.subjects()) {
            applySubjectPolicy(subject, request.policyGeneration(), activeTenant, observedAt);
        }

        for (QuotaSubject subject : request.subjects()) {
            Capacity capacity = capacity(subject.subjectFingerprint(), observedAt);
            if (capacity.active() >= subject.maxActive()) {
                long retryAfter = retryAfterSeconds(
                        capacity.earliestExpiry(), observedAt, request.leaseDuration());
                return AcquireResult.rejected(new Rejection(
                        subject.dimension(), subject.maxActive(), capacity.active(), retryAfter),
                        observedAt);
            }
        }

        String token = UUID.randomUUID().toString();
        Instant expiresAt = observedAt.plus(request.leaseDuration());
        jdbc.update("""
                        INSERT INTO rg_test_admission_leases (
                            admission_id, intent_fingerprint, policy_fingerprint,
                            policy_generation, token_fingerprint, owner_id, lease_epoch,
                            lease_expires_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                request.admissionId(), request.intentFingerprint(), request.policyFingerprint(),
                request.policyGeneration(), tokenFingerprint(token), request.ownerId(), nextEpoch,
                Timestamp.from(expiresAt), Timestamp.from(observedAt), Timestamp.from(observedAt));
        for (QuotaSubject subject : request.subjects()) {
            jdbc.update("""
                            INSERT INTO rg_test_admission_claims (
                                admission_id, subject_key, dimension
                            ) VALUES (?, ?, ?)
                            """,
                    request.admissionId(), subject.subjectFingerprint(),
                    subject.dimension().name());
        }
        return AcquireResult.acquired(new AdmissionLease(
                request.admissionId(), request.intentFingerprint(), request.policyFingerprint(),
                request.policyGeneration(), token, request.ownerId(), nextEpoch,
                expiresAt, request.subjects()), observedAt);
    }

    /**
     * Extends one exact live permit without resurrecting an expired or superseded token.
     *
     * @param lease exact token and epoch returned by acquisition
     * @param leaseDuration bounded extension from database current time
     * @return renewed permit, or empty when ownership is no longer live
     */
    public Optional<AdmissionLease> renew(AdmissionLease lease, Duration leaseDuration) {
        AdmissionLease current = Objects.requireNonNull(lease, "lease");
        Duration duration = boundedLease(leaseDuration);
        return Optional.ofNullable(mutations.execute(status -> {
            Instant observedAt = currentTime();
            Instant expiresAt = observedAt.plus(duration);
            int updated = jdbc.update("""
                            UPDATE rg_test_admission_leases
                            SET lease_expires_at = ?, updated_at = ?
                            WHERE admission_id = ?
                              AND intent_fingerprint = ?
                              AND policy_fingerprint = ?
                              AND policy_generation = ?
                              AND token_fingerprint = ?
                              AND owner_id = ?
                              AND lease_epoch = ?
                              AND lease_expires_at > ?
                            """,
                    Timestamp.from(expiresAt), Timestamp.from(observedAt), current.admissionId(),
                    current.intentFingerprint(), current.policyFingerprint(),
                    current.policyGeneration(), tokenFingerprint(current.token()),
                    current.ownerId(), current.leaseEpoch(), Timestamp.from(observedAt));
            return updated == 1 ? current.withExpiry(expiresAt) : null;
        }));
    }

    /**
     * Releases one exact permit and all claims in one transaction.
     *
     * @param lease exact token and epoch returned by acquisition
     * @return true when this caller owned and removed the permit
     */
    public boolean release(AdmissionLease lease) {
        AdmissionLease current = Objects.requireNonNull(lease, "lease");
        Boolean released = mutations.execute(status -> {
            ensureAndLock(List.of(admissionLock(current.admissionId())));
            Integer owned = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM rg_test_admission_leases
                            WHERE admission_id = ? AND token_fingerprint = ?
                              AND owner_id = ? AND lease_epoch = ?
                            """, Integer.class,
                    current.admissionId(), tokenFingerprint(current.token()),
                    current.ownerId(), current.leaseEpoch());
            if (owned == null || owned != 1) {
                return false;
            }
            jdbc.update("DELETE FROM rg_test_admission_claims WHERE admission_id = ?",
                    current.admissionId());
            return jdbc.update("""
                            DELETE FROM rg_test_admission_leases
                            WHERE admission_id = ? AND token_fingerprint = ?
                              AND owner_id = ? AND lease_epoch = ?
                            """,
                    current.admissionId(), tokenFingerprint(current.token()),
                    current.ownerId(), current.leaseEpoch()) == 1;
        });
        return Boolean.TRUE.equals(released);
    }

    /**
     * Purges a bounded oldest-first page of expired permits and their orphan-safe claims.
     *
     * @param limit positive page size, capped at 10,000
     * @return number of expired permit rows removed
     */
    public int purgeExpired(int limit) {
        if (limit <= 0 || limit > MAXIMUM_PURGE_BATCH) {
            throw new IllegalArgumentException(
                    "Admission purge limit must be between 1 and " + MAXIMUM_PURGE_BATCH);
        }
        Integer removed = mutations.execute(status -> {
            Instant observedAt = currentTime();
            List<String> ids = jdbc.query("""
                            SELECT admission_id
                            FROM rg_test_admission_leases
                            WHERE lease_expires_at <= ?
                            ORDER BY lease_expires_at, admission_id
                            LIMIT ?
                            """,
                    (rs, row) -> rs.getString("admission_id"),
                    Timestamp.from(observedAt), limit);
            if (ids.isEmpty()) {
                return 0;
            }
            ensureAndLock(ids.stream().map(DatabaseTestRuntimeAdmissionControl::admissionLock)
                    .distinct().sorted().toList());
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<String> expired = jdbc.query("SELECT admission_id FROM rg_test_admission_leases "
                            + "WHERE admission_id IN (" + placeholders + ") "
                            + "AND lease_expires_at <= ? ORDER BY admission_id",
                    (rs, row) -> rs.getString("admission_id"),
                    append(ids, Timestamp.from(observedAt)));
            if (expired.isEmpty()) {
                return 0;
            }
            placeholders = String.join(",",
                    java.util.Collections.nCopies(expired.size(), "?"));
            jdbc.update("DELETE FROM rg_test_admission_claims WHERE admission_id IN ("
                    + placeholders + ")", expired.toArray());
            return jdbc.update("DELETE FROM rg_test_admission_leases WHERE admission_id IN ("
                    + placeholders + ") AND lease_expires_at <= ?",
                    append(expired, Timestamp.from(observedAt)));
        });
        return removed == null ? 0 : removed;
    }

    private void ensureAndLock(List<String> lockKeys) {
        for (String lockKey : lockKeys) {
            jdbc.update("MERGE INTO rg_test_admission_locks (lock_key) KEY(lock_key) VALUES (?)",
                    lockKey);
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(lockKeys.size(), "?"));
        List<String> locked = jdbc.query("SELECT lock_key FROM rg_test_admission_locks WHERE lock_key IN ("
                        + placeholders + ") ORDER BY lock_key FOR UPDATE",
                (rs, row) -> rs.getString("lock_key"), lockKeys.toArray());
        if (locked.size() != lockKeys.size()) {
            throw new IllegalStateException("Admission lock set is incomplete");
        }
    }

    private void applySubjectPolicy(
            QuotaSubject subject,
            long requestedGeneration,
            long activeTenant,
            Instant observedAt) {
        SubjectPolicy stored = jdbc.query("""
                        SELECT dimension, policy_generation, max_active
                        FROM rg_test_admission_subject_policies
                        WHERE subject_key = ?
                        """,
                rs -> rs.next() ? new SubjectPolicy(
                        TestRuntimeAdmissionPolicy.Dimension.valueOf(
                                rs.getString("dimension").toUpperCase(Locale.ROOT)),
                        rs.getLong("policy_generation"), rs.getLong("max_active")) : null,
                subject.subjectFingerprint());
        if (stored == null) {
            try {
                jdbc.update("""
                                INSERT INTO rg_test_admission_subject_policies (
                                    subject_key, dimension, policy_generation, max_active, updated_at
                                ) VALUES (?, ?, ?, ?, ?)
                                """,
                        subject.subjectFingerprint(), subject.dimension().name(),
                        requestedGeneration, subject.maxActive(), Timestamp.from(observedAt));
            } catch (DataIntegrityViolationException concurrentInitialization) {
                throw new AdmissionConflictException(ConflictReason.POLICY_DRIFT,
                        "Admission subject policy initialization conflicted");
            }
            return;
        }
        if (stored.dimension() != subject.dimension()) {
            throw new AdmissionConflictException(ConflictReason.POLICY_DRIFT,
                    "Admission subject dimension is inconsistent");
        }
        if (stored.generation() == requestedGeneration) {
            if (stored.maxActive() != subject.maxActive()) {
                throw new AdmissionConflictException(ConflictReason.POLICY_DRIFT,
                        "Admission policy changed without a new generation");
            }
            return;
        }
        if (stored.generation() > requestedGeneration) {
            throw new AdmissionConflictException(ConflictReason.STALE_POLICY,
                    "Admission replica uses a stale policy generation");
        }
        if (activeTenant > 0) {
            throw new AdmissionConflictException(ConflictReason.POLICY_TRANSITION_ACTIVE,
                    "Admission policy generation cannot change while tenant permits are live");
        }
        int updated = jdbc.update("""
                        UPDATE rg_test_admission_subject_policies
                        SET policy_generation = ?, max_active = ?, updated_at = ?
                        WHERE subject_key = ? AND policy_generation = ?
                        """,
                requestedGeneration, subject.maxActive(), Timestamp.from(observedAt),
                subject.subjectFingerprint(), stored.generation());
        if (updated != 1) {
            throw new AdmissionConflictException(ConflictReason.POLICY_DRIFT,
                    "Admission subject policy transition lost its exact generation fence");
        }
    }

    private Capacity capacity(String subjectFingerprint, Instant observedAt) {
        Capacity capacity = jdbc.queryForObject("""
                        SELECT COUNT(*) AS active_count,
                               MIN(l.lease_expires_at) AS earliest_expiry
                        FROM rg_test_admission_claims c
                        JOIN rg_test_admission_leases l
                          ON l.admission_id = c.admission_id
                        WHERE c.subject_key = ? AND l.lease_expires_at > ?
                        """,
                (rs, row) -> {
                    Timestamp earliest = rs.getTimestamp("earliest_expiry");
                    return new Capacity(rs.getLong("active_count"),
                            earliest == null ? null : earliest.toInstant());
                }, subjectFingerprint, Timestamp.from(observedAt));
        if (capacity == null) {
            throw new IllegalStateException("Admission capacity query returned no result");
        }
        return capacity;
    }

    private long activeCount(String subjectFingerprint, Instant observedAt) {
        return capacity(subjectFingerprint, observedAt).active();
    }

    private Optional<StoredLease> storedLease(String admissionId) {
        List<StoredLease> rows = jdbc.query("""
                        SELECT intent_fingerprint, policy_fingerprint, policy_generation,
                               lease_epoch, lease_expires_at
                        FROM rg_test_admission_leases
                        WHERE admission_id = ?
                        """,
                (rs, row) -> new StoredLease(
                        rs.getString("intent_fingerprint"),
                        rs.getString("policy_fingerprint"),
                        rs.getLong("policy_generation"), rs.getLong("lease_epoch"),
                        rs.getTimestamp("lease_expires_at").toInstant()), admissionId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Admission identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private static void requireSameIntent(StoredLease existing, AdmissionRequest request) {
        if (!existing.intentFingerprint().equals(request.intentFingerprint())
                || !existing.policyFingerprint().equals(request.policyFingerprint())
                || existing.policyGeneration() != request.policyGeneration()) {
            throw new AdmissionConflictException(ConflictReason.IDENTITY_CONFLICT,
                    "Admission identity already represents a different intent");
        }
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Admission database did not return current time");
        }
        return value.toInstant();
    }

    private static String admissionLock(String admissionId) {
        return ProtocolFingerprint.ofText(
                "bloge.testAdmissionLockStripe.v1|" + admissionLockStripe(admissionId));
    }

    /** Returns the bounded stable request-lock stripe used by acquire, release, and retention. */
    static int admissionLockStripe(String admissionId) {
        String value = fingerprint(admissionId, "admissionId");
        long prefix = Long.parseUnsignedLong(value.substring("sha256:".length(), 15), 16);
        return (int) (prefix % ADMISSION_LOCK_STRIPES);
    }

    private static String tokenFingerprint(String token) {
        return ProtocolFingerprint.ofText("bloge.testAdmissionToken.v1|" + token);
    }

    private static long retryAfterSeconds(
            Instant earliestExpiry,
            Instant observedAt,
            Duration leaseDuration) {
        if (earliestExpiry == null || !earliestExpiry.isAfter(observedAt)) {
            return 1;
        }
        long millis = Duration.between(observedAt, earliestExpiry).toMillis();
        long seconds = Math.max(1, Math.floorDiv(millis + 999, 1_000));
        return Math.min(seconds, Math.max(1, leaseDuration.toSeconds()));
    }

    private static Duration boundedLease(Duration value) {
        Duration duration = Objects.requireNonNull(value, "leaseDuration");
        if (duration.compareTo(MINIMUM_LEASE) < 0
                || duration.compareTo(MAXIMUM_LEASE) > 0
                || duration.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Admission lease must be an integral 2 seconds to one hour");
        }
        return duration;
    }

    private static Object[] append(List<String> values, Object tail) {
        Object[] result = new Object[values.size() + 1];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        result[values.size()] = tail;
        return result;
    }

    /** Exact request for one atomic multi-dimensional permit. */
    public record AdmissionRequest(
            String admissionId,
            String intentFingerprint,
            String policyFingerprint,
            long policyGeneration,
            String ownerId,
            Duration leaseDuration,
            List<QuotaSubject> subjects) {
        /** Validates canonical identities, one tenant claim, and a bounded unique lock set. */
        public AdmissionRequest {
            admissionId = fingerprint(admissionId, "admissionId");
            intentFingerprint = fingerprint(intentFingerprint, "intentFingerprint");
            policyFingerprint = fingerprint(policyFingerprint, "policyFingerprint");
            if (policyGeneration <= 0) {
                throw new IllegalArgumentException("Admission policyGeneration must be positive");
            }
            ownerId = ownerId == null ? "" : ownerId.trim();
            if (!OWNER.matcher(ownerId).matches()) {
                throw new IllegalArgumentException("Admission ownerId is invalid");
            }
            leaseDuration = boundedLease(leaseDuration);
            subjects = subjects == null ? List.of() : subjects.stream()
                    .sorted(Comparator.comparing((QuotaSubject subject) -> subject.dimension().name())
                            .thenComparing(QuotaSubject::subjectFingerprint))
                    .toList();
            if (subjects.isEmpty() || subjects.size() > MAXIMUM_SUBJECTS) {
                throw new IllegalArgumentException("Admission subject set is empty or unbounded");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (QuotaSubject subject : subjects) {
                if (!keys.add(subject.subjectFingerprint())) {
                    throw new IllegalArgumentException("Admission subjects must be unique");
                }
            }
            if (subjects.stream().filter(subject -> subject.dimension()
                    == TestRuntimeAdmissionPolicy.Dimension.TENANT).count() != 1) {
                throw new IllegalArgumentException("Admission requires exactly one tenant subject");
            }
        }
    }

    /** One hashed quota subject and the policy limit enforced while its lock is held. */
    public record QuotaSubject(
            TestRuntimeAdmissionPolicy.Dimension dimension,
            String subjectFingerprint,
            long maxActive) {
        /** Rejects unknown dimensions, non-canonical hashes, and non-positive limits. */
        public QuotaSubject {
            dimension = Objects.requireNonNull(dimension, "dimension");
            subjectFingerprint = fingerprint(subjectFingerprint, "subjectFingerprint");
            if (maxActive <= 0 || maxActive > 1_000_000) {
                throw new IllegalArgumentException("Admission maxActive is outside bounded policy");
            }
        }
    }

    /** Exact renewable permit; the raw token exists only in process memory. */
    public record AdmissionLease(
            String admissionId,
            String intentFingerprint,
            String policyFingerprint,
            long policyGeneration,
            String token,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            List<QuotaSubject> subjects) {
        /** Validates immutable fencing and copies the subject closure. */
        public AdmissionLease {
            admissionId = fingerprint(admissionId, "admissionId");
            intentFingerprint = fingerprint(intentFingerprint, "intentFingerprint");
            policyFingerprint = fingerprint(policyFingerprint, "policyFingerprint");
            token = token == null ? "" : token.trim();
            ownerId = ownerId == null ? "" : ownerId.trim();
            if (policyGeneration <= 0 || token.isBlank() || !OWNER.matcher(ownerId).matches()
                    || leaseEpoch < 0 || leaseExpiresAt == null) {
                throw new IllegalArgumentException("Admission lease fence is incomplete");
            }
            subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
        }

        private AdmissionLease withExpiry(Instant expiry) {
            return new AdmissionLease(admissionId, intentFingerprint, policyFingerprint,
                    policyGeneration, token, ownerId, leaseEpoch, expiry, subjects);
        }
    }

    /** Acquisition state without exposing a subject identity or lease token on rejection. */
    public enum AcquireState {
        ACQUIRED,
        ALREADY_ACTIVE,
        REJECTED
    }

    /** Result of one transactionally serialized admission attempt. */
    public record AcquireResult(
            AcquireState state,
            AdmissionLease lease,
            Rejection rejection,
            Instant retryAt,
            Instant observedAt) {
        /** Enforces the shape associated with each result state. */
        public AcquireResult {
            state = Objects.requireNonNull(state, "state");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            switch (state) {
                case ACQUIRED -> Objects.requireNonNull(lease, "lease");
                case ALREADY_ACTIVE -> Objects.requireNonNull(retryAt, "retryAt");
                case REJECTED -> Objects.requireNonNull(rejection, "rejection");
            }
        }

        private static AcquireResult acquired(AdmissionLease lease, Instant observedAt) {
            return new AcquireResult(AcquireState.ACQUIRED, lease, null, null, observedAt);
        }

        private static AcquireResult alreadyActive(Instant retryAt, Instant observedAt) {
            return new AcquireResult(
                    AcquireState.ALREADY_ACTIVE, null, null, retryAt, observedAt);
        }

        private static AcquireResult rejected(Rejection rejection, Instant observedAt) {
            return new AcquireResult(AcquireState.REJECTED, null, rejection, null, observedAt);
        }

        /** @return retry delay rounded up to a positive whole second */
        public long retryAfterSeconds() {
            if (state == AcquireState.REJECTED) {
                return rejection.retryAfterSeconds();
            }
            if (state != AcquireState.ALREADY_ACTIVE) {
                return 0;
            }
            return DatabaseTestRuntimeAdmissionControl.retryAfterSeconds(
                    retryAt, observedAt, Duration.ofHours(1));
        }
    }

    /** Closed-dimension quota rejection with aggregate counts only. */
    public record Rejection(
            TestRuntimeAdmissionPolicy.Dimension dimension,
            long maxActive,
            long active,
            long retryAfterSeconds) {
        /** Rejects impossible aggregate rejection projections. */
        public Rejection {
            dimension = Objects.requireNonNull(dimension, "dimension");
            if (maxActive <= 0 || active < maxActive || retryAfterSeconds <= 0) {
                throw new IllegalArgumentException("Invalid admission rejection aggregate");
            }
        }
    }

    /** Stable non-payload conflict reasons mapped by the application coordinator. */
    public enum ConflictReason {
        IDENTITY_CONFLICT,
        POLICY_DRIFT,
        STALE_POLICY,
        POLICY_TRANSITION_ACTIVE
    }

    /** Fail-closed protocol conflict that never carries raw subject or token values. */
    public static final class AdmissionConflictException extends RuntimeException {
        private final ConflictReason reason;

        /** Creates one stable admission conflict. */
        public AdmissionConflictException(ConflictReason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        /** @return stable machine-readable conflict reason */
        public ConflictReason reason() {
            return reason;
        }
    }

    private static String fingerprint(String value, String name) {
        String result = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(name + " must be canonical SHA-256");
        }
        return result;
    }

    private record StoredLease(
            String intentFingerprint,
            String policyFingerprint,
            long policyGeneration,
            long leaseEpoch,
            Instant leaseExpiresAt) {
    }

    private record SubjectPolicy(
            TestRuntimeAdmissionPolicy.Dimension dimension,
            long generation,
            long maxActive) {
    }

    private record Capacity(long active, Instant earliestExpiry) {
    }
}
