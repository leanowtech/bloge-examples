package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Trust transition from an authenticated test request to run-scoped external secret values.
 *
 * <p>The service constructs the exact payload-free authority context, invokes one configured
 * authority, independently validates the complete response closure and validity window, and maps
 * every failure to stable payload-free API problems. It is invoked again for durable recovery;
 * resolved values are never checkpointed.</p>
 */
public final class TestSecretResolutionService {
    private final ObjectMapper objectMapper;
    private final TestSecretAuthority authority;
    private final TestSecurityEventRepository securityEvents;
    private final Clock clock;

    /** Creates a service using the system UTC clock. */
    public TestSecretResolutionService(ObjectMapper objectMapper,
                                       TestSecretAuthority authority,
                                       TestSecurityEventRepository securityEvents) {
        this(objectMapper, authority, securityEvents, Clock.systemUTC());
    }

    /** Constructor with an explicit clock for deterministic trust-boundary tests. */
    TestSecretResolutionService(ObjectMapper objectMapper,
                                TestSecretAuthority authority,
                                TestSecurityEventRepository securityEvents,
                                Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves an exact fixture secret closure for a fresh run or durable re-authorization.
     *
     * @param fixture immutable fixture bundle
     * @param executionTargetFingerprint exact graph/operator/mutant execution target
     * @param fixtureTargetFingerprint exact target to which the fixture is bound
     * @param authorizedPurpose server-minted execution purpose
     * @param identity authenticated enterprise request identity
     * @return empty or independently verified run-scoped secret values
     */
    public ResolvedTestSecrets resolve(FixtureBundle fixture,
                                       String executionTargetFingerprint,
                                       String fixtureTargetFingerprint,
                                       String authorizedPurpose,
                                       IntegrationRequestContext identity) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(identity, "identity").requireComplete();
        FixtureExecutionServices services = FixtureExecutionServices.from(fixture);
        if (services.secretRefs().isEmpty()) {
            return ResolvedTestSecrets.empty();
        }
        TestSecretResolutionContext context = new TestSecretResolutionContext("",
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region(), identity.actorType(), identity.actorId(),
                identity.delegatedBy(), identity.purpose(), identity.groups(), identity.clearance(),
                identity.delegationGrantId(), authorizedPurpose, executionTargetFingerprint,
                fixtureTargetFingerprint, fixture.fixtureBundleId(), fixture.revision(),
                ProtocolFingerprint.of(objectMapper, fixture), services.secretRefs());
        try {
            return ResolvedTestSecrets.verified(
                    objectMapper, authority.resolve(context), context, clock.instant());
        } catch (TestSecretAuthority.ResolutionException authorityFailure) {
            if (authorityFailure.reason() == TestSecretAuthority.Reason.DENIED) {
                audit(identity, "DENIED");
                throw new IntegrationProblemException(IntegrationProblem.forbidden(
                        "RG.TEST.SECRET_AUTHORITY_DENIED",
                        "The external test-secret authority denied this execution scope.",
                        identity.correlationId(), Map.of()));
            }
            if (authorityFailure.reason() == TestSecretAuthority.Reason.INVALID_RESPONSE) {
                audit(identity, "AUTHORITY_RESPONSE_INVALID");
                throw invalidResponse(identity);
            }
            throw unavailable(identity);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            audit(identity, "AUTHORITY_RESPONSE_INVALID");
            throw invalidResponse(identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException infrastructure) {
            throw unavailable(identity);
        }
    }

    /** @return payload-free configured authority capability */
    public TestSecretAuthority.Descriptor descriptor() {
        return authority.descriptor();
    }

    private void audit(IntegrationRequestContext identity, String reason) {
        try {
            TestSecretAuthority.Descriptor descriptor = authority.descriptor();
            securityEvents.append(new TestSecurityEvent(0, Instant.now(clock),
                    identity.correlationId(), identity.tenantId(), identity.environmentId(),
                    identity.actorId(), "TEST_SECRET_AUTHORITY", "REJECTED", reason,
                    Map.of("authorityFingerprint", ProtocolFingerprint.of(objectMapper, Map.of(
                            "providerType", descriptor.providerType(),
                            "authorityId", descriptor.authorityId())))));
        } catch (RuntimeException ignored) {
            // The original fail-closed decision must survive an unavailable audit sink.
        }
    }

    private static IntegrationProblemException invalidResponse(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.SECRET_AUTHORITY_RESPONSE_INVALID",
                "The external test-secret authority returned an invalid bound response.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.SECRET_AUTHORITY_UNAVAILABLE",
                "The external test-secret authority is unavailable.",
                identity.correlationId(), Map.of()));
    }
}
