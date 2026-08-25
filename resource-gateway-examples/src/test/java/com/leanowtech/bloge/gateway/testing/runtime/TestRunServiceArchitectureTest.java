package com.leanowtech.bloge.gateway.testing.runtime;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunServiceArchitectureTest {

    @Test
    void stageZeroCoreHasNoDirectSystemIdentityOrRandomnessCalls() throws IOException {
        byte[] classBytes;
        try (var input = TestRunService.class.getResourceAsStream("TestRunService.class")) {
            classBytes = input.readAllBytes();
        }

        List<String> invocations = ClassFile.of().parse(classBytes).methods().stream()
                .flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .map(instruction -> instruction.owner().asInternalName()
                        + "." + instruction.name().stringValue())
                .toList();
        List<String> allocations = ClassFile.of().parse(classBytes).methods().stream()
                .flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(NewObjectInstruction.class::isInstance)
                .map(NewObjectInstruction.class::cast)
                .map(instruction -> instruction.className().asInternalName())
                .toList();

        assertThat(invocations)
                .doesNotContain("java/time/Instant.now", "java/util/UUID.randomUUID",
                        "java/lang/Math.random", "java/util/concurrent/ThreadLocalRandom.current");
        assertThat(allocations).doesNotContain("java/util/Random");
    }
}
