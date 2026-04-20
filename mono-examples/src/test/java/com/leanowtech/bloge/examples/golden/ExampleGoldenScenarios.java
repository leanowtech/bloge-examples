package com.leanowtech.bloge.examples.golden;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;

/**
 * Catalog of golden-file scenarios backed by the existing example integration fixtures.
 *
 * <p>Reusing the current test fixtures keeps the golden suite aligned with the already
 * maintained execution harnesses for Java API, DSL, loop, and long-running examples.
 * The reflective indirection is test-only and avoids copying large amounts of setup code.</p>
 */
final class ExampleGoldenScenarios {

    private ExampleGoldenScenarios() {
    }

    static Stream<GoldenScenario> scenarios() {
        return Stream.of(
                scenario(
                        "chatbot/customer-service-chatbot-java-order-query.json",
                        "com.leanowtech.bloge.examples.chatbot.CustomerServiceChatbotExampleTest",
                        "executeJavaApi",
                        "Where is my order?"
                ),
                scenario(
                        "chatbot/customer-service-chatbot-dsl-complaint.json",
                        "com.leanowtech.bloge.examples.chatbot.CustomerServiceChatbotExampleTest",
                        "executeDsl",
                        "I want to make a complaint"
                ),
                scenario(
                        "chatbot/customer-service-chatbot-long-running-java-order-query.json",
                        "com.leanowtech.bloge.examples.chatbot.CustomerServiceChatbotLongRunningExampleTest",
                        "executeJavaApi",
                        "Where is my order?"
                ),
                scenario(
                        "chatbot/customer-service-session-java-handoff.json",
                        "com.leanowtech.bloge.examples.chatbot.CustomerServiceSessionExampleTest",
                        "executeJavaApi",
                        "I need a refund and human support"
                ),
                scenario(
                        "chatbot/customer-service-session-dsl-close.json",
                        "com.leanowtech.bloge.examples.chatbot.CustomerServiceSessionExampleTest",
                        "executeDsl",
                        "Thanks, bye"
                ),
                scenario(
                        "chatbot/it-helpdesk-chatbot-dsl-password-reset.json",
                        "com.leanowtech.bloge.examples.chatbot.ItHelpdeskChatbotExampleTest",
                        "executeDsl",
                        "I forgot my password and got locked out"
                ),
                scenario(
                        "chatbot/ecommerce-chatbot-java-compare.json",
                        "com.leanowtech.bloge.examples.chatbot.EcommerceChatbotExampleTest",
                        "executeJavaApi",
                        "Compare the two models vs each other"
                ),
                scenario(
                        "rag/rag-pipeline-java-answer.json",
                        "com.leanowtech.bloge.examples.rag.RagPipelineExampleTest",
                        "run",
                        "Explain RAG in one sentence."
                ),
                scenario(
                        "iteration/sequential-transfer-java.json",
                        "com.leanowtech.bloge.examples.iteration.SequentialTransferExampleTest",
                        "executeJavaApi"
                ),
                scenario(
                        "iteration/batch-order-parallel-dsl.json",
                        "com.leanowtech.bloge.examples.iteration.BatchOrderParallelExampleTest",
                        "executeDsl"
                ),
                scenario(
                        "iteration/logistics-batch-dispatch-java.json",
                        "com.leanowtech.bloge.examples.iteration.LogisticsBatchDispatchExampleTest",
                        "executeJavaApi"
                ),
                scenario(
                        "iteration/cursor-pagination-dsl.json",
                        "com.leanowtech.bloge.examples.iteration.CursorPaginationExampleTest",
                        "executeDsl"
                ),
                scenarioWithSetup(
                        "iteration/retry-with-backoff-java.json",
                        "com.leanowtech.bloge.examples.iteration.RetryWithBackoffExampleTest",
                        "resetCallCounters",
                        "executeJavaApi"
                ),
                scenario(
                        "iteration/status-polling-dsl.json",
                        "com.leanowtech.bloge.examples.iteration.StatusPollingExampleTest",
                        "executeDsl"
                )
        );
    }

    private static GoldenScenario scenario(String resourcePath, String fixtureClassName, String methodName) {
        return new GoldenScenario(resourcePath, fixtureClassName, methodName, null, new Class<?>[0], new Object[0]);
    }

    private static GoldenScenario scenario(String resourcePath,
                                           String fixtureClassName,
                                           String methodName,
                                           String argument) {
        return new GoldenScenario(
                resourcePath,
                fixtureClassName,
                methodName,
                null,
                new Class<?>[]{String.class},
                new Object[]{argument}
        );
    }

    private static GoldenScenario scenarioWithSetup(String resourcePath,
                                                    String fixtureClassName,
                                                    String setupMethodName,
                                                    String methodName) {
        return new GoldenScenario(resourcePath, fixtureClassName, methodName, setupMethodName, new Class<?>[0], new Object[0]);
    }

    /**
     * Describes one golden-file execution, including any optional fixture reset hook.
     */
    record GoldenScenario(String resourcePath,
                          String fixtureClassName,
                          String methodName,
                          String setupMethodName,
                          Class<?>[] parameterTypes,
                          Object[] arguments) {

        Object executeFixture() throws Exception {
            Class<?> fixtureClass = Class.forName(fixtureClassName);
            Constructor<?> constructor = fixtureClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object fixture = constructor.newInstance();
            if (setupMethodName != null) {
                Method setupMethod = fixtureClass.getDeclaredMethod(setupMethodName);
                setupMethod.setAccessible(true);
                setupMethod.invoke(fixture);
            }
            Method method = fixtureClass.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            try {
                return method.invoke(fixture, arguments);
            } catch (InvocationTargetException invocationTargetException) {
                Throwable cause = invocationTargetException.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(cause);
            }
        }

        @Override
        public String toString() {
            return resourcePath;
        }
    }
}
