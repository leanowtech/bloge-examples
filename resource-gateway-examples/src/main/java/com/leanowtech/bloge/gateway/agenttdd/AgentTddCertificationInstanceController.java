package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exposes the immutable identity of an RG process spawned solely for Codex certification.
 *
 * <p>The endpoint does not exist in a normal deployment. The certification launcher enables it
 * with a fresh nonce after packaging a clean commit, then verifies the same tuple before and after
 * the Codex turn. This prevents a listener left by another checkout or build from being certified
 * as the current source tree.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "gateway.agent-tdd.certification-instance", name = "enabled",
        havingValue = "true")
public final class AgentTddCertificationInstanceController {
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32,128}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private final InstanceIdentity identity;

    /**
     * Creates the opt-in identity endpoint from values supplied by the owning launcher.
     *
     * @param instanceNonce fresh per-process nonce, never reused across certification runs
     * @param repositoryCommit exact clean commit packaged into the launched JAR
     * @param jarSha256 digest of the launched executable JAR
     */
    public AgentTddCertificationInstanceController(
            @Value("${gateway.agent-tdd.certification-instance.instance-nonce}") String instanceNonce,
            @Value("${gateway.agent-tdd.certification-instance.repository-commit}") String repositoryCommit,
            @Value("${gateway.agent-tdd.certification-instance.jar-sha256}") String jarSha256) {
        this.identity = new InstanceIdentity(
                "rg.agentTddCertificationInstance.v1",
                requireMatch(instanceNonce, NONCE, "instanceNonce"),
                requireMatch(repositoryCommit, COMMIT, "repositoryCommit"),
                requireMatch(jarSha256, SHA256, "jarSha256"));
    }

    /** Returns the payload-free identity tuple for launcher verification. */
    @GetMapping("/internal/agent-tdd/certification-instance")
    public InstanceIdentity identity() {
        return identity;
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " does not match its certification format");
        }
        return value;
    }

    /** Immutable, non-business build identity returned only by the opt-in certification process. */
    public record InstanceIdentity(String schemaVersion,
                                   String instanceNonce,
                                   String repositoryCommit,
                                   String jarSha256) { }
}
