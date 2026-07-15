package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/** Append-only security-event sink for the caller-driven test surface. */
public interface TestSecurityEventRepository {
    TestSecurityEvent append(TestSecurityEvent event);

    List<TestSecurityEvent> recent(int limit);
}
