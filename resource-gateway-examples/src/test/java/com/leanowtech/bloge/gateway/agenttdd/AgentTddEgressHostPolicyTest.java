package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies exact-host, fail-closed egress admission for Agent TDD resource declarations. */
class AgentTddEgressHostPolicyTest {

    @Test
    void admitsOnlyExactConfiguredHttpHostsAndAllowsPathTemplates() {
        AgentTddEgressHostPolicy policy = new AgentTddEgressHostPolicy(
                "sandbox.example.test,127.0.0.1");

        assertThat(policy.requireAllowed(
                "https://sandbox.example.test/orders/{orderId}")).isEqualTo("sandbox.example.test");
        assertThat(policy.requireAllowed("http://127.0.0.1:18081/profile")).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsSuffixTricksCredentialsVariableHostsAndUnsupportedSchemes() {
        AgentTddEgressHostPolicy policy = new AgentTddEgressHostPolicy("sandbox.example.test");

        assertRejected(policy, "https://sandbox.example.test.attacker.invalid/orders");
        assertRejected(policy, "https://sandbox.example.test@attacker.invalid/orders");
        assertRejected(policy, "https://{tenant}.example.test/orders");
        assertRejected(policy, "file:///etc/passwd");
        assertRejected(new AgentTddEgressHostPolicy(""), "https://sandbox.example.test/orders");
    }

    private static void assertRejected(AgentTddEgressHostPolicy policy, String url) {
        assertThatThrownBy(() -> policy.requireAllowed(url))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("EGRESS_NOT_ALLOWED"));
    }
}
