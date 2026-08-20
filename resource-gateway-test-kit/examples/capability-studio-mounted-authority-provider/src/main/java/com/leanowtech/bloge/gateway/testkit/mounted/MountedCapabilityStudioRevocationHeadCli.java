package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import com.leanowtech.bloge.gateway.testkit.mounted.FilesystemDeploymentAdmissionAuthority.PreparedStore;
import com.leanowtech.bloge.gateway.testkit.mounted.FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate;
import com.leanowtech.bloge.gateway.testkit.mounted.FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdateResult;
import com.leanowtech.bloge.gateway.testkit.mounted.FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdateStatus;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deployment CLI for atomically advancing the mounted reference revocation head.
 *
 * <p>The CLI performs no network access and accepts only a pinned, strict local update document.
 * Exact retries return {@code ALREADY_CURRENT} with the same update-specific receipt
 * fingerprint.</p>
 */
public final class MountedCapabilityStudioRevocationHeadCli {
    /** Environment pin for the immutable store descriptor fingerprint. */
    public static final String STORE_DESCRIPTOR_PIN_ENV =
            "BLOGE_EXPECTED_EXECUTION_LEASE_STORE_DESCRIPTOR_FINGERPRINT";

    /** Environment pin for the exact raw revocation-head input bytes. */
    public static final String HEAD_INPUT_SHA256_ENV =
            "BLOGE_EXPECTED_REVOCATION_HEAD_INPUT_SHA256";

    /** Maximum accepted revocation-head update input size. */
    public static final int MAXIMUM_HEAD_INPUT_BYTES = 64 * 1024;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Set<String> FIELDS = Set.of("messageVersion",
            "storeDescriptorFingerprint", "registryRef", "revision",
            "snapshotFingerprint", "observedAt", "expiresAt",
            "predecessorHeadFingerprint", "headFingerprint");

    private MountedCapabilityStudioRevocationHeadCli() {
    }

    /**
     * Runs the strict local revocation-head update command.
     *
     * @param args exactly {@code --state-root PATH --head-input PATH}, in either order
     */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, Clock.systemUTC()));
    }

    static int run(String[] args, Map<String, String> environment,
            PrintStream output, Clock clock) {
        try {
            Arguments parsed = Arguments.parse(args);
            String descriptorPin = pin(environment, STORE_DESCRIPTOR_PIN_ENV);
            String inputPin = pin(environment, HEAD_INPUT_SHA256_ENV);
            byte[] bytes = FilesystemDeploymentAdmissionAuthority.readStrictFile(
                    parsed.headInput, MAXIMUM_HEAD_INPUT_BYTES);
            if (!inputPin.equals(sha256(bytes))) {
                return emit(output, "status=INVALID reason=REVOCATION_HEAD_UPDATE_FAILED", 2);
            }
            RevocationHeadUpdate update = parse(bytes);
            if (!descriptorPin.equals(update.storeDescriptorFingerprint())) {
                return emit(output, "status=INVALID reason=REVOCATION_HEAD_UPDATE_FAILED", 2);
            }
            PreparedStore store = FilesystemDeploymentAdmissionAuthority.openExistingStore(
                    parsed.stateRoot, descriptorPin);
            Instant trustedTime;
            try {
                trustedTime = clock.instant();
            } catch (RuntimeException unavailable) {
                return emit(output,
                        "status=BLOCKED reason=REVOCATION_HEAD_UPDATE_UNAVAILABLE", 2);
            }
            RevocationHeadUpdateResult result = store.advanceRevocationHead(
                    update, trustedTime);
            if (result.status() == RevocationHeadUpdateStatus.REJECTED) {
                return emit(output,
                        "status=REJECTED reason=REVOCATION_HEAD_UPDATE_REJECTED", 3);
            }
            if (result.status() == RevocationHeadUpdateStatus.UNAVAILABLE) {
                return emit(output,
                        "status=BLOCKED reason=REVOCATION_HEAD_UPDATE_UNAVAILABLE", 2);
            }
            return emit(output, "status=" + result.status()
                    + " storeDescriptorFingerprint=" + result.storeDescriptorFingerprint()
                    + " previousHeadFingerprint=" + result.previousHeadFingerprint()
                    + " newHeadFingerprint=" + result.newHeadFingerprint()
                    + " revision=" + result.revision()
                    + " updateReceiptFingerprint=" + result.updateReceiptFingerprint(), 0);
        } catch (DeploymentUnavailableException unavailable) {
            return emit(output,
                    "status=BLOCKED reason=REVOCATION_HEAD_UPDATE_UNAVAILABLE", 2);
        } catch (RuntimeException failure) {
            return emit(output, "status=INVALID reason=REVOCATION_HEAD_UPDATE_FAILED", 2);
        }
    }

    private static RevocationHeadUpdate parse(byte[] bytes) {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode node)) {
                throw new IllegalArgumentException("input is invalid");
            }
            Set<String> actual = new HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            if (!FIELDS.equals(actual)
                    || !FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_UPDATE_VERSION
                    .equals(text(node, "messageVersion"))) {
                throw new IllegalArgumentException("input is invalid");
            }
            long revision = exactPositiveLong(node, "revision");
            RevocationAuthoritySnapshot material = new RevocationAuthoritySnapshot(
                    text(node, "registryRef"), revision,
                    fingerprint(node, "snapshotFingerprint"),
                    Instant.parse(text(node, "observedAt")),
                    Instant.parse(text(node, "expiresAt")));
            return new RevocationHeadUpdate(
                    fingerprint(node, "storeDescriptorFingerprint"), material,
                    fingerprint(node, "predecessorHeadFingerprint"),
                    fingerprint(node, "headFingerprint"));
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new IllegalArgumentException("input is invalid");
        }
    }

    private static String pin(Map<String, String> environment, String name) {
        String value = environment == null ? null : environment.get(name);
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("pin is invalid");
        }
        return value;
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("input is invalid");
        }
        return value.textValue();
    }

    private static String fingerprint(ObjectNode node, String field) {
        String value = text(node, field);
        if (!FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("input is invalid");
        }
        return value;
    }

    private static long exactPositiveLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw new IllegalArgumentException("input is invalid");
        }
        return value.longValue();
    }

    private static int emit(PrintStream output, String line, int exit) {
        try {
            output.println(line);
            output.flush();
            return output.checkError() ? 2 : exit;
        } catch (RuntimeException failure) {
            return 2;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Arguments(Path stateRoot, Path headInput) {
        private static Arguments parse(String[] args) {
            if (args == null || args.length != 4) {
                throw new IllegalArgumentException("arguments are invalid");
            }
            Path state = null;
            Path input = null;
            for (int index = 0; index < args.length; index += 2) {
                if ("--state-root".equals(args[index]) && state == null) {
                    state = path(args[index + 1]);
                } else if ("--head-input".equals(args[index]) && input == null) {
                    input = path(args[index + 1]);
                } else {
                    throw new IllegalArgumentException("arguments are invalid");
                }
            }
            if (state == null || input == null) {
                throw new IllegalArgumentException("arguments are invalid");
            }
            return new Arguments(state, input);
        }

        private static Path path(String value) {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalArgumentException("path is invalid");
            }
            return path;
        }
    }
}
