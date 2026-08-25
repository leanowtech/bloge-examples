package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Authenticates test API body-bearing routes before request-body deserialization. */
@Component
@Profile("!production & (test | staging)")
final class TestExecutionAuthenticationInterceptor implements HandlerInterceptor {
    static final String REQUEST_ATTRIBUTE = TestExecutionAuthenticationInterceptor.class.getName()
            + ".authenticatedRequest";

    private static final List<Route> ROUTES = List.of(
            Route.controlPost("/api/testing/executions", IntegrationOperation.TEST_EXECUTION),
            Route.post("/api/testing/targets/operators/{ref}/executions",
                    IntegrationOperation.TEST_EXECUTION),
            Route.post("/api/testing/executions/batch", IntegrationOperation.TEST_EXECUTION),
            Route.put("/api/testing/fixture-bundles/{id}", IntegrationOperation.TEST_FIXTURE_WRITE),
            Route.put("/api/testing/replay-payloads/{id}", IntegrationOperation.TEST_REPLAY_WRITE),
            Route.put("/api/testing/suites/{id}", IntegrationOperation.TEST_SUITE_WRITE),
            Route.post("/api/testing/suites/{id}/executions",
                    IntegrationOperation.TEST_SUITE_EXECUTION));

    private final IntegrationRequestAuthenticator authenticator;

    TestExecutionAuthenticationInterceptor(IntegrationRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        Route route = routeFor(request);
        if (route == null) {
            return true;
        }
        HttpHeaders requestHeaders = headers(request);
        IntegrationRequestContext context = authenticator.authenticate(
                requestHeaders, route.operation());
        if (!route.allowsTestControls()
                && TestExecutionIngressAdapter.hasAnyTestControlHeaders(requestHeaders)) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.CONTROL_HEADERS_UNSUPPORTED",
                    "Test control headers are supported only on /api/testing/executions.",
                    context.correlationId(), java.util.Map.of("operation", route.operation().name())));
        }
        request.setAttribute(REQUEST_ATTRIBUTE, new AuthenticatedRequest(route.operation(), context));
        return true;
    }

    static IntegrationOperation operationFor(String method, String servletPath) {
        if (method == null || servletPath == null || servletPath.isBlank()) {
            return null;
        }
        return ROUTES.stream()
                .filter(route -> route.matches(method, servletPath))
                .map(Route::operation)
                .findFirst()
                .orElse(null);
    }

    private static Route routeFor(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        IntegrationOperation operation = operationFor(request.getMethod(), request.getServletPath());
        return operation == null ? null : ROUTES.stream()
                .filter(route -> route.operation() == operation
                        && route.matches(request.getMethod(), request.getServletPath()))
                .findFirst()
                .orElse(null);
    }

    private static HttpHeaders headers(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (request == null || request.getHeaderNames() == null) {
            return headers;
        }
        Collections.list(request.getHeaderNames()).forEach(name ->
                headers.put(name, Collections.list(request.getHeaders(name))));
        return headers;
    }

    record AuthenticatedRequest(IntegrationOperation operation, IntegrationRequestContext context) {
    }

    private record Route(String method, List<String> pathSegments, IntegrationOperation operation,
                         boolean allowsTestControls) {
        private static Route post(String path, IntegrationOperation operation) {
            return new Route("POST", segments(path), operation, false);
        }

        private static Route controlPost(String path, IntegrationOperation operation) {
            return new Route("POST", segments(path), operation, true);
        }

        private static Route put(String path, IntegrationOperation operation) {
            return new Route("PUT", segments(path), operation, false);
        }

        private boolean matches(String requestMethod, String servletPath) {
            if (!method.equalsIgnoreCase(requestMethod)) {
                return false;
            }
            List<String> actual = segments(servletPath);
            if (pathSegments.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < pathSegments.size(); i++) {
                String expected = pathSegments.get(i);
                if (!isVariable(expected) && !expected.equals(actual.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isVariable(String segment) {
            return segment.startsWith("{") && segment.endsWith("}");
        }

        private static List<String> segments(String path) {
            if (path == null || path.isBlank() || "/".equals(path)) {
                return List.of();
            }
            return Arrays.stream(path.split("/"))
                    .filter(segment -> !segment.isBlank())
                    .map(segment -> segment.toLowerCase(Locale.ROOT))
                    .toList();
        }
    }
}
