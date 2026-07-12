package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Authenticates integration requests before server-owned claims enter the service layer. */
public final class IntegrationRequestAuthenticator {
    private static final int MAX_CREDENTIAL_LENGTH = 4096;

    private final IntegrationIdentityResolver resolver;
    private final IntegrationAccessAuditRepository audit;

    public IntegrationRequestAuthenticator(IntegrationIdentityResolver resolver,
                                           IntegrationAccessAuditRepository audit) {
        this.resolver = resolver == null ? IntegrationIdentityResolver.unavailable() : resolver;
        this.audit = audit;
    }

    public IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation) {
        String correlationId = limited(header(headers, "X-Correlation-Id"), 128);
        if (correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String purpose = header(headers, "X-Purpose").toUpperCase();
        String credential = bearer(headers);
        if (credential.isBlank()) {
            deny(correlationId, null, operation, purpose, "RG.INTEGRATION.AUTHENTICATION_REQUIRED");
            throw new IntegrationProblemException(IntegrationProblem.unauthorized(
                    "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                    "A verified integration workload credential is required.", correlationId, Map.of()));
        }
        IntegrationWorkloadIdentity identity = resolver.resolve(credential).orElse(null);
        if (identity == null) {
            deny(correlationId, null, operation, purpose, "RG.INTEGRATION.AUTHENTICATION_FAILED");
            throw new IntegrationProblemException(IntegrationProblem.unauthorized(
                    "RG.INTEGRATION.AUTHENTICATION_FAILED",
                    "The integration workload credential is invalid or inactive.", correlationId, Map.of()));
        }
        if (purpose.isBlank()) {
            deny(correlationId, identity, operation, purpose, "RG.INTEGRATION.PURPOSE_REQUIRED");
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PURPOSE_REQUIRED", "An explicit integration purpose is required.",
                    correlationId, Map.of()));
        }
        if (operation == null || !operation.accepts(purpose) || !identity.allowsPurpose(purpose)) {
            deny(correlationId, identity, operation, purpose, "RG.INTEGRATION.PURPOSE_FORBIDDEN");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PURPOSE_FORBIDDEN",
                    "The verified workload identity is not permitted to use this purpose for the operation.",
                    correlationId, operation == null ? Map.of() : Map.of("operation", operation.name())));
        }
        Map<String, Object> mismatches = claimHintMismatches(headers, identity);
        if (!mismatches.isEmpty()) {
            deny(correlationId, identity, operation, purpose, "RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH",
                    "Self-asserted identity headers conflict with the verified workload identity.",
                    correlationId, mismatches));
        }
        IntegrationRequestContext context = new IntegrationRequestContext(identity.tenantId(),
                identity.organizationId(), identity.projectId(), identity.environmentId(), identity.region(),
                identity.actorType(), identity.actorId(), identity.delegatedBy(), purpose, correlationId);
        appendAudit(new IntegrationAccessAuditRecord(0, Instant.now(), correlationId, identity.identityId(),
                identity.tenantId(), identity.environmentId(), operation.name(), purpose, "ALLOWED", ""));
        return context;
    }

    public IntegrationIdentityResolver.Descriptor descriptor() {
        return resolver.descriptor();
    }

    private void deny(String correlationId,
                      IntegrationWorkloadIdentity identity,
                      IntegrationOperation operation,
                      String purpose,
                      String reasonCode) {
        appendAudit(new IntegrationAccessAuditRecord(0, Instant.now(), correlationId,
                identity == null ? "" : identity.identityId(), identity == null ? "" : identity.tenantId(),
                identity == null ? "" : identity.environmentId(), operation == null ? "" : operation.name(),
                purpose, "DENIED", reasonCode));
    }

    private void appendAudit(IntegrationAccessAuditRecord record) {
        if (audit == null) {
            throw auditUnavailable(record.correlationId());
        }
        try {
            audit.append(record);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw auditUnavailable(record.correlationId());
        }
    }

    private static IntegrationProblemException auditUnavailable(String correlationId) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                "Integration authentication is unavailable because its required audit sink cannot commit.",
                correlationId, Map.of()));
    }

    private static Map<String, Object> claimHintMismatches(HttpHeaders headers,
                                                           IntegrationWorkloadIdentity identity) {
        Map<String, Object> mismatches = new LinkedHashMap<>();
        compareHint(mismatches, headers, "X-Tenant-Id", identity.tenantId());
        compareHint(mismatches, headers, "X-Organization-Id", identity.organizationId());
        compareHint(mismatches, headers, "X-Project-Id", identity.projectId());
        compareHint(mismatches, headers, "X-Environment-Id", identity.environmentId());
        compareHint(mismatches, headers, "X-Region", identity.region());
        compareHint(mismatches, headers, "X-Actor-Type", identity.actorType());
        compareHint(mismatches, headers, "X-Actor-Id", identity.actorId());
        compareHint(mismatches, headers, "X-Delegated-By", identity.delegatedBy());
        return mismatches;
    }

    private static void compareHint(Map<String, Object> mismatches,
                                    HttpHeaders headers,
                                    String name,
                                    String trustedValue) {
        String supplied = header(headers, name);
        if (!supplied.isBlank() && !supplied.equals(trustedValue)) {
            mismatches.put(name, "does-not-match-verified-identity");
        }
    }

    private static String bearer(HttpHeaders headers) {
        String authorization = header(headers, HttpHeaders.AUTHORIZATION);
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        String credential = authorization.substring(7).trim();
        return credential.length() > MAX_CREDENTIAL_LENGTH ? "" : credential;
    }

    private static String header(HttpHeaders headers, String name) {
        String value = headers == null ? "" : headers.getFirst(name);
        return value == null ? "" : value.trim();
    }

    private static String limited(String value, int maximum) {
        return value == null || value.length() <= maximum ? (value == null ? "" : value) : value.substring(0, maximum);
    }
}
