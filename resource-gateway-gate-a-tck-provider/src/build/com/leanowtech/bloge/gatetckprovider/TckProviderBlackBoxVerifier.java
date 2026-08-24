package com.leanowtech.bloge.gatetckprovider;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import static com.leanowtech.bloge.gatetckprovider.TckProviderBlackBoxVerifier.ExitCode.*;

/**
 * JDK-only black-box TCK Provider verifier.
 *
 * <p>Launches {@code CapabilityStudioGateAChallengeCli} in an independent JVM with
 * the classpath order: Candidate JAR first, Provider JAR second.
 *
 * <p>Preflight (NOFOLLOW):
 * <ol>
 *   <li>providerJar   — regular file</li>
 *   <li>candidateJar  — regular file</li>
 *   <li>authority     — regular file</li>
 *   <li>workingDir    — directory</li>
 *   <li>oracle (5th arg) — regular file, size &gt; 0 and &le; 1 MiB</li>
 * </ol>
 *
 * <p>Subprocess: stdout and stderr each capped at 1 MiB.
 * Output-limit overrun → {@code OUTPUT_LIMIT_EXCEEDED}.
 * Timeout (30 s) → SIGTERM, 2 s grace, SIGKILL.
 *
 * <p>4-arg mode: {@code basicContainsCheck} validates the four fixed JSON fields.
 * 5-arg mode: exact byte-for-byte match of stdout vs oracle file bytes
 *             (oracle must itself be a single LF-terminated line).
 *
 * <p>Error codes are fixed strings; no dynamic values are embedded in them.
 * stdout / stderr content never appears in returned errors or printed output.
 */
public final class TckProviderBlackBoxVerifier {

    /* ── expected receipt fields ─────────────────────────────────────── */
    private static final String EXPECTED_ROLE            = "TCK_PROVIDER";
    private static final String EXPECTED_STATUS         = "READY";
    private static final String EXPECTED_MESSAGE_VERSION =
            "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1";
    private static final String EXPECTED_FIXTURE_SET_ID = "GATE_A_ROLE_BLACK_BOX_V1";

    /* ── subprocess limits ────────────────────────────────────────────── */
    private static final int  TIMEOUT_SECONDS = 30;
    private static final int  GRACE_SECONDS   = 2;
    private static final long ONE_MIB          = 1L << 20;   // 1 MiB
    private static final int  READ_CHUNK       = 8192;        // 8 KiB buffer

    /* ── usage (never echoed by this program) ───────────────────────── */
    private static final String USAGE =
            "USAGE: TckProviderBlackBoxVerifier <providerJar> <workingDir> <authority> <candidateJar> [expectedOracle]";

    /* ───────────────────────────────────────────────────────────────── */
    public static void main(String[] args) {
        if (args.length != 4 && args.length != 5) {
            System.exit(USAGE_EXIT);
        }

        Path providerJar;
        Path workingDir;
        Path authority;
        Path candidateJar;
        Path oracle;

        try {
            providerJar  = Path.of(args[0]);
            workingDir   = Path.of(args[1]);
            authority    = Path.of(args[2]);
            candidateJar = Path.of(args[3]);
            oracle       = args.length == 5 ? Path.of(args[4]) : null;
        } catch (RuntimeException e) {
            System.err.println("INVALID_PATH");
            System.exit(USAGE_EXIT);
            return; // unreachable
        }

        List<String> errors = verify(providerJar, workingDir, authority, candidateJar, oracle);

        if (!errors.isEmpty()) {
            for (String err : errors) {
                System.err.println(err);
            }
            System.exit(FAIL_EXIT);
        }
        System.out.println("PASS");
        System.exit(PASS_EXIT);
    }

    /* ───────────────────────────────────────────────────────────────── */
    static List<String> verify(Path providerJar, Path workingDir,
                              Path authority, Path candidateJar, Path oracle) {
        List<String> errors = new ArrayList<>();

        // pre-flight (NOFOLLOW)
        errors.addAll(preflightRegular(providerJar,   "PROVIDER_JAR"));
        errors.addAll(preflightRegular(candidateJar,  "CANDIDATE_JAR"));
        errors.addAll(preflightRegular(authority,     "AUTHORITY"));
        errors.addAll(preflightDirectory(workingDir,  "WORKING_DIR"));
        if (oracle != null) {
            errors.addAll(preflightOracle(oracle));
        }
        if (!errors.isEmpty()) return errors;

        // classpath: Candidate first, Provider second
        String classpath = candidateJar.toAbsolutePath().normalize().toString()
                + File.pathSeparator
                + providerJar.toAbsolutePath().normalize().toString();

        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<String> cmd = List.of(
                javaExe,
                "-cp", classpath,
                "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli",
                "--role-self-test",
                "--role", EXPECTED_ROLE,
                "--authority", authority.toAbsolutePath().normalize().toString(),
                "--artifact",  providerJar.toAbsolutePath().normalize().toString(),
                "--fixture-set-id", EXPECTED_FIXTURE_SET_ID
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();            // hermetic: no inherited env
        pb.redirectErrorStream(false);
        pb.directory(workingDir.toFile());   // workingDir already validated above

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return List.of("SUBPROCESS_START_FAILED");
        }

        // ── bounded capture ─────────────────────────────────────────────
        BoundedReader stdoutRd = new BoundedReader(ONE_MIB);
        BoundedReader stderrRd = new BoundedReader(ONE_MIB);

        Thread outThread = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdoutRd));
        Thread errThread = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderrRd));

        boolean timedOut;

        try {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                timedOut = true;
                // SIGTERM first
                process.destroy();
                try {
                    Thread.sleep(GRACE_SECONDS * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // then SIGKILL if still alive
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(GRACE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                timedOut = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return List.of("SUBPROCESS_INTERRUPTED");
        }

        // drain grace: give threads a moment to flush what they have
        try { outThread.join(GRACE_SECONDS * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        try { errThread.join(GRACE_SECONDS * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        if (timedOut) {
            return List.of("SUBPROCESS_TIMEOUT");
        }

        int exitCode = process.exitValue();

        // ── output-limit check ───────────────────────────────────────────
        if (stdoutRd.limitExceeded() || stderrRd.limitExceeded()) {
            errors.add("OUTPUT_LIMIT_EXCEEDED");
        }

        // ── assertions ────────────────────────────────────────────────
        if (exitCode != 0) {
            errors.add("NON_ZERO_EXIT");
        }

        if (stderrRd.size() > 0) {
            errors.add("STDERR_NOT_EMPTY");
        }

        byte[] stdoutBytes = stdoutRd.toByteArray();

        // ── UTF-8 canonical line check (always stable) ─────────────────
        // oracle read is always attempted regardless of prior errors
        List<String> lineErrors = new ArrayList<>();
        String stdoutStr = canonicalLineCheck(stdoutBytes, lineErrors);
        for (String le : lineErrors) {
            errors.add(le);
        }

        if (oracle != null) {
            // exact byte-match mode
            if (stdoutStr != null) {
                byte[] oracleBytes = readOracleBounded(oracle, errors);
                if (oracleBytes == null) {
                    // read or consistency failure already recorded in errors
                } else if (!Arrays.equals(stdoutBytes, oracleBytes)) {
                    errors.add("ORACLE_MISMATCH");
                }
            }
        } else {
            // 4-arg smoke mode: basic contains-check
            if (stdoutStr != null) {
                errors.addAll(basicContainsCheck(stdoutStr));
            }
        }

        return errors;
    }

    /* ── oracle bounded read (NOFOLLOW) ──────────────────────────────── */
    /**
     * Reads oracle file with a stable bounded NOFOLLOW read.
     * Verifies BasicFileAttributes consistency (regular, size, lastModified, fileKey)
     * before and after the read.  Fills errors in the provided list and returns null
     * on any failure.  Returns the bytes on success.
     */
    private static byte[] readOracleBounded(Path oracle, List<String> errors) {
        // ── pre-read attributes ────────────────────────────────────────
        BasicFileAttributes preAttrs;
        try {
            preAttrs = Files.readAttributes(oracle, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            errors.add("ORACLE_NOT_FOUND");
            return null;
        } catch (IOException e) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }

        if (!preAttrs.isRegularFile() || Files.isSymbolicLink(oracle)) {
            errors.add("ORACLE_NOT_REGULAR_FILE");
            return null;
        }

        long preSize = preAttrs.size();
        if (preSize == 0 || preSize > ONE_MIB) {
            errors.add("ORACLE_TOO_LARGE");
            return null;
        }

        Object preKey   = preAttrs.fileKey();
        long   preMtime = preAttrs.lastModifiedTime().toMillis();

        // ── bounded read ────────────────────────────────────────────────
        byte[] result;
        try {
            result = boundedReadFile(oracle, ONE_MIB);
        } catch (IOException e) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }
        if (result == null) {
            errors.add("ORACLE_TOO_LARGE");
            return null;
        }

        // ── post-read attributes ────────────────────────────────────────
        BasicFileAttributes postAttrs;
        try {
            postAttrs = Files.readAttributes(oracle, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }

        // consistency: still a regular file, same size, same identity
        if (!postAttrs.isRegularFile() || Files.isSymbolicLink(oracle)) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }
        if (postAttrs.size() != preSize) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }
        Object postKey = postAttrs.fileKey();
        if (preKey != null && postKey != null && !preKey.equals(postKey)) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }
        long postMtime = postAttrs.lastModifiedTime().toMillis();
        if (preMtime != postMtime) {
            errors.add("ORACLE_READ_FAILED");
            return null;
        }

        // ── oracle itself must be a canonical single-LF line ───────────
        List<String> oracleLineErrors = new ArrayList<>();
        String decoded = canonicalLineCheck(result, oracleLineErrors);
        if (decoded == null) {
            // oracle itself is malformed — ORACLE_INVALID already in oracleLineErrors
            for (String le : oracleLineErrors) {
                // re-label as ORACLE_INVALID for clarity
                if (le.startsWith("STDOUT_")) {
                    errors.add("ORACLE_INVALID");
                } else {
                    errors.add(le);
                }
            }
            return null;
        }

        return result;
    }

    /**
     * Bounded file read up to {@code limit} bytes via NIO FileChannel (NOFOLLOW).
     * Returns null if the file is larger than limit.
     */
    private static byte[] boundedReadFile(Path p, long limit) throws IOException {
        FileChannel ch = FileChannel.open(p,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long size = ch.size();
            if (size > limit) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            int n = 0;
            while (n < size) {
                int r = ch.read(buf);
                if (r == -1) break;
                n += r;
            }
            buf.flip();
            byte[] result = new byte[n];
            buf.get(result);
            return result;
        } finally {
            ch.close();
        }
    }

    /* ── canonical line check ────────────────────────────────────────── */
    /**
     * Validates that {@code bytes} represent exactly one UTF-8 line:
     * ends with LF, contains no CR, LF does not appear before the final byte.
     * Uses CharsetDecoder with REPORT (not REPLACE) for malformed UTF-8.
     *
     * Returns the decoded string on success, or null on failure (errors added).
     * Error codes:
     *   STDOUT_EMPTY          — zero bytes
     *   STDOUT_NO_TRAILING_LF — last byte is not LF
     *   STDOUT_MULTIPLE_LINES — CR or LF found before the final byte
     *   STDOUT_INVALID_UTF8   — decoder rejects malformed input
     */
    static String canonicalLineCheck(byte[] bytes, List<String> errors) {
        if (bytes.length == 0) {
            errors.add("STDOUT_EMPTY");
            return null;
        }

        // must end with LF
        if (bytes[bytes.length - 1] != 0x0A) {
            errors.add("STDOUT_NO_TRAILING_LF");
            return null;
        }

        // must contain no CR and no LF before the last byte
        for (int i = 0; i < bytes.length - 1; i++) {
            byte b = bytes[i];
            if (b == 0x0D || b == 0x0A) {
                errors.add("STDOUT_MULTIPLE_LINES");
                return null;
            }
        }

        // strict UTF-8 decode — no substitution
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            // decode up to but not including the trailing LF
            return decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1)).toString();
        } catch (CharacterCodingException e) {
            errors.add("STDOUT_INVALID_UTF8");
            return null;
        }
    }

    /* ── basic contains check (4-arg mode) ─────────────────────────── */
    /**
     * Development smoke check: stdout (not trimmed) must contain each of the
     * four fixed JSON field pairs.
     */
    static List<String> basicContainsCheck(String stdout) {
        List<String> errors = new ArrayList<>();
        if (!stdout.contains("\"role\":\"" + EXPECTED_ROLE + "\"")) {
            errors.add("RECEIPT_WRONG_ROLE");
        }
        if (!stdout.contains("\"status\":\"" + EXPECTED_STATUS + "\"")) {
            errors.add("RECEIPT_WRONG_STATUS");
        }
        if (!stdout.contains("\"" + EXPECTED_MESSAGE_VERSION + "\"")) {
            errors.add("RECEIPT_WRONG_MESSAGE_VERSION");
        }
        if (!stdout.contains("\"fixtureSetId\":\"" + EXPECTED_FIXTURE_SET_ID + "\"")) {
            errors.add("RECEIPT_WRONG_FIXTURE_SET_ID");
        }
        return errors;
    }

    /* ── pre-flight helpers (NOFOLLOW) ─────────────────────────────── */
    private static List<String> preflightRegular(Path p, String label) {
        try {
            BasicFileAttributes attr = Files.readAttributes(
                    p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attr.isRegularFile() || attr.isSymbolicLink()) {
                return List.of(label + "_NOT_REGULAR_FILE");
            }
        } catch (NoSuchFileException e) {
            return List.of(label + "_NOT_FOUND");
        } catch (IOException e) {
            return List.of(label + "_UNREADABLE");
        }
        return List.of();
    }

    private static List<String> preflightDirectory(Path p, String label) {
        try {
            BasicFileAttributes attr = Files.readAttributes(
                    p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attr.isDirectory() || attr.isSymbolicLink()) {
                return List.of(label + "_NOT_DIRECTORY");
            }
        } catch (NoSuchFileException e) {
            return List.of(label + "_NOT_FOUND");
        } catch (IOException e) {
            return List.of(label + "_UNREADABLE");
        }
        return List.of();
    }

    /**
     * Pre-flight for the oracle file: regular, size &gt; 0 and &le; 1 MiB.
     */
    private static List<String> preflightOracle(Path oracle) {
        try {
            BasicFileAttributes attr = Files.readAttributes(
                    oracle, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attr.isRegularFile() || Files.isSymbolicLink(oracle)) {
                return List.of("ORACLE_NOT_REGULAR_FILE");
            }
            if (attr.size() == 0 || attr.size() > ONE_MIB) {
                return List.of("ORACLE_TOO_LARGE");
            }
        } catch (NoSuchFileException e) {
            return List.of("ORACLE_NOT_FOUND");
        } catch (IOException e) {
            return List.of("ORACLE_UNREADABLE");
        }
        return List.of();
    }

    /* ── bounded byte reader ────────────────────────────────────────── */
    private static final class BoundedReader {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private final long limit;
        private boolean exceeded;

        BoundedReader(long limit) { this.limit = limit; }

        void appendChunk(byte[] chunk, int len) {
            if (exceeded) return;
            long needed    = (long) len;
            long available = limit - baos.size();
            if (needed > available) {
                baos.write(chunk, 0, (int) available);
                exceeded = true;
            } else {
                baos.write(chunk, 0, len);
            }
        }

        boolean limitExceeded() { return exceeded; }
        int  size()            { return baos.size(); }
        byte[] toByteArray()   { return baos.toByteArray(); }
    }

    /* ── drain a stream into a bounded reader ──────────────────────── */
    /**
     * Reads from {@code in} and appends to {@code out}.
     * When the limit is exceeded the reader continues reading and discarding
     * until EOF so the subprocess pipe is never blocked.
     */
    private static void drain(InputStream in, BoundedReader out) {
        byte[] buf = new byte[READ_CHUNK];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.appendChunk(buf, n);
                // do NOT break on exceeded — must consume pipe to avoid deadlock
            }
        } catch (IOException ignored) {
            // stream closed — expected on process exit
        }
    }

    /* ── exit codes ─────────────────────────────────────────────────── */
    static final class ExitCode {
        static final int USAGE_EXIT = 64;   // EX_USAGE
        static final int FAIL_EXIT  = 1;    // generic failure
        static final int PASS_EXIT  = 0;    // all checks passed
    }
}
