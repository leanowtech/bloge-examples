import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Build-only generator for the Evidence CLI source/class identity resource. */
public final class CapabilityStudioEvidenceCliBuildIdentityGenerator {
    private static final String VERSION =
            "bloge.capability-studio.execution-lease-evidence-cli-build-identity.v1";
    private static final String SOURCE_PATH =
            "src/main/java/com/leanowtech/bloge/gateway/testkit/"
                    + "CapabilityStudioExecutionLeaseEvidenceCli.java";
    private static final String CLASS_NAME =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli";

    private CapabilityStudioEvidenceCliBuildIdentityGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("source, class, and output are required");
        }
        String sourceFingerprint = sha256(Files.readAllBytes(Path.of(args[0])));
        String classFingerprint = sha256(Files.readAllBytes(Path.of(args[1])));
        String canonical = json(sourceFingerprint, classFingerprint, null);
        String identityFingerprint = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        Path output = Path.of(args[2]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json(sourceFingerprint, classFingerprint,
                        identityFingerprint) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static String json(
            String sourceFingerprint,
            String classFingerprint,
            String identityFingerprint) {
        return "{\"messageVersion\":\"" + VERSION + "\""
                + ",\"sourcePath\":\"" + SOURCE_PATH + "\""
                + ",\"sourceFingerprint\":\"" + sourceFingerprint + "\""
                + ",\"className\":\"" + CLASS_NAME + "\""
                + ",\"classFingerprint\":\"" + classFingerprint + "\""
                + ",\"identityFingerprint\":"
                + (identityFingerprint == null ? "null" : "\"" + identityFingerprint + "\"")
                + "}";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
