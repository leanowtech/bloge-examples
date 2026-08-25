package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

/** Transport boundary used by {@link HttpResourceOperator}. */
@FunctionalInterface
public interface HttpRequestTransport {

    /** Executes one already-rendered HTTP request. */
    HttpResponseOutput execute(HttpRequestInput input, OperatorContext context) throws Exception;
}
