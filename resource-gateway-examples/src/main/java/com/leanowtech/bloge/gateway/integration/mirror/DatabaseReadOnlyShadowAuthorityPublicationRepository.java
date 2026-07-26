package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-authoritative append-only log for signed read-only Shadow control decisions.
 *
 * <p>Each complete enterprise scope, publication kind, and stream id owns one durable head row.
 * Append first requires a currently trusted, scope- and protocol-delegated signature. It then
 * locks the stream head, validates an exact predecessor, inserts immutable canonical JSON, and
 * advances the head with compare-and-set in one transaction. Concurrent first-generation writers
 * retry the complete transaction after a head-initialization race, which is required by databases
 * such as PostgreSQL that abort a transaction after a uniqueness violation. Multiple Resource
 * Gateway replicas therefore observe one trusted successor without relying on process-local
 * revision state. Reads revalidate every indexed coordinate against canonical JSON and fail closed
 * on corruption.</p>
 *
 * <p>The schema intentionally contains only public authority material and payload-free scope
 * coordinates. It has no request, response, fixture, secret, credential, exception, or stack
 * columns.</p>
 */
public final class
DatabaseReadOnlyShadowAuthorityPublicationRepository
        implements ReadOnlyShadowAuthorityPublicationSource {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_authority_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                publication_kind VARCHAR(64) NOT NULL,
                stream_id VARCHAR(512) NOT NULL,
                current_revision BIGINT NOT NULL,
                current_publication_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, publication_kind,
                    stream_id
                )
            )
            """;
    private static final String CREATE_PUBLICATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_authority_publications (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                publication_kind VARCHAR(64) NOT NULL,
                stream_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                publication_fingerprint VARCHAR(71) NOT NULL UNIQUE,
                material_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                publication_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, publication_kind,
                    stream_id, revision
                )
            )
            """;
    private static final String SELECT_HEAD = """
            SELECT current_revision,
                   current_publication_fingerprint
            FROM mirror_shadow_authority_heads
            WHERE tenant_id = ?
              AND organization_id = ?
              AND project_id = ?
              AND environment_id = ?
              AND region = ?
              AND publication_kind = ?
              AND stream_id = ?
            """;
    private static final String SELECT_PUBLICATION = """
            SELECT revision,
                   publication_fingerprint,
                   material_fingerprint,
                   schema_version,
                   publication_json
            FROM mirror_shadow_authority_publications
            WHERE tenant_id = ?
              AND organization_id = ?
              AND project_id = ?
              AND environment_id = ?
              AND region = ?
              AND publication_kind = ?
              AND stream_id = ?
              AND revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReadOnlyShadowAuthorityIntegrity integrity;
    private final ReadOnlyShadowAuthorityTrustStore trustStore;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * Creates a database-authoritative publication repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity canonical publication fingerprint verifier
     * @param trustStore dynamic scope- and protocol-bound authority trust source
     * @param clock trusted publication-admission clock
     * @param transactionManager transaction manager shared by Mirror persistence
     */
    public DatabaseReadOnlyShadowAuthorityPublicationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowAuthorityIntegrity integrity,
            ReadOnlyShadowAuthorityTrustStore trustStore,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(
                mapper, "mapper")
                .copy()
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES);
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.trustStore = Objects.requireNonNull(
                trustStore, "trustStore");
        this.clock = Objects.requireNonNull(
                clock, "clock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager,
                        "transactionManager"));
        this.transactions.setPropagationBehavior(
                TransactionDefinition
                        .PROPAGATION_REQUIRES_NEW);
    }

    /** Creates additive immutable-publication and mutable-head tables when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_PUBLICATIONS);
    }

    /**
     * Appends one canonical shared guard-policy successor.
     *
     * @param publication signed policy publication
     * @return the inserted publication, or the existing identical current generation
     */
    public ReadOnlyShadowGuardPolicyPublication append(
            ReadOnlyShadowGuardPolicyPublication publication) {
        return (ReadOnlyShadowGuardPolicyPublication)
                append(Envelope.from(publication));
    }

    /**
     * Appends one canonical sampling-grant successor.
     *
     * @param publication signed grant publication
     * @return the inserted publication, or the existing identical current generation
     */
    public ReadOnlyShadowSamplingGrantPublication append(
            ReadOnlyShadowSamplingGrantPublication publication) {
        return (ReadOnlyShadowSamplingGrantPublication)
                append(Envelope.from(publication));
    }

    /**
     * Appends one canonical kill-switch successor.
     *
     * @param publication signed switch publication
     * @return the inserted publication, or the existing identical current generation
     */
    public ReadOnlyShadowKillSwitchPublication append(
            ReadOnlyShadowKillSwitchPublication publication) {
        return (ReadOnlyShadowKillSwitchPublication)
                append(Envelope.from(publication));
    }

    @Override
    public Optional<ReadOnlyShadowSamplingGrantPublication>
    currentSamplingGrant(
            CapabilitySnapshot.Scope scope,
            String grantId) {
        return current(
                new Stream(
                        PublicationKind.SAMPLING_GRANT,
                        scope,
                        grantId),
                ReadOnlyShadowSamplingGrantPublication.class)
                .map(
                        ReadOnlyShadowSamplingGrantPublication
                                .class::cast);
    }

    @Override
    public Optional<ReadOnlyShadowKillSwitchPublication>
    currentKillSwitch(
            CapabilitySnapshot.Scope scope,
            String switchId) {
        return current(
                new Stream(
                        PublicationKind.KILL_SWITCH,
                        scope,
                        switchId),
                ReadOnlyShadowKillSwitchPublication.class)
                .map(
                        ReadOnlyShadowKillSwitchPublication
                                .class::cast);
    }

    @Override
    public Optional<ReadOnlyShadowGuardPolicyPublication>
    currentGuardPolicy(
            CapabilitySnapshot.Scope guardScope,
            String policyId) {
        return current(
                new Stream(
                        PublicationKind.GUARD_POLICY,
                        guardScope,
                        policyId),
                ReadOnlyShadowGuardPolicyPublication.class)
                .map(
                        ReadOnlyShadowGuardPolicyPublication
                                .class::cast);
    }

    @Override
    public boolean available() {
        try {
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mirror_shadow_authority_heads",
                    Long.class);
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mirror_shadow_authority_publications",
                    Long.class);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Object append(Envelope envelope) {
        if (!canonical(envelope)) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        requireTrusted(envelope);
        Object stored = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                stored = transactions.execute(status -> {
                    ensureHead(envelope.stream());
                    return appendLocked(envelope);
                });
                break;
            } catch (HeadInitializationRace race) {
                if (attempt == 1) {
                    throw violation(
                            Reason.CONCURRENT_INITIALIZATION);
                }
            }
        }
        if (stored == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return stored;
    }

    private Object appendLocked(Envelope envelope) {
        Head head = selectHead(
                envelope.stream(), true)
                .orElseThrow(() ->
                        violation(
                                Reason.STORED_STATE_CORRUPT));
        Optional<Object> sameRevision = findRevision(
                envelope.stream(),
                envelope.revision(),
                envelope.publicationType());
        if (envelope.revision() < head.revision()) {
            throw violation(Reason.REVISION_ROLLBACK);
        }
        if (envelope.revision() == head.revision()) {
            if (sameRevision.isPresent()
                    && head.publicationFingerprint()
                    .equals(
                            envelope.publicationFingerprint())
                    && Envelope.from(sameRevision.get())
                    .publicationFingerprint()
                    .equals(
                            envelope.publicationFingerprint())) {
                return sameRevision.get();
            }
            throw violation(Reason.REVISION_FORK);
        }
        if (sameRevision.isPresent()) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        requireSuccessor(head, envelope);
        insert(envelope);
        advanceHead(head, envelope);
        return envelope.publication();
    }

    private Optional<Object> current(
            Stream stream,
            Class<?> publicationType) {
        Stream exact = Objects.requireNonNull(
                stream, "stream");
        Optional<Head> head = selectHead(exact, false);
        if (head.isEmpty()
                || head.get().revision() == 0) {
            return Optional.empty();
        }
        Object publication = findRevision(
                exact,
                head.get().revision(),
                publicationType)
                .orElseThrow(() -> violation(
                        Reason.STORED_STATE_CORRUPT));
        Envelope envelope = Envelope.from(publication);
        if (!head.get().publicationFingerprint()
                .equals(
                        envelope.publicationFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(publication);
    }

    private void ensureHead(Stream stream) {
        if (selectHead(stream, false).isPresent()) {
            return;
        }
        CapabilitySnapshot.Scope scope =
                stream.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_shadow_authority_heads (
                                tenant_id, organization_id, project_id,
                                environment_id, region, publication_kind,
                                stream_id, current_revision,
                                current_publication_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, '')
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    stream.kind().name(),
                    stream.streamId());
        } catch (DuplicateKeyException alreadyInitialized) {
            // PostgreSQL aborts this transaction; retry the whole append in a fresh transaction.
            throw new HeadInitializationRace();
        }
    }

    private void requireTrusted(Envelope envelope) {
        boolean available;
        Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
                key;
        try {
            available = trustStore.available();
            key = available
                    ? trustStore.resolve(
                    envelope.stream().scope(),
                    envelope.authorityKind(),
                    envelope.issuer(),
                    envelope.keyId())
                    : Optional.empty();
        } catch (RuntimeException unavailable) {
            throw violation(
                    Reason.AUTHORITY_TRUST_UNAVAILABLE);
        }
        if (!available) {
            throw violation(
                    Reason.AUTHORITY_TRUST_UNAVAILABLE);
        }
        if (key.isEmpty()) {
            throw violation(
                    Reason.AUTHORITY_KEY_REJECTED);
        }
        Instant now = clock.instant();
        if (!envelope.expiresAt().isAfter(now)) {
            throw violation(
                    Reason.PUBLICATION_EXPIRED);
        }
        Instant verificationTime =
                envelope.effectiveAt().isAfter(now)
                        ? envelope.effectiveAt()
                        : now;
        ReadOnlyShadowAuthorityIntegrity.VerificationResult
                verification = switch (
                envelope.stream().kind()) {
            case GUARD_POLICY ->
                    integrity.verifyGuardPolicy(
                            (ReadOnlyShadowGuardPolicyPublication)
                                    envelope.publication(),
                            key.get(),
                            verificationTime);
            case SAMPLING_GRANT ->
                    integrity.verifySamplingGrant(
                            (ReadOnlyShadowSamplingGrantPublication)
                                    envelope.publication(),
                            key.get(),
                            verificationTime);
            case KILL_SWITCH ->
                    integrity.verifyKillSwitch(
                            (ReadOnlyShadowKillSwitchPublication)
                                    envelope.publication(),
                            key.get(),
                            verificationTime);
        };
        if (!verification.verified()) {
            throw violation(
                    Reason.AUTHORITY_PUBLICATION_UNTRUSTED);
        }
    }

    private Optional<Head> selectHead(
            Stream stream,
            boolean lock) {
        CapabilitySnapshot.Scope scope =
                stream.scope();
        List<Head> rows = jdbc.query(
                SELECT_HEAD + (lock
                        ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> new Head(
                        rs.getLong(
                                "current_revision"),
                        rs.getString(
                                "current_publication_fingerprint")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                stream.kind().name(),
                stream.streamId());
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<Object> findRevision(
            Stream stream,
            long revision,
            Class<?> publicationType) {
        CapabilitySnapshot.Scope scope =
                stream.scope();
        List<Object> rows = jdbc.query(
                SELECT_PUBLICATION,
                (rs, rowNumber) -> deserialize(
                        rs,
                        stream,
                        publicationType),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                stream.kind().name(),
                stream.streamId(),
                revision);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Object deserialize(
            ResultSet rs,
            Stream expected,
            Class<?> publicationType)
            throws SQLException {
        try {
            Object publication = mapper.readValue(
                    rs.getString("publication_json"),
                    publicationType);
            Envelope envelope =
                    Envelope.from(publication);
            if (!canonical(envelope)
                    || !envelope.stream().equals(expected)
                    || envelope.revision()
                    != rs.getLong("revision")
                    || !envelope.publicationFingerprint()
                    .equals(rs.getString(
                            "publication_fingerprint"))
                    || !envelope.materialFingerprint()
                    .equals(rs.getString(
                            "material_fingerprint"))
                    || !envelope.schemaVersion()
                    .equals(rs.getString(
                            "schema_version"))) {
                throw violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return publication;
        } catch (JsonProcessingException
                 | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private void insert(Envelope envelope) {
        CapabilitySnapshot.Scope scope =
                envelope.stream().scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_shadow_authority_publications (
                                tenant_id, organization_id, project_id,
                                environment_id, region, publication_kind,
                                stream_id, revision, publication_fingerprint,
                                material_fingerprint, schema_version,
                                publication_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    envelope.stream().kind().name(),
                    envelope.stream().streamId(),
                    envelope.revision(),
                    envelope.publicationFingerprint(),
                    envelope.materialFingerprint(),
                    envelope.schemaVersion(),
                    serialize(envelope.publication()));
        } catch (DuplicateKeyException collision) {
            throw violation(
                    Reason.CONTENT_ADDRESS_CONFLICT);
        }
    }

    private void advanceHead(
            Head head,
            Envelope envelope) {
        CapabilitySnapshot.Scope scope =
                envelope.stream().scope();
        int advanced = jdbc.update("""
                        UPDATE mirror_shadow_authority_heads
                        SET current_revision = ?,
                            current_publication_fingerprint = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND publication_kind = ?
                          AND stream_id = ?
                          AND current_revision = ?
                          AND current_publication_fingerprint = ?
                        """,
                envelope.revision(),
                envelope.publicationFingerprint(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                envelope.stream().kind().name(),
                envelope.stream().streamId(),
                head.revision(),
                head.publicationFingerprint());
        if (advanced != 1) {
            throw violation(Reason.REVISION_FORK);
        }
    }

    private void requireSuccessor(
            Head head,
            Envelope envelope) {
        if (head.revision() == 0) {
            if (envelope.revision() != 1) {
                throw violation(
                        Reason.BOOTSTRAP_REVISION_INVALID);
            }
            return;
        }
        if (envelope.revision()
                > head.revision() + 1) {
            throw violation(Reason.REVISION_GAP);
        }
        if (!envelope.previousPublicationFingerprint()
                .equals(
                        head.publicationFingerprint())) {
            throw violation(
                    Reason.PREDECESSOR_MISMATCH);
        }
    }

    private boolean canonical(Envelope envelope) {
        return switch (envelope.stream().kind()) {
            case GUARD_POLICY ->
                    integrity.canonicalFingerprintVerified(
                            (ReadOnlyShadowGuardPolicyPublication)
                                    envelope.publication());
            case SAMPLING_GRANT ->
                    integrity.canonicalFingerprintVerified(
                            (ReadOnlyShadowSamplingGrantPublication)
                                    envelope.publication());
            case KILL_SWITCH ->
                    integrity.canonicalFingerprintVerified(
                            (ReadOnlyShadowKillSwitchPublication)
                                    envelope.publication());
        };
    }

    private String serialize(Object publication) {
        try {
            return mapper.writeValueAsString(publication);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.CANONICAL_INVALID);
        }
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    /** Stable fail-closed repository violation. */
    public static final class Violation
            extends RuntimeException {
        private final Reason reason;

        private Violation(Reason reason) {
            super("Read-only Shadow authority repository rejected publication: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable payload-free rejection reason */
        public Reason reason() {
            return reason;
        }
    }

    /** Bounded repository rejection reasons. */
    public enum Reason {
        /** Canonical material or publication fingerprint is invalid. */
        CANONICAL_INVALID,
        /** A stream did not begin at revision one. */
        BOOTSTRAP_REVISION_INVALID,
        /** A successor skipped one or more revisions. */
        REVISION_GAP,
        /** A publication attempts to move behind the durable head. */
        REVISION_ROLLBACK,
        /** Two different publications claim one stream revision. */
        REVISION_FORK,
        /** A successor does not name the exact current publication fingerprint. */
        PREDECESSOR_MISMATCH,
        /** A global content address already belongs to different coordinates. */
        CONTENT_ADDRESS_CONFLICT,
        /** Dynamic key or revocation state could not be resolved. */
        AUTHORITY_TRUST_UNAVAILABLE,
        /** No current key delegation matches scope, protocol, issuer, and key id. */
        AUTHORITY_KEY_REJECTED,
        /** The authority publication is already outside its online validity window. */
        PUBLICATION_EXPIRED,
        /** Fingerprints are canonical but authority policy or signature verification failed. */
        AUTHORITY_PUBLICATION_UNTRUSTED,
        /** Concurrent head bootstrap did not converge after a fresh-transaction retry. */
        CONCURRENT_INITIALIZATION,
        /** Indexed rows and canonical publication JSON no longer agree. */
        STORED_STATE_CORRUPT
    }

    private enum PublicationKind {
        GUARD_POLICY,
        SAMPLING_GRANT,
        KILL_SWITCH
    }

    private record Stream(
            PublicationKind kind,
            CapabilitySnapshot.Scope scope,
            String streamId
    ) {
        private Stream {
            kind = Objects.requireNonNull(kind, "kind");
            scope = ReadOnlyShadowAuthoritySeal.scope(
                    scope, "scope");
            streamId =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            streamId, "streamId");
        }
    }

    private record Head(
            long revision,
            String publicationFingerprint
    ) {
        private Head {
            publicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.normalized(
                            publicationFingerprint);
            if (revision < 0
                    || revision == 0
                    && !publicationFingerprint.isBlank()
                    || revision > 0
                    && !ReadOnlyShadowAuthoritySeal
                    .FINGERPRINT.matcher(
                            publicationFingerprint)
                    .matches()) {
                throw violation(
                        Reason.STORED_STATE_CORRUPT);
            }
        }
    }

    private record Envelope(
            Stream stream,
            long revision,
            String previousPublicationFingerprint,
            String publicationFingerprint,
            String materialFingerprint,
            String schemaVersion,
            Object publication,
            Class<?> publicationType
    ) {
        private Envelope {
            stream = Objects.requireNonNull(
                    stream, "stream");
            if (revision < 1) {
                throw new IllegalArgumentException(
                        "revision must be positive");
            }
            previousPublicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.predecessor(
                            previousPublicationFingerprint,
                            revision,
                            "previousPublicationFingerprint");
            publicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.fingerprint(
                            publicationFingerprint,
                            "publicationFingerprint");
            materialFingerprint =
                    ReadOnlyShadowAuthoritySeal.fingerprint(
                            materialFingerprint,
                            "materialFingerprint");
            schemaVersion =
                    ReadOnlyShadowAuthoritySeal.required(
                            schemaVersion,
                            "schemaVersion",
                            128);
            publication = Objects.requireNonNull(
                    publication, "publication");
            publicationType = Objects.requireNonNull(
                    publicationType, "publicationType");
        }

        private static Envelope from(Object publication) {
            if (publication
                    instanceof
                    ReadOnlyShadowGuardPolicyPublication value) {
                return new Envelope(
                        new Stream(
                                PublicationKind.GUARD_POLICY,
                                value.material().guardScope(),
                                value.material().policyId()),
                        value.material().revision(),
                        value.material()
                                .previousPublicationFingerprint(),
                        value.publicationFingerprint(),
                        value.materialFingerprint(),
                        value.schemaVersion(),
                        value,
                        ReadOnlyShadowGuardPolicyPublication
                                .class);
            }
            if (publication
                    instanceof
                    ReadOnlyShadowSamplingGrantPublication value) {
                return new Envelope(
                        new Stream(
                                PublicationKind.SAMPLING_GRANT,
                                value.material().scope(),
                                value.material().grantId()),
                        value.material().revision(),
                        value.material()
                                .previousPublicationFingerprint(),
                        value.publicationFingerprint(),
                        value.materialFingerprint(),
                        value.schemaVersion(),
                        value,
                        ReadOnlyShadowSamplingGrantPublication
                                .class);
            }
            if (publication
                    instanceof
                    ReadOnlyShadowKillSwitchPublication value) {
                return new Envelope(
                        new Stream(
                                PublicationKind.KILL_SWITCH,
                                value.material().scope(),
                                value.material().switchId()),
                        value.material().revision(),
                        value.material()
                                .previousPublicationFingerprint(),
                        value.publicationFingerprint(),
                        value.materialFingerprint(),
                        value.schemaVersion(),
                        value,
                        ReadOnlyShadowKillSwitchPublication
                                .class);
            }
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow authority publication");
        }

        private ReadOnlyShadowAuthorityIntegrity.PublicationKind
        authorityKind() {
            return ReadOnlyShadowAuthorityIntegrity
                    .PublicationKind.valueOf(
                            stream.kind().name());
        }

        private String issuer() {
            if (publication
                    instanceof
                    ReadOnlyShadowGuardPolicyPublication value) {
                return value.material().issuer();
            }
            if (publication
                    instanceof
                    ReadOnlyShadowSamplingGrantPublication value) {
                return value.material().issuer();
            }
            return ((ReadOnlyShadowKillSwitchPublication)
                    publication).material().issuer();
        }

        private String keyId() {
            if (publication
                    instanceof
                    ReadOnlyShadowGuardPolicyPublication value) {
                return value.seal().keyId();
            }
            if (publication
                    instanceof
                    ReadOnlyShadowSamplingGrantPublication value) {
                return value.seal().keyId();
            }
            return ((ReadOnlyShadowKillSwitchPublication)
                    publication).seal().keyId();
        }

        private Instant effectiveAt() {
            if (publication
                    instanceof
                    ReadOnlyShadowGuardPolicyPublication value) {
                return value.material().validFrom()
                        .isAfter(value.seal().signedAt())
                        ? value.material().validFrom()
                        : value.seal().signedAt();
            }
            if (publication
                    instanceof
                    ReadOnlyShadowSamplingGrantPublication value) {
                return value.material().validFrom()
                        .isAfter(value.seal().signedAt())
                        ? value.material().validFrom()
                        : value.seal().signedAt();
            }
            ReadOnlyShadowKillSwitchPublication value =
                    (ReadOnlyShadowKillSwitchPublication)
                            publication;
            return value.material().effectiveAt()
                    .isAfter(value.seal().signedAt())
                    ? value.material().effectiveAt()
                    : value.seal().signedAt();
        }

        private Instant expiresAt() {
            if (publication
                    instanceof
                    ReadOnlyShadowGuardPolicyPublication value) {
                return value.material().expiresAt();
            }
            if (publication
                    instanceof
                    ReadOnlyShadowSamplingGrantPublication value) {
                return value.material().expiresAt();
            }
            return ((ReadOnlyShadowKillSwitchPublication)
                    publication).material().expiresAt();
        }
    }

    private static final class HeadInitializationRace
            extends RuntimeException {
        private HeadInitializationRace() {
            super("Concurrent authority head initialization");
        }
    }
}
