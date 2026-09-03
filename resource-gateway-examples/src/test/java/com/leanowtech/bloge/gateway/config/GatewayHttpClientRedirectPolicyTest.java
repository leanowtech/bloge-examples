package com.leanowtech.bloge.gateway.config;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the production HTTP transport cannot redirect around Resource Gateway egress policy. */
class GatewayHttpClientRedirectPolicyTest {

    @Test
    void productionTransportReturnsRedirectWithoutCallingItsUnapprovedTarget() throws Exception {
        AtomicInteger redirectedTargetCalls = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/outside", exchange -> {
            redirectedTargetCalls.incrementAndGet();
            byte[] body = "outside".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpServer admitted = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admitted.createContext("/inside", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + target.getAddress().getPort() + "/outside");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        target.start();
        admitted.start();
        try {
            var transport = new GatewayConfiguration().httpRequestOperator();
            var context = new OperatorContext("node", "graph",
                    new GraphContext(new TenantContext("tenant", "project")), 0);

            var response = transport.execute(new HttpRequestInput(
                    "http://127.0.0.1:" + admitted.getAddress().getPort() + "/inside"), context);

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(redirectedTargetCalls).hasValue(0);
        } finally {
            admitted.stop(0);
            target.stop(0);
        }
    }
}
