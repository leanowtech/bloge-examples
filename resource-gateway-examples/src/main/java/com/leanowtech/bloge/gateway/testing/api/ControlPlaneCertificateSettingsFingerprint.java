package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Computes a path- and credential-free identity for one complete TLS settings generation.
 *
 * <p>The fingerprint covers exact trust-store and client-keystore bytes, server pins, and public
 * client/server workload identity policy. Files are streamed through SHA-256; paths, password
 * references, aliases, and resolved secrets never enter canonical material. Re-serializing a
 * PKCS#12 file therefore creates a new governed material generation even when its certificate is
 * semantically similar, which prevents silent private-key or bag-attribute replacement.</p>
 */
public final class ControlPlaneCertificateSettingsFingerprint {

    private final ObjectMapper objectMapper;

    /** @param objectMapper canonical protocol mapper */
    public ControlPlaneCertificateSettingsFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Fingerprints complete immutable settings without exposing source locations.
     *
     * @param settings validated pinned mutual-TLS settings
     * @return canonical {@code sha256:...} fingerprint
     */
    public String fingerprint(
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        var required = Objects.requireNonNull(settings, "settings").validated();
        ControlPlaneCertificateIdentityPolicy identity =
                required.certificateIdentityPolicy();
        var material = new Material(Material.SCHEMA_VERSION,
                required.trustStorePath() == null
                        ? "SYSTEM_PKIX" : fileFingerprint(required.trustStorePath()),
                fileFingerprint(required.clientKeyStorePath()),
                required.serverSpkiPins().stream().sorted().toList(),
                identity.expectedClientSubjectDn(), identity.expectedClientUriSan(),
                identity.clientIssuerSpkiPins().stream().sorted().toList(),
                identity.expectedServerUriSan(),
                identity.serverIssuerSpkiPins().stream().sorted().toList());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private static String fileFingerprint(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException(
                    "Control-plane certificate settings material is unavailable", failure);
        }
    }

    /** Canonical path- and credential-free settings material. */
    private record Material(
            String schemaVersion,
            String trustStoreFingerprint,
            String clientKeyStoreFingerprint,
            List<String> serverSpkiPins,
            String expectedClientSubjectDn,
            String expectedClientUriSan,
            List<String> clientIssuerSpkiPins,
            String expectedServerUriSan,
            List<String> serverIssuerSpkiPins) {

        private static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateSettingsMaterial.v1";
    }
}
