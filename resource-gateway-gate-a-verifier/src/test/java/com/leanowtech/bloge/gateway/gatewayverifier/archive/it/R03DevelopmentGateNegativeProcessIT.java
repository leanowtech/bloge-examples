package com.leanowtech.bloge.gateway.gatewayverifier.archive.it;

import com.leanowtech.bloge.gateway.gatewayverifier.archive.DevelopmentPredecessorBinding;
import com.leanowtech.bloge.gateway.gatewayverifier.archive.DevelopmentPredecessorBindingVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D7-T4 negative integration tests: R03DevelopmentGateProbe child-JVM.
 * Five child-JVM cases covering: missing binding, binding fp mutate,
 * authority fp mutate, symlink provider, 17MiB provider.
 */
class R03DevelopmentGateNegativeProcessIT {

    private static byte[] RAW_BINDING_BYTES;
    private static DevelopmentPredecessorBinding VERIFIED_BINDING;
    private static Path SYSTEM_AUTHORITY_PATH;
    private static Path SYSTEM_REPO_ROOT;

    @BeforeAll
    static void setupAll() throws Exception {
        String bindingPathStr = System.getProperty("gate.a.binding.path");
        assertNotNull(bindingPathStr, "gate.a.binding.path system property required");
        String authorityPathStr = System.getProperty("gate.a.authority.path");
        assertNotNull(authorityPathStr, "gate.a.authority.path system property required");
        String repoRootStr = System.getProperty("gate.a.repo.root");
        assertNotNull(repoRootStr, "gate.a.repo.root system property required");

        SYSTEM_AUTHORITY_PATH = Paths.get(authorityPathStr);
        SYSTEM_REPO_ROOT = Paths.get(repoRootStr);

        RAW_BINDING_BYTES = Files.readAllBytes(Paths.get(bindingPathStr));

        DevelopmentPredecessorBindingVerifier verifier =
                new DevelopmentPredecessorBindingVerifier();
        VERIFIED_BINDING = verifier.verify(
                Paths.get(bindingPathStr),
                SYSTEM_AUTHORITY_PATH,
                SYSTEM_REPO_ROOT);
    }

    // -------------------------------------------------------------------------
    // Child-JVM launcher
    // -------------------------------------------------------------------------

    private static final int TIMEOUT_SEC = 30;
    private static final int BOUND = 65_536;

    private record ProbeResult(int exitCode, String stdout, String stderr, boolean overflow) {}

    private ProbeResult launchProbe(Path bindingFile, Path authorityFile, Path repoRoot) throws Exception {
        String cpProp = System.getProperty("surefire.test.class.path", "");
        String classPath = (!cpProp.isBlank()) ? cpProp : System.getProperty("java.class.path", "");
        assertTrue(classPath.contains("test-classes"),
                "classpath must contain test-classes: " + classPath);

        ProcessBuilder pb = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath,
                "com.leanowtech.bloge.gateway.gatewayverifier.archive.R03DevelopmentGateProbe",
                bindingFile.toAbsolutePath().toString(),
                authorityFile.toAbsolutePath().toString(),
                repoRoot.toAbsolutePath().toString());

        pb.redirectErrorStream(false);
        Process proc = pb.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream(BOUND + 1);
        ByteArrayOutputStream err = new ByteArrayOutputStream(BOUND + 1);
        AtomicBoolean overflow = new AtomicBoolean(false);
        AtomicReference<IOException> readerErr = new AtomicReference<>();

        Thread outReader = startReader(proc, proc.getInputStream(), out, overflow, readerErr);
        Thread errReader = startReader(proc, proc.getErrorStream(), err, overflow, readerErr);
        outReader.start();
        errReader.start();

        boolean done = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
        }

        int exitCode = proc.exitValue();

        outReader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC));
        errReader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC));

        if (readerErr.get() != null && !overflow.get()) {
            fail("Reader error: " + readerErr.get().getMessage());
        }

        return new ProbeResult(exitCode, out.toString(StandardCharsets.US_ASCII),
                err.toString(StandardCharsets.US_ASCII), overflow.get());
    }

    private Thread startReader(Process proc, InputStream in, ByteArrayOutputStream out,
            AtomicBoolean overflow, AtomicReference<IOException> readerErr) {
        return new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (out.size() + n > BOUND) {
                        out.write(buf, 0, BOUND - out.size());
                        overflow.set(true);
                        proc.destroyForcibly();
                        break;
                    }
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                if (!overflow.get()) {
                    readerErr.set(e);
                }
            }
        });
    }

    private static int countDiffs(byte[] a, byte[] b) {
        if (a.length != b.length) return -1;
        int diffs = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) diffs++;
        }
        return diffs;
    }

    private static int findHexBytePos(byte[] data, byte[] marker) {
        outer:
        for (int i = 0; i <= data.length - marker.length - 1; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (data[i + j] != marker[j]) continue outer;
            }
            return i + marker.length;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // T1: binding file missing -> R03-BINDING-MISSING
    // -------------------------------------------------------------------------

    @Test
    void t1_missingBinding(@TempDir Path tempDir) throws Exception {
        Path noFile = tempDir.resolve("nonexistent.json");

        ProbeResult r = launchProbe(noFile, SYSTEM_AUTHORITY_PATH, SYSTEM_REPO_ROOT);
        assertEquals(1, r.exitCode(), () -> "stderr: " + r.stderr());
        assertEquals("R03-BINDING-MISSING", r.stderr.trim());
        assertTrue(r.stdout.isEmpty());
    }

    // -------------------------------------------------------------------------
    // T2: binding mutated: one hex byte in fingerprint replaced with another
    //     valid lowercase hex; bytes differ -> R03-BINDING-FP-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    void t2_bindingMutate(@TempDir Path tempDir) throws Exception {
        byte[] mutated = RAW_BINDING_BYTES.clone();
        byte[] marker = "\"bindingFingerprint\":\"sha256:".getBytes(StandardCharsets.US_ASCII);
        int pos = findHexBytePos(mutated, marker);
        assertTrue(pos > 0, "fingerprint marker not found");
        assertTrue(mutated[pos] >= '0' && mutated[pos] <= '9'
                || mutated[pos] >= 'a' && mutated[pos] <= 'f',
                "original hex byte expected: " + (char) mutated[pos]);
        byte orig = mutated[pos];
        mutated[pos] = (orig == '0') ? (byte) '1' : (byte) '0';

        Path badFile = tempDir.resolve("bad.json");
        Files.write(badFile, mutated);

        ProbeResult r = launchProbe(badFile, SYSTEM_AUTHORITY_PATH, SYSTEM_REPO_ROOT);
        assertEquals(1, r.exitCode(), () -> "stderr: " + r.stderr());
        assertEquals("R03-BINDING-FP-MISMATCH", r.stderr.trim());
        assertTrue(r.stdout.isEmpty());
        assertEquals(RAW_BINDING_BYTES.length, mutated.length, "binding size preserved");
        assertEquals(mutated[mutated.length - 1], (byte) '\n', "trailing LF preserved");
        assertEquals(1, countDiffs(RAW_BINDING_BYTES, mutated), "exactly one char changed");
    }

    // -------------------------------------------------------------------------
    // T3: authority mutated: toggle one ASCII byte (mid-file space), bytes differ
    //     but file remains readable; original raw binding -> R03-AUTHORITY-FP-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    void t3_authorityMutate(@TempDir Path tempDir) throws Exception {
        byte[] authBytes = Files.readAllBytes(SYSTEM_AUTHORITY_PATH);
        int pos = authBytes.length / 2;
        authBytes[pos] = (byte) (authBytes[pos] ^ 0x01);

        Path badAuth = tempDir.resolve("bad-auth.json");
        Files.write(badAuth, authBytes);

        Path validBinding = tempDir.resolve("valid-binding.json");
        Files.write(validBinding, RAW_BINDING_BYTES);

        ProbeResult r = launchProbe(validBinding, badAuth, SYSTEM_REPO_ROOT);
        assertEquals(1, r.exitCode(), () -> "stderr: " + r.stderr());
        assertEquals("R03-AUTHORITY-FP-MISMATCH", r.stderr.trim());
        assertTrue(r.stdout.isEmpty());
    }

    // -------------------------------------------------------------------------
    // T4: external file with provider bytes; leaf symlink points to external;
    //     original raw binding unchanged -> R03-READ-UNREADABLE
    // -------------------------------------------------------------------------

    @Test
    void t4_symlinkProvider(@TempDir Path tempDir) throws Exception {
        Path tempRepo = tempDir.resolve("repo");
        Files.createDirectories(tempRepo);
        Path external = tempDir.resolve("external-provider.bin");
        Files.write(external, VERIFIED_BINDING.providerBytes());
        Path leaf = tempRepo.resolve(VERIFIED_BINDING.providerArtifact().path());
        Files.createDirectories(leaf.getParent());
        Files.deleteIfExists(leaf);
        Files.createSymbolicLink(leaf, external);
        Files.write(tempDir.resolve("binding.json"), RAW_BINDING_BYTES);

        ProbeResult r = launchProbe(
                tempDir.resolve("binding.json"), SYSTEM_AUTHORITY_PATH, tempRepo);
        assertEquals(1, r.exitCode(), () -> "stderr: " + r.stderr());
        assertEquals("R03-READ-UNREADABLE", r.stderr.trim());
        assertTrue(r.stdout.isEmpty());
    }

    // -------------------------------------------------------------------------
    // T5: temp repo mirrors same path with 17MiB regular leaf;
    //     original raw binding unchanged -> R03-READ-OVERSIZE
    // -------------------------------------------------------------------------

    @Test
    void t5_17MiBProvider(@TempDir Path tempDir) throws Exception {
        Path tempRepo = tempDir.resolve("repo");
        Files.createDirectories(tempRepo);

        String provRelativePath = VERIFIED_BINDING.providerArtifact().path();
        Path largeFile = tempRepo.resolve(provRelativePath);
        Files.createDirectories(largeFile.getParent());

        byte[] large = new byte[17 * 1_048_576];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i & 0xFF);
        }
        Files.write(largeFile, large);

        Path bindingCopy = tempDir.resolve("binding.json");
        Files.write(bindingCopy, RAW_BINDING_BYTES);

        ProbeResult r = launchProbe(bindingCopy, SYSTEM_AUTHORITY_PATH, tempRepo);
        assertEquals(1, r.exitCode(), () -> "stderr: " + r.stderr());
        assertEquals("R03-READ-OVERSIZE", r.stderr.trim());
        assertTrue(r.stdout.isEmpty());
    }
}
