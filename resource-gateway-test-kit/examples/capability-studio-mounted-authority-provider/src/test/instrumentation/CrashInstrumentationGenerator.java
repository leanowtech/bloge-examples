import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically creates test-only crash-instrumented production source copies. */
public final class CrashInstrumentationGenerator {
    private static final String CLI_RELATIVE =
            "src/main/java/com/leanowtech/bloge/gateway/testkit/"
                    + "CapabilityStudioExecutionLeaseEvidenceCli.java";
    private static final String PROVIDER_RELATIVE =
            "src/main/java/com/leanowtech/bloge/gateway/testkit/mounted/"
                    + "FilesystemDeploymentAdmissionAuthority.java";
    private static final String CLI =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli";
    private static final String PUBLICATION = CLI + "$TranscriptPublication";
    private static final String PROVIDER =
            "com.leanowtech.bloge.gateway.testkit.mounted."
                    + "FilesystemDeploymentAdmissionAuthority";
    private static final String STORE = PROVIDER + "$PreparedStore";
    private static final String HIT =
            "com.leanowtech.bloge.gateway.testkit.instrumentation.CrashCheckpoint.hit";
    private static final String LOCK_MISS =
            "com.leanowtech.bloge.gateway.testkit.instrumentation.CrashCheckpoint."
                    + "publicationFileLockMiss";
    private static final List<String> POINT_ORDER = List.of(
            "PRE_OWNER",
            "OWNER_SOURCE_FORCED",
            "WRAPPER_DURABLE",
            "OWNER_DURABLE",
            "BEFORE_SOURCE_FORCED",
            "BEFORE_DURABLE",
            "PRE_LEASE",
            "STATE_BEFORE_CHECKPOINT",
            "CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
            "COMMITTED_SOURCE_FORCED",
            "COMMITTED_DURABLE",
            "MANIFEST_DURABLE_BEFORE_FINAL_COMMIT",
            "FINAL_COMMIT_SOURCE_FORCED",
            "FINAL_COMMIT_INSTALLED",
            "FINAL_COMMIT_DURABLE",
            "FINAL_INSTALLED",
            "FINAL_BEFORE_STDOUT");
    private static final List<String> SEMANTIC_WINDOW_ORDER = List.of(
            "PRE_OWNER", "OWNER_PUBLICATION", "WRAPPER_PUBLICATION",
            "BEFORE_PUBLICATION", "PRE_LEASE", "STATE_TRANSITION_PRE_CHECKPOINT",
            "CHECKPOINT_POST_COMMIT", "COMMITTED_TRANSCRIPT_PUBLICATION",
            "MANIFEST_PUBLICATION", "FINAL_COMMIT_SOURCE", "FINAL_COMMIT_INSTALL",
            "FINAL_COMMIT_DURABILITY", "FINAL_TRANSCRIPT_INSTALL", "PRE_STDOUT");
    private static final Map<String, String> SEMANTIC_WINDOW_BY_POINT = Map.ofEntries(
            Map.entry("PRE_OWNER", "PRE_OWNER"),
            Map.entry("OWNER_SOURCE_FORCED", "OWNER_PUBLICATION"),
            Map.entry("WRAPPER_DURABLE", "WRAPPER_PUBLICATION"),
            Map.entry("OWNER_DURABLE", "OWNER_PUBLICATION"),
            Map.entry("BEFORE_SOURCE_FORCED", "BEFORE_PUBLICATION"),
            Map.entry("BEFORE_DURABLE", "BEFORE_PUBLICATION"),
            Map.entry("PRE_LEASE", "PRE_LEASE"),
            Map.entry("STATE_BEFORE_CHECKPOINT", "STATE_TRANSITION_PRE_CHECKPOINT"),
            Map.entry("CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
                    "CHECKPOINT_POST_COMMIT"),
            Map.entry("COMMITTED_SOURCE_FORCED", "COMMITTED_TRANSCRIPT_PUBLICATION"),
            Map.entry("COMMITTED_DURABLE", "COMMITTED_TRANSCRIPT_PUBLICATION"),
            Map.entry("MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "MANIFEST_PUBLICATION"),
            Map.entry("FINAL_COMMIT_SOURCE_FORCED", "FINAL_COMMIT_SOURCE"),
            Map.entry("FINAL_COMMIT_INSTALLED", "FINAL_COMMIT_INSTALL"),
            Map.entry("FINAL_COMMIT_DURABLE", "FINAL_COMMIT_DURABILITY"),
            Map.entry("FINAL_INSTALLED", "FINAL_TRANSCRIPT_INSTALL"),
            Map.entry("FINAL_BEFORE_STDOUT", "PRE_STDOUT"));

    private CrashInstrumentationGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || (!"generate".equals(args[0])
                && !"finalize".equals(args[0]))) {
            throw new IllegalArgumentException(
                    "mode, module, output, and manifest/classes are required");
        }
        Path module = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path testKit = module.resolve("../..").normalize();
        List<Anchor> cliAnchors = cliAnchors();
        List<Anchor> providerAnchors = providerAnchors();
        List<ObservationHook> cliHooks = cliObservationHooks();
        requirePointOrder(cliAnchors, providerAnchors);

        Generated cli = generate(testKit.resolve(CLI_RELATIVE), output.resolve(CLI_RELATIVE),
                CLI_RELATIVE, cliAnchors, cliHooks, cliSourceOrder());
        Generated provider = generate(module.resolve(PROVIDER_RELATIVE),
                output.resolve(PROVIDER_RELATIVE), PROVIDER_RELATIVE, providerAnchors, List.of(),
                providerSourceOrder());
        Path manifest = output.resolve("META-INF/bloge/crash-instrumentation-v1.json");
        List<CompiledClass> classes = "finalize".equals(args[0])
                ? compiledClasses(Path.of(args[3]).toAbsolutePath().normalize(),
                cliAnchors, providerAnchors, cliHooks)
                : List.of();
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest,
                manifest(List.of(cli, provider), cliAnchors, providerAnchors, cliHooks, classes),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static List<Anchor> cliAnchors() {
        return List.of(
                anchor("PRE_OWNER", PUBLICATION, "before ensureOwner",
                        "                assertPublicationLease();\n"
                                + "                Owner owner = ensureOwner(attempt);\n"
                                + "                if (Files.exists(output, "
                                + "LinkOption.NOFOLLOW_LINKS)) {",
                        "                assertPublicationLease();\n"
                                + hit("PRE_OWNER", 16)
                                + "                Owner owner = ensureOwner(attempt);\n"
                                + "                if (Files.exists(output, "
                                + "LinkOption.NOFOLLOW_LINKS)) {"),
                anchor("OWNER_SOURCE_FORCED", PUBLICATION, "owner claim durable",
                        "                publishOwned(claim, ownerBytes(owner, "
                                + "owner.fingerprint));\n"
                                + "            }\n"
                                + "            if (!transactionExists) {",
                        "                publishOwned(claim, ownerBytes(owner, "
                                + "owner.fingerprint));\n"
                                + hit("OWNER_SOURCE_FORCED", 16)
                                + "            }\n"
                                + "            if (!transactionExists) {"),
                anchor("WRAPPER_DURABLE", PUBLICATION, "wrapper parent forced",
                        "                    Files.createDirectory(transaction,\n"
                                + "                            PosixFilePermissions."
                                + "asFileAttribute(\n"
                                + "                                    PosixFilePermissions."
                                + "fromString(\"rwx------\")));\n"
                                + "                    forceDirectory(parent);\n"
                                + "                } catch (FileAlreadyExistsException raced) {",
                        "                    Files.createDirectory(transaction,\n"
                                + "                            PosixFilePermissions."
                                + "asFileAttribute(\n"
                                + "                                    PosixFilePermissions."
                                + "fromString(\"rwx------\")));\n"
                                + "                    forceDirectory(parent);\n"
                                + hit("WRAPPER_DURABLE", 20)
                                + "                } catch (FileAlreadyExistsException raced) {"),
                anchor("OWNER_DURABLE", PUBLICATION, "owner link parents forced",
                        "                forceFile(target);\n"
                                + "                forceDirectory(transaction);\n"
                                + "                forceDirectory(parent);\n"
                                + "            }\n"
                                + "            Owner exact = requireOwner(",
                        "                forceFile(target);\n"
                                + "                forceDirectory(transaction);\n"
                                + "                forceDirectory(parent);\n"
                                + hit("OWNER_DURABLE", 16)
                                + "            }\n"
                                + "            Owner exact = requireOwner("),
                anchor("BEFORE_SOURCE_FORCED", PUBLICATION, "before source forced",
                        "                prepareOwnedSource(part(before), bytes);",
                        "                prepareOwnedSource(part(before), bytes);\n"
                                + hit("BEFORE_SOURCE_FORCED", 16)),
                anchor("BEFORE_DURABLE", PUBLICATION, "before target durable",
                        "                installOwnedFile(part(before), before, bytes);\n"
                                + "                return current;",
                        "                installOwnedFile(part(before), before, bytes);\n"
                                + hit("BEFORE_DURABLE", 16)
                                + "                return current;"),
                anchor("COMMITTED_SOURCE_FORCED", PUBLICATION,
                        "committed transcript source forced",
                        "                    prepareOwnedSource(committedPart, "
                                + "transcript.bytes());",
                        "                    prepareOwnedSource(committedPart, "
                                + "transcript.bytes());\n"
                                + hit("COMMITTED_SOURCE_FORCED", 20)),
                anchor("COMMITTED_DURABLE", PUBLICATION,
                        "committed transcript target durable",
                        "                    installOwnedFile(committedPart, committed, "
                                + "transcript.bytes());\n"
                                + "                }",
                        "                    installOwnedFile(committedPart, committed, "
                                + "transcript.bytes());\n"
                                + hit("COMMITTED_DURABLE", 20)
                                + "                }"),
                anchor("MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", PUBLICATION,
                        "manifest durable before final commitment",
                        "                byte[] manifestBytes = readStrict(\n"
                                + "                        manifestPath, "
                                + "MAXIMUM_COMMIT_MANIFEST_BYTES);\n"
                                + "                FinalCommit commitment = "
                                + "FinalCommit.create(owner,",
                        "                byte[] manifestBytes = readStrict(\n"
                                + "                        manifestPath, "
                                + "MAXIMUM_COMMIT_MANIFEST_BYTES);\n"
                                + hit("MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", 16)
                                + "                FinalCommit commitment = "
                                + "FinalCommit.create(owner,"),
                anchor("FINAL_COMMIT_SOURCE_FORCED", CLI,
                        "final commit source chmod and file force",
                        "            Files.setPosixFilePermissions(source,\n"
                                + "                    PosixFilePermissions."
                                + "fromString(\"r--------\"));\n"
                                + "            channel.force(true);\n"
                                + "        }",
                        "            Files.setPosixFilePermissions(source,\n"
                                + "                    PosixFilePermissions."
                                + "fromString(\"r--------\"));\n"
                                + "            channel.force(true);\n"
                                + "            if (source.getFileName().toString().equals("
                                + "\".\" + FINAL_COMMIT_FILE + \".part\")) {\n"
                                + hit("FINAL_COMMIT_SOURCE_FORCED", 16)
                                + "            }\n"
                                + "        }"),
                anchor("FINAL_COMMIT_INSTALLED", CLI,
                        "final commit target linked before force",
                        "        try {\n"
                                + "            Files.createLink(target, source);\n"
                                + "        } catch (FileAlreadyExistsException raced) {\n"
                                + "            installOwnedFile(source, target, bytes);",
                        "        try {\n"
                                + "            Files.createLink(target, source);\n"
                                + "            if (target.getFileName().toString().equals("
                                + "FINAL_COMMIT_FILE)) {\n"
                                + hit("FINAL_COMMIT_INSTALLED", 16)
                                + "            }\n"
                                + "        } catch (FileAlreadyExistsException raced) {\n"
                                + "            installOwnedFile(source, target, bytes);"),
                anchor("FINAL_COMMIT_DURABLE", CLI,
                        "final commit source unlinked and parent forced",
                        "        requireExact(source, bytes, 2L);\n"
                                + "        requireExact(target, bytes, 2L);\n"
                                + "        forceFile(target);\n"
                                + "        forceDirectory(target.getParent());\n"
                                + "        Files.delete(source);\n"
                                + "        forceDirectory(source.getParent());\n"
                                + "        requireExact(target, bytes, 1L);\n"
                                + "    }",
                        "        requireExact(source, bytes, 2L);\n"
                                + "        requireExact(target, bytes, 2L);\n"
                                + "        forceFile(target);\n"
                                + "        forceDirectory(target.getParent());\n"
                                + "        Files.delete(source);\n"
                                + "        forceDirectory(source.getParent());\n"
                                + "        if (target.getFileName().toString().equals("
                                + "FINAL_COMMIT_FILE)) {\n"
                                + hit("FINAL_COMMIT_DURABLE", 12)
                                + "        }\n"
                                + "        requireExact(target, bytes, 1L);\n"
                                + "    }"),
                anchor("FINAL_INSTALLED", PUBLICATION, "retained final transcript durable",
                        "            return new Result(existed ? "
                                + "EvidencePublicationStatus.RECOVERED",
                        hit("FINAL_INSTALLED", 12)
                                + "            return new Result(existed ? "
                                + "EvidencePublicationStatus.RECOVERED"),
                anchor("FINAL_BEFORE_STDOUT", CLI, "accepted line not yet emitted",
                        "        String success = \"ACCEPTED status=ACCEPTED "
                                + "evidencePublicationStatus=\"",
                        hit("FINAL_BEFORE_STDOUT", 8)
                                + "        String success = \"ACCEPTED status=ACCEPTED "
                                + "evidencePublicationStatus=\""));
    }

    private static List<Anchor> providerAnchors() {
        return List.of(
                anchor("PRE_LEASE", PROVIDER, "before lease commit mutation",
                        "                CommitOutcome outcome = commitInternal("
                                + "state, attempt.request(), true,",
                        hit("PRE_LEASE", 16)
                                + "                CommitOutcome outcome = commitInternal("
                                + "state, attempt.request(), true,"),
                anchor("STATE_BEFORE_CHECKPOINT", STORE,
                        "state durable before checkpoint update",
                        "                writeState(state);\n"
                                + "                writeCheckpoint(Checkpoint.forSnapshot(",
                        "                writeState(state);\n"
                                + hit("STATE_BEFORE_CHECKPOINT", 16)
                                + "                writeCheckpoint(Checkpoint.forSnapshot("),
                anchor("CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE", PROVIDER,
                        "checkpoint parent forced before transition install",
                        "        store.writeStateAndCheckpoint(updated);\n"
                                + "        if (transition != null) {",
                        "        store.writeStateAndCheckpoint(updated);\n"
                                + hit("CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE", 8)
                                + "        if (transition != null) {"));
    }

    private static List<ObservationHook> cliObservationHooks() {
        return List.of(new ObservationHook(
                "PUBLICATION_FILE_LOCK_MISS",
                CLI,
                "exclusive publication FileLock tryLock miss",
                "                    return lock;\n"
                        + "                }\n"
                        + "            } catch (OverlappingFileLockException unavailable) {",
                "                    return lock;\n"
                        + "                }\n"
                        + "                " + LOCK_MISS + "();\n"
                        + "            } catch (OverlappingFileLockException unavailable) {"));
    }

    private static List<String> cliSourceOrder() {
        return List.of("FINAL_BEFORE_STDOUT", "PRE_OWNER", "BEFORE_SOURCE_FORCED",
                "BEFORE_DURABLE", "COMMITTED_SOURCE_FORCED", "COMMITTED_DURABLE",
                "MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "FINAL_INSTALLED",
                "OWNER_SOURCE_FORCED", "WRAPPER_DURABLE", "OWNER_DURABLE",
                "FINAL_COMMIT_SOURCE_FORCED", "FINAL_COMMIT_INSTALLED",
                "FINAL_COMMIT_DURABLE");
    }

    private static List<String> providerSourceOrder() {
        return List.of("PRE_LEASE", "CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
                "STATE_BEFORE_CHECKPOINT");
    }

    private static Anchor anchor(
            String point, String className, String summary, String needle, String replacement) {
        return new Anchor(point, className, summary, needle, replacement);
    }

    private static String hit(String point, int spaces) {
        return " ".repeat(spaces) + HIT + "(\"" + point + "\");\n";
    }

    private static void requirePointOrder(
            List<Anchor> cliAnchors, List<Anchor> providerAnchors) {
        Map<String, Anchor> all = new HashMap<>();
        for (Anchor anchor : concat(cliAnchors, providerAnchors)) {
            if (all.put(anchor.point, anchor) != null) {
                throw new IllegalStateException("duplicate instrumentation point");
            }
        }
        if (all.size() != POINT_ORDER.size()
                || !all.keySet().equals(java.util.Set.copyOf(POINT_ORDER))) {
            throw new IllegalStateException("instrumentation point order is invalid");
        }
        if (!SEMANTIC_WINDOW_BY_POINT.keySet().equals(java.util.Set.copyOf(POINT_ORDER))
                || !java.util.Set.copyOf(SEMANTIC_WINDOW_BY_POINT.values())
                .equals(java.util.Set.copyOf(SEMANTIC_WINDOW_ORDER))
                || java.util.Set.copyOf(SEMANTIC_WINDOW_ORDER).size()
                != SEMANTIC_WINDOW_ORDER.size()) {
            throw new IllegalStateException("semantic window mapping is invalid");
        }
    }

    private static Generated generate(
            Path source,
            Path target,
            String relativePath,
            List<Anchor> anchors,
            List<ObservationHook> hooks,
            List<String> sourceOrder) throws IOException {
        String original = Files.readString(source, StandardCharsets.UTF_8);
        Map<String, Anchor> byPoint = new HashMap<>();
        for (Anchor anchor : anchors) {
            byPoint.put(anchor.point, anchor);
        }
        int prior = -1;
        for (String point : sourceOrder) {
            Anchor anchor = byPoint.get(point);
            if (anchor == null) {
                throw new IllegalStateException("missing source-order point: " + point);
            }
            int position = uniquePosition(original, anchor);
            if (position <= prior) {
                throw new IllegalStateException("instrumentation source order changed: " + point);
            }
            prior = position;
        }
        if (sourceOrder.size() != anchors.size()) {
            throw new IllegalStateException("instrumentation source order is incomplete");
        }

        String instrumented = original;
        for (Anchor anchor : anchors) {
            int position = uniquePosition(instrumented, anchor);
            instrumented = instrumented.substring(0, position) + anchor.replacement
                    + instrumented.substring(position + anchor.needle.length());
        }
        for (ObservationHook hook : hooks) {
            int position = uniquePosition(instrumented, hook.name, hook.needle);
            instrumented = instrumented.substring(0, position) + hook.replacement
                    + instrumented.substring(position + hook.needle.length());
        }
        for (Anchor anchor : anchors) {
            String call = HIT + "(\"" + anchor.point + "\")";
            if (occurrences(instrumented, call) != 1) {
                throw new IllegalStateException(
                        "instrumentation point must be injected exactly once: " + anchor.point);
            }
        }
        for (ObservationHook hook : hooks) {
            if (occurrences(instrumented, LOCK_MISS + "()") != 1) {
                throw new IllegalStateException(
                        "observation hook must be injected exactly once: " + hook.name);
            }
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, instrumented, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return new Generated(relativePath, sha256(original), sha256(instrumented),
                anchors.stream().map(Anchor::point).toList());
    }

    private static int uniquePosition(String source, Anchor anchor) {
        return uniquePosition(source, anchor.point, anchor.needle);
    }

    private static int uniquePosition(String source, String name, String needle) {
        int first = source.indexOf(needle);
        if (first < 0 || source.indexOf(needle, first + 1) >= 0) {
            throw new IllegalStateException(
                    "instrumentation anchor must occur exactly once: " + name);
        }
        return first;
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int position = 0;
        while ((position = source.indexOf(value, position)) >= 0) {
            count++;
            position += value.length();
        }
        return count;
    }

    private static List<CompiledClass> compiledClasses(
            Path classesRoot,
            List<Anchor> cliAnchors,
            List<Anchor> providerAnchors,
            List<ObservationHook> hooks)
            throws IOException {
        List<CompiledClass> classes = new ArrayList<>();
        try (var files = Files.walk(classesRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted().toList()) {
                String relative = classesRoot.relativize(file).toString().replace('\\', '/');
                String className = relative.substring(0, relative.length() - ".class".length())
                        .replace('/', '.');
                classes.add(new CompiledClass(className, sha256(Files.readAllBytes(file))));
            }
        }
        Map<String, CompiledClass> byName = new HashMap<>();
        classes.forEach(value -> byName.put(value.className, value));
        for (Anchor anchor : concat(cliAnchors, providerAnchors)) {
            CompiledClass compiled = byName.get(anchor.className);
            if (compiled == null) {
                throw new IllegalStateException(
                        "instrumented class is missing: " + anchor.className);
            }
            Path file = classesRoot.resolve(anchor.className.replace('.', '/') + ".class");
            String constants = new String(Files.readAllBytes(file),
                    StandardCharsets.ISO_8859_1);
            if (!constants.contains(anchor.point)) {
                throw new IllegalStateException(
                        "instrumented class does not bind point: " + anchor.point);
            }
        }
        for (ObservationHook hook : hooks) {
            Path file = classesRoot.resolve(hook.className.replace('.', '/') + ".class");
            String constants = new String(Files.readAllBytes(file),
                    StandardCharsets.ISO_8859_1);
            if (!constants.contains("publicationFileLockMiss")) {
                throw new IllegalStateException(
                        "instrumented class does not bind observation hook: " + hook.name);
            }
        }
        return List.copyOf(classes);
    }

    private static String manifest(
            List<Generated> sources,
            List<Anchor> cliAnchors,
            List<Anchor> providerAnchors,
            List<ObservationHook> hooks,
            List<CompiledClass> classes) {
        Map<String, Anchor> byPoint = new LinkedHashMap<>();
        concat(cliAnchors, providerAnchors).forEach(value -> byPoint.put(value.point, value));
        String sourceJson = sources.stream().map(CrashInstrumentationGenerator::sourceJson)
                .reduce((left, right) -> left + "," + right).orElse("");
        String pointJson = POINT_ORDER.stream().map(byPoint::get)
                .map(CrashInstrumentationGenerator::pointJson)
                .reduce((left, right) -> left + "," + right).orElse("");
        String classJson = classes.stream()
                .sorted(Comparator.comparing(CompiledClass::className))
                .map(CrashInstrumentationGenerator::classJson)
                .reduce((left, right) -> left + "," + right).orElse("");
        String semanticWindows = SEMANTIC_WINDOW_ORDER.stream()
                .map(CrashInstrumentationGenerator::quote)
                .reduce((left, right) -> left + "," + right).orElse("");
        String hookJson = hooks.stream().map(CrashInstrumentationGenerator::hookJson)
                .reduce((left, right) -> left + "," + right).orElse("");
        return "{\"messageVersion\":\"bloge.test-only.crash-instrumentation.v3\","
                + "\"pointCount\":" + POINT_ORDER.size()
                + ",\"semanticWindowCount\":" + SEMANTIC_WINDOW_ORDER.size()
                + ",\"semanticWindows\":[" + semanticWindows + "]"
                + ",\"sources\":[" + sourceJson + "]"
                + ",\"observationHooks\":[" + hookJson + "]"
                + ",\"points\":[" + pointJson + "]"
                + ",\"classes\":[" + classJson + "]}\n";
    }

    private static String sourceJson(Generated generated) {
        String points = generated.points.stream().map(CrashInstrumentationGenerator::quote)
                .reduce((left, right) -> left + "," + right).orElse("");
        return "{\"sourcePath\":" + quote(generated.sourcePath)
                + ",\"sourceSha256\":" + quote(generated.sourceSha256)
                + ",\"instrumentedSha256\":" + quote(generated.instrumentedSha256)
                + ",\"points\":[" + points + "]}";
    }

    private static String pointJson(Anchor anchor) {
        return "{\"point\":" + quote(anchor.point)
                + ",\"semanticWindowId\":"
                + quote(SEMANTIC_WINDOW_BY_POINT.get(anchor.point))
                + ",\"className\":" + quote(anchor.className)
                + ",\"anchorSummary\":" + quote(anchor.summary) + "}";
    }

    private static String hookJson(ObservationHook hook) {
        return "{\"hook\":" + quote(hook.name)
                + ",\"className\":" + quote(hook.className)
                + ",\"anchorSummary\":" + quote(hook.summary) + "}";
    }

    private static String classJson(CompiledClass compiled) {
        return "{\"className\":" + quote(compiled.className)
                + ",\"classSha256\":" + quote(compiled.classSha256) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }

    private record Anchor(
            String point,
            String className,
            String summary,
            String needle,
            String replacement) {
    }

    private record ObservationHook(
            String name,
            String className,
            String summary,
            String needle,
            String replacement) {
    }

    private record Generated(
            String sourcePath,
            String sourceSha256,
            String instrumentedSha256,
            List<String> points) {
    }

    private record CompiledClass(String className, String classSha256) {
    }
}
