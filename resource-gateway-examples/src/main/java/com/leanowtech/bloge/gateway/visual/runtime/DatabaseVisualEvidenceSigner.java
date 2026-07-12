package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H2-persisted Ed25519 signing authority used by the example application.
 * Production deployments should replace this bean with a KMS/HSM-backed implementation.
 */
public final class DatabaseVisualEvidenceSigner implements VisualEvidenceSigner {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_evidence_signing_keys (
                key_id VARCHAR(255) PRIMARY KEY,
                algorithm VARCHAR(32) NOT NULL,
                public_key CLOB NOT NULL,
                private_key CLOB NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL,
                provider VARCHAR(64) NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT key_id, algorithm, public_key, private_key, created_at, state, provider
            FROM visual_evidence_signing_keys ORDER BY created_at, key_id
            """;
    private static final String INSERT = """
            INSERT INTO visual_evidence_signing_keys
                (key_id, algorithm, public_key, private_key, created_at, state, provider)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final VisualEvidenceSigner delegate;

    public DatabaseVisualEvidenceSigner(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate is required for persistent evidence signing");
        }
        jdbc.execute(CREATE_TABLE);
        List<StoredKey> keys = load(jdbc);
        StoredKey active = keys.stream().filter(key -> "ACTIVE".equals(key.state())).findFirst().orElse(null);
        if (active == null) {
            active = createActiveKey(jdbc);
            keys = load(jdbc);
            StoredKey persistedActive = keys.stream()
                    .filter(key -> "ACTIVE".equals(key.state()))
                    .findFirst()
                    .orElse(active);
            active = persistedActive;
        }
        this.delegate = delegate(active, keys);
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        return delegate.seal(materialFingerprint);
    }

    @Override
    public Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        return delegate.verify(seal, actualMaterialFingerprint);
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        return delegate.key(keyId);
    }

    @Override
    public boolean available() {
        return true;
    }

    private static VisualEvidenceSigner delegate(StoredKey active, List<StoredKey> keys) {
        try {
            Map<String, Ed25519VisualEvidenceSigner.VerificationMaterial> verificationKeys = new LinkedHashMap<>();
            for (StoredKey key : keys) {
                PublicKey publicKey = publicKey(key.publicKey());
                VerificationKey descriptor = new VerificationKey("", key.keyId(), key.algorithm(), key.publicKey(),
                        key.createdAt(), key.state(), key.provider());
                verificationKeys.put(key.keyId(),
                        new Ed25519VisualEvidenceSigner.VerificationMaterial(descriptor, publicKey));
            }
            KeyPair activePair = new KeyPair(publicKey(active.publicKey()), privateKey(active.privateKey()));
            return new Ed25519VisualEvidenceSigner(activePair, active.keyId(), active.createdAt(), active.provider(),
                    verificationKeys);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to load persisted evidence signing key", exception);
        }
    }

    private static StoredKey createActiveKey(JdbcTemplate jdbc) {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("publicKey", publicKey));
            String keyId = "local-h2-ed25519:" + fingerprint.substring("sha256:".length(), 23);
            StoredKey stored = new StoredKey(keyId, "Ed25519", publicKey, privateKey, Instant.now(), "ACTIVE",
                    "LOCAL_H2_DEMO");
            try {
                jdbc.update(INSERT, stored.keyId(), stored.algorithm(), stored.publicKey(), stored.privateKey(),
                        stored.createdAt().toString(), stored.state(), stored.provider());
                return stored;
            } catch (DataAccessException race) {
                return load(jdbc).stream().filter(key -> "ACTIVE".equals(key.state())).findFirst()
                        .orElseThrow(() -> race);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate evidence signing key", exception);
        }
    }

    private static List<StoredKey> load(JdbcTemplate jdbc) {
        return jdbc.query(SELECT_ALL, (rs, rowNum) -> new StoredKey(
                rs.getString("key_id"), rs.getString("algorithm"), rs.getString("public_key"),
                rs.getString("private_key"), Instant.parse(rs.getString("created_at")), rs.getString("state"),
                rs.getString("provider")
        ));
    }

    private static PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    private static PrivateKey privateKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    private record StoredKey(String keyId, String algorithm, String publicKey, String privateKey,
                             Instant createdAt, String state, String provider) {
    }
}
