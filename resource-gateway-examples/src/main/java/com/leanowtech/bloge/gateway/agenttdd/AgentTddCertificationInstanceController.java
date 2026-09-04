package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Exposes the immutable identity of an RG process spawned solely for Codex certification.
 *
 * <p>The endpoint does not exist in a normal deployment. The certification launcher enables it
 * with a fresh nonce after packaging a clean commit. The process hashes its own executable archive
 * rather than trusting a digest supplied by the launcher, then verifies the same tuple before and
 * after the Codex turn. This prevents a listener left by another checkout or build, or a replaced
 * archive between packaging and process startup, from being certified as the current source tree.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "gateway.agent-tdd.certification-instance", name = "enabled",
        havingValue = "true")
public final class AgentTddCertificationInstanceController {
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32,128}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private final InstanceIdentity identity;
    private final Supplier<String> currentJarSha256;

    /**
     * Creates the opt-in identity endpoint from values supplied by the owning launcher.
     *
     * @param instanceNonce fresh per-process nonce, never reused across certification runs
     * @param repositoryCommit exact clean commit packaged into the launched JAR
     * @param expectedJarSha256 launcher-computed digest that the running archive must match
     */
    @Autowired
    public AgentTddCertificationInstanceController(
            @Value("${gateway.agent-tdd.certification-instance.instance-nonce}") String instanceNonce,
            @Value("${gateway.agent-tdd.certification-instance.repository-commit}") String repositoryCommit,
            @Value("${gateway.agent-tdd.certification-instance.expected-jar-sha256}")
            String expectedJarSha256) {
        this(instanceNonce, repositoryCommit, expectedJarSha256, runningArchiveSha256(),
                AgentTddCertificationInstanceController::runningArchiveSha256);
    }

    AgentTddCertificationInstanceController(String instanceNonce,
                                            String repositoryCommit,
                                            String expectedJarSha256,
                                            String actualJarSha256) {
        this(instanceNonce, repositoryCommit, expectedJarSha256, actualJarSha256,
                () -> actualJarSha256);
    }

    private AgentTddCertificationInstanceController(String instanceNonce,
                                                    String repositoryCommit,
                                                    String expectedJarSha256,
                                                    String actualJarSha256,
                                                    Supplier<String> currentJarSha256) {
        String expected = requireMatch(expectedJarSha256, SHA256, "expectedJarSha256");
        String actual = requireMatch(actualJarSha256, SHA256, "actualJarSha256");
        if (!expected.equals(actual)) {
            throw new IllegalStateException("running certification archive digest does not match launcher expectation");
        }
        this.identity = new InstanceIdentity(
                "rg.agentTddCertificationInstance.v1",
                requireMatch(instanceNonce, NONCE, "instanceNonce"),
                requireMatch(repositoryCommit, COMMIT, "repositoryCommit"),
                actual);
        this.currentJarSha256 = Objects.requireNonNull(currentJarSha256, "currentJarSha256");
    }

    /**
     * Returns the payload-free identity tuple after re-hashing the current executable archive.
     * This makes both the launcher's before-turn and after-turn checks validate live bytes.
     */
    @GetMapping("/internal/agent-tdd/certification-instance")
    public InstanceIdentity identity() {
        String current = requireMatch(currentJarSha256.get(), SHA256, "currentJarSha256");
        if (!identity.jarSha256().equals(current)) {
            throw new IllegalStateException("running certification archive changed after startup");
        }
        return identity;
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " does not match its certification format");
        }
        return value;
    }

    /**
     * Computes the digest of the archive from which this Spring Boot application is executing.
     *
     * <p>A classes directory is deliberately rejected. Enabling the certification endpoint under
     * an IDE or a loose classpath therefore fails closed instead of claiming a packaged-runtime
     * identity.</p>
     */
    private static String runningArchiveSha256() {
        var source = new ApplicationHome(AgentTddCertificationInstanceController.class).getSource();
        if (source == null) {
            throw new IllegalStateException("running certification archive cannot be located");
        }
        return archiveSha256(source.toPath());
    }

    /** Computes a SHA-256 identity only for a regular, non-symbolic-link archive. */
    static String archiveSha256(Path archive) {
        Objects.requireNonNull(archive, "archive");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("running certification source is not a regular archive");
        }
        try (InputStream input = Files.newInputStream(archive)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("running certification archive cannot be hashed", failure);
        }
    }

    /** Immutable, non-business build identity returned only by the opt-in certification process. */
    public record InstanceIdentity(String schemaVersion,
                                   String instanceNonce,
                                   String repositoryCommit,
                                   String jarSha256) { }
}
