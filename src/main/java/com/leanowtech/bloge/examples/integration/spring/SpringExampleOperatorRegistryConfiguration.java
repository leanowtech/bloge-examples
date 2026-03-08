package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Adapts scanned {@link BlogeOperator}-annotated Spring beans into runtime BLOGE operators.
 *
 * <p>This keeps the example compatible with the repository's Java 25 engine artifacts while still
 * showing the starter's intended annotation-based discovery flow inside a Spring Boot application.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SpringExampleOperatorRegistryConfiguration {

    @Bean
    public OperatorRegistry operatorRegistry(ApplicationContext applicationContext) {
        var registry = new DefaultOperatorRegistry();
        applicationContext.getBeansWithAnnotation(BlogeOperator.class).values().forEach(bean -> {
            if (bean instanceof SpringTicketClassifierOperator classifier) {
                registry.register("SpringTicketClassifierOperator", new SpringTicketClassifierAdapter(classifier));
            } else if (bean instanceof SpringReplyDraftOperator drafter) {
                registry.register("SpringReplyDraftOperator", new SpringReplyDraftAdapter(drafter));
            }
        });
        return registry;
    }

    /**
     * Adapts the DSL-produced map payload into the request record expected by the Spring bean while
     * preserving the concrete output type for downstream schema-aware bindings.
     */
    private static final class SpringTicketClassifierAdapter implements
            Operator<Map<String, Object>, SpringTicketClassifierOperator.ClassifiedTicket> {

        private final SpringTicketClassifierOperator delegate;

        private SpringTicketClassifierAdapter(SpringTicketClassifierOperator delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpringTicketClassifierOperator.ClassifiedTicket execute(
                Map<String, Object> input,
                OperatorContext ctx) {
            return delegate.classify(new SpringTicketClassifierOperator.SpringTicketRequest(
                    String.valueOf(input.get("ticketId")),
                    String.valueOf(input.get("message")),
                    String.valueOf(input.get("customerTier"))
            ));
        }
    }

    /**
     * Converts the draft node's map-shaped DSL input into the request record consumed by the Spring
     * bean so the graph can stay declarative.
     */
    private static final class SpringReplyDraftAdapter implements
            Operator<Map<String, Object>, SpringReplyDraftOperator.ReplyDraft> {

        private final SpringReplyDraftOperator delegate;

        private SpringReplyDraftAdapter(SpringReplyDraftOperator delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpringReplyDraftOperator.ReplyDraft execute(
                Map<String, Object> input,
                OperatorContext ctx) {
            return delegate.draft(new SpringReplyDraftOperator.ReplyDraftRequest(
                    String.valueOf(input.get("ticketId")),
                    String.valueOf(input.get("queue")),
                    ((Number) input.get("priorityScore")).intValue(),
                    Boolean.TRUE.equals(input.get("vip"))
            ));
        }
    }
}
