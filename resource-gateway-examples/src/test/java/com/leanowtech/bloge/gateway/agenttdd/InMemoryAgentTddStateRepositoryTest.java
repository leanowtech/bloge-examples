package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies atomic exactly-once mutation and replay inside the local Agent TDD repository. */
class InMemoryAgentTddStateRepositoryTest {

    @Test
    void concurrentSameKeyRunsTheBusinessMutationExactlyOnce() throws Exception {
        InMemoryAgentTddStateRepository repository = new InMemoryAgentTddStateRepository();
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger mutations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<String> responses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                start.await();
                return repository.executeOnce("scope", "publish", "key", "sha256:req", () -> {
                    int value = mutations.incrementAndGet();
                    return mapper.valueToTree(java.util.Map.of("publicationOrdinal", value));
                }).toString();
            })).toList();
            start.countDown();
            for (var task : tasks) responses.add(task.get());
        }

        assertThat(mutations).hasValue(1);
        assertThat(responses).containsOnly("{\"publicationOrdinal\":1}");
    }
}
