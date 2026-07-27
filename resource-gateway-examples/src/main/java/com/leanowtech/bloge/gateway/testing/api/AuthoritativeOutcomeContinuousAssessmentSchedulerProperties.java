package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict process-local configuration for autonomous continuous outcome assessment.
 *
 * <p>Scheduling is disabled by default and may target only one exact non-production partition.
 * Durable registration, freshness, retries, and fencing remain database-authoritative across all
 * replicas.</p>
 *
 * @param enabled explicit scheduler activation switch
 * @param instanceId stable opaque replica identity
 * @param region exact regional partition
 * @param environmentId exact non-production environment
 * @param maximumPollers process-local synchronous worker lanes
 * @param initialDelayMillis delay before the first poll
 * @param pollIntervalMillis fixed delay after each completed turn
 * @param drainTimeoutMillis bounded graceful shutdown interval
 */
@ConfigurationProperties(
        prefix = AuthoritativeOutcomeContinuousAssessmentSchedulerProperties
                .PREFIX,
        ignoreUnknownFields = false)
public record
AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
        Boolean enabled,
        String instanceId,
        String region,
        String environmentId,
        Integer maximumPollers,
        Long initialDelayMillis,
        Long pollIntervalMillis,
        Long drainTimeoutMillis
) {
    /** Configuration prefix shared by application configuration and runtime documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.outcome-continuous-assessment.scheduler";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Set<String> RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    /** Applies finite defaults and rejects partial or production-targeted activation. */
    public AuthoritativeOutcomeContinuousAssessmentSchedulerProperties {
        enabled = Boolean.TRUE.equals(enabled);
        instanceId = normalized(instanceId);
        region = normalized(region)
                .toLowerCase(Locale.ROOT);
        environmentId = normalized(environmentId)
                .toLowerCase(Locale.ROOT);
        maximumPollers = maximumPollers == null
                ? 2 : maximumPollers;
        initialDelayMillis = initialDelayMillis == null
                ? 1_000L : initialDelayMillis;
        pollIntervalMillis = pollIntervalMillis == null
                ? 1_000L : pollIntervalMillis;
        drainTimeoutMillis = drainTimeoutMillis == null
                ? 30_000L : drainTimeoutMillis;
        bounded(
                maximumPollers,
                1,
                64,
                "maximumPollers");
        bounded(
                initialDelayMillis,
                0,
                Duration.ofMinutes(5).toMillis(),
                "initialDelayMillis");
        bounded(
                pollIntervalMillis,
                100,
                Duration.ofMinutes(1).toMillis(),
                "pollIntervalMillis");
        bounded(
                drainTimeoutMillis,
                Duration.ofSeconds(1).toMillis(),
                Duration.ofHours(1).toMillis(),
                "drainTimeoutMillis");
        if (enabled) {
            identifier(
                    instanceId, 255, "instanceId");
            identifier(region, 96, "region");
            identifier(
                    environmentId,
                    255,
                    "environmentId");
            if (RESERVED_PRODUCTION_ENVIRONMENTS
                    .contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Enabled continuous assessment scheduler cannot target a reserved production environment");
            }
        }
    }

    /** @return validated delay before the first local poll */
    public Duration initialDelay() {
        return Duration.ofMillis(
                initialDelayMillis);
    }

    /** @return validated delay between completed worker turns */
    public Duration pollInterval() {
        return Duration.ofMillis(
                pollIntervalMillis);
    }

    /** @return validated graceful shutdown drain interval */
    public Duration drainTimeout() {
        return Duration.ofMillis(
                drainTimeoutMillis);
    }

    private static void identifier(
            String value,
            int maximum,
            String field) {
        if (value.length() > maximum
                || !IDENTIFIER.matcher(
                value).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
    }

    private static void bounded(
            long value,
            long minimum,
            long maximum,
            String field) {
        if (value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded continuous assessment scheduler policy");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
