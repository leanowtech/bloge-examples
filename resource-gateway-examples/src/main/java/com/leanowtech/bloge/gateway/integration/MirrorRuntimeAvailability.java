package com.leanowtech.bloge.gateway.integration;

/**
 * Profile-owned capability marker for the protected mirror HTTP surface.
 *
 * <p>The marker is created only by the isolated test/staging composition root. Capability probes
 * consume it instead of inferring readiness from configuration text or classpath presence.</p>
 *
 * @param planCompilationApi protected plan compile/read routes are assembled
 * @param executionApi protected run/evidence routes are assembled
 */
public record MirrorRuntimeAvailability(
        boolean planCompilationApi,
        boolean executionApi
) {
}
