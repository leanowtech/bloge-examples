package com.leanowtech.bloge.gateway.testing.runtime;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunServiceArchitectureTest {

    private static final List<Class<?>> STAGE_ZERO_CORE = List.of(
            com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler.class,
            com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder.class,
            com.leanowtech.bloge.gateway.testing.planning.SelectorResolver.class,
            com.leanowtech.bloge.gateway.testing.planning.SafetyPreflight.class,
            TestDoubleFactory.class,
            ResourceFixtureRuntime.class,
            com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter.class,
            TestRunService.class);

    private static final Set<String> FORBIDDEN_INVOCATIONS = Set.of(
            "java/time/Instant.now",
            "java/lang/System.currentTimeMillis",
            "java/lang/System.nanoTime",
            "java/util/UUID.randomUUID",
            "java/lang/Math.random",
            "java/util/concurrent/ThreadLocalRandom.current",
            "java/util/Collection.parallelStream",
            "java/util/stream/BaseStream.parallel");

    @Test
    void stageZeroCoreHasNoDirectSystemIdentityOrRandomnessCalls() throws IOException {
        Map<String, List<String>> forbidden = new LinkedHashMap<>();
        List<Class<?>> classes = new ArrayList<>(STAGE_ZERO_CORE);
        classes.add(nested(TestDoubleFactory.class, "ObservedOperator"));
        for (Class<?> type : classes) {
            List<String> violations = new ArrayList<>();
            violations.addAll(invocations(type).stream()
                    .filter(reference -> FORBIDDEN_INVOCATIONS.contains(reference)
                            || reference.endsWith(".parallelStream")
                            || reference.endsWith(".parallel"))
                    .toList());
            violations.addAll(allocations(type).stream()
                    .filter(name -> name.equals("java/util/Random")
                            || name.equals("java/security/SecureRandom"))
                    .map(name -> "new " + name)
                    .toList());
            if (!violations.isEmpty()) {
                forbidden.put(type.getName(), violations);
            }
        }
        assertThat(forbidden).as("Stage-0 deterministic core violations").isEmpty();
    }

    private static Class<?> nested(Class<?> owner, String name) {
        try {
            return Class.forName(owner.getName() + "$" + name);
        } catch (ClassNotFoundException failure) {
            throw new AssertionError("Missing deterministic-core nested class: " + name, failure);
        }
    }

    private static List<String> invocations(Class<?> type) throws IOException {
        return code(type).flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .map(instruction -> instruction.owner().asInternalName()
                        + "." + instruction.name().stringValue())
                .toList();
    }

    private static List<String> allocations(Class<?> type) throws IOException {
        return code(type).flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(NewObjectInstruction.class::isInstance)
                .map(NewObjectInstruction.class::cast)
                .map(instruction -> instruction.className().asInternalName())
                .toList();
    }

    private static Stream<java.lang.classfile.MethodModel> code(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var input = type.getClassLoader().getResourceAsStream(resource)) {
            return ClassFile.of().parse(input.readAllBytes()).methods().stream();
        }
    }
}
