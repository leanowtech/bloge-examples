package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;

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

    @Test
    void admitsAStablePublicAddressSetAndTheExplicitLocalSandbox() throws Exception {
        AgentTddEgressHostPolicy publicPolicy = new AgentTddEgressHostPolicy("api.example.test",
                host -> List.of(InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("2001:4860:4860::8888")));

        AgentTddEgressHostPolicy.Resolution admitted = publicPolicy.resolveAllowed(
                "https://api.example.test/orders");
        publicPolicy.requireUnchanged("https://api.example.test/orders", admitted);

        AgentTddEgressHostPolicy localPolicy = new AgentTddEgressHostPolicy("localhost",
                host -> List.of(InetAddress.getLoopbackAddress()));
        assertThat(localPolicy.resolveAllowed("http://localhost:18081/read").host())
                .isEqualTo("localhost");
    }

    @Test
    void rejectsPrivateMixedAndChangingDnsAnswers() throws Exception {
        assertResolutionRejected(host -> List.of(InetAddress.getByName("10.0.0.8")));
        assertResolutionRejected(host -> List.of(InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("169.254.169.254")));

        ArrayDeque<List<InetAddress>> answers = new ArrayDeque<>(List.of(
                List.of(InetAddress.getByName("8.8.8.8")),
                List.of(InetAddress.getByName("1.1.1.1"))));
        assertResolutionRejected(host -> answers.removeFirst());
    }

    @Test
    void rejectsAnAddressSwitchAfterInitialAdmission() throws Exception {
        ArrayDeque<List<InetAddress>> answers = new ArrayDeque<>(List.of(
                List.of(InetAddress.getByName("8.8.8.8")),
                List.of(InetAddress.getByName("8.8.8.8")),
                List.of(InetAddress.getByName("1.1.1.1"))));
        AgentTddEgressHostPolicy policy = new AgentTddEgressHostPolicy(
                "api.example.test", host -> answers.removeFirst());

        AgentTddEgressHostPolicy.Resolution admitted = policy.resolveAllowed(
                "https://api.example.test/read");

        assertThatThrownBy(() -> policy.requireUnchanged(
                "https://api.example.test/read", admitted))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("EGRESS_NOT_ALLOWED"));
    }

    @Test
    void rejectsIpv6LoopbackLinkLocalAndUniqueLocalAnswers() throws Exception {
        assertResolutionRejected(host -> List.of(InetAddress.getByName("::1")));
        assertResolutionRejected(host -> List.of(InetAddress.getByName("fe80::1")));
        assertResolutionRejected(host -> List.of(InetAddress.getByName("fd00::1")));
    }

    private static void assertRejected(AgentTddEgressHostPolicy policy, String url) {
        assertThatThrownBy(() -> policy.requireAllowed(url))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("EGRESS_NOT_ALLOWED"));
    }

    private static void assertResolutionRejected(AgentTddEgressHostPolicy.HostResolver resolver) {
        AgentTddEgressHostPolicy policy = new AgentTddEgressHostPolicy("api.example.test", resolver);
        assertThatThrownBy(() -> policy.resolveAllowed("https://api.example.test/read"))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("EGRESS_NOT_ALLOWED"));
    }
}
