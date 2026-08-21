package com.leanowtech.bloge.gateway.testkit;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child-process probe loaded with the shaded JAR instead of target/classes. */
final class CapabilityStudioGateACandidateReplayResultShadedJarProbe {
    private CapabilityStudioGateACandidateReplayResultShadedJarProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            System.exit(2);
        }
        CapabilityStudioGateACandidateReplayResult.verifyResultBytes(
                Files.readAllBytes(Path.of(args[0])));
        System.out.println("SHADED_SCHEMA_OK");
    }
}
