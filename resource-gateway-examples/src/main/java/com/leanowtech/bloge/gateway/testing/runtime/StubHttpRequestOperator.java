package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.operator.HttpRequestTransport;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Productized transport test double for exercising an HTTP-backed operator's real mapping and
 * response interpretation without opening a network connection.
 *
 * <p>The resolver receives the fully rendered {@link HttpRequestInput}; tests can therefore assert
 * URL, headers, body, auth, and capped timeout before returning a protocol-level response.</p>
 */
public class StubHttpRequestOperator implements HttpRequestTransport {

    private final Function<HttpRequestInput, HttpResponseOutput> resolver;
    private final List<HttpRequestInput> requests = new ArrayList<>();

    /** @param response fixed protocol response returned for every request */
    public StubHttpRequestOperator(HttpResponseOutput response) {
        this(ignored -> Objects.requireNonNull(response, "response"));
    }

    /** @param resolver deterministic response resolver, which must not perform I/O */
    public StubHttpRequestOperator(Function<HttpRequestInput, HttpResponseOutput> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Records and resolves one transport invocation. */
    @Override
    public synchronized HttpResponseOutput execute(HttpRequestInput input, OperatorContext ctx) {
        requests.add(input);
        HttpResponseOutput response = resolver.apply(input);
        if (response == null) {
            throw new TestControlException("FIXTURE_UNMATCHED", "HTTP_TRANSPORT_FIXTURE",
                    "HTTP transport fixture returned no response.");
        }
        return response;
    }

    /** @return immutable captured requests in invocation order */
    public synchronized List<HttpRequestInput> requests() {
        return List.copyOf(requests);
    }

    /** @return the latest request */
    public synchronized HttpRequestInput lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("Stub HTTP transport has not been invoked.");
        }
        return requests.getLast();
    }
}
