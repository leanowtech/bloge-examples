package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict local runtime configuration for autonomous Scenario rehearsal batch scheduling.
 *
 * <p>Scheduling is disabled by default and may target only one exact {@code test} or
 * {@code staging} regional partition per process. Durable policy still owns fleet-wide capacity;
 * these values only bound process-local polling and graceful shutdown.</p>
 *
 * @param enabled explicit scheduler activation switch
 * @param instanceId stable opaque replica identity used in lease owner fences
 * @param region exact regional partition served by this process
 * @param environmentId exact non-production environment partition
 * @param maximumPollers process-local synchronous worker lanes
 * @param initialDelayMillis delay before first claim
 * @param pollIntervalMillis fixed delay after each completed turn
 * @param drainTimeoutMillis bounded shutdown drain interval
 */
@ConfigurationProperties(
        prefix = ScenarioRehearsalBatchSchedulerProperties.PREFIX,
        ignoreUnknownFields = false)
public record ScenarioRehearsalBatchSchedulerProperties(
        Boolean enabled,
        String instanceId,
        String region,
        String environmentId,
        Integer maximumPollers,
        Long initialDelayMillis,
        Long pollIntervalMillis,
        Long drainTimeoutMillis) {
    /** Configuration prefix shared by application YAML, tests, and capability documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.scenario-batch.scheduler";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Applies finite defaults and rejects partial or production-targeted activation. */
    public ScenarioRehearsalBatchSchedulerProperties {
        enabled = Boolean.TRUE.equals(enabled);
        instanceId = normalized(instanceId);
        region = normalized(region).toLowerCase(Locale.ROOT);
        environmentId = normalized(
                environmentId).toLowerCase(Locale.ROOT);
        maximumPollers =
                maximumPollers == null ? 4 : maximumPollers;
        initialDelayMillis =
                initialDelayMillis == null
                        ? 1_000L : initialDelayMillis;
        pollIntervalMillis =
                pollIntervalMillis == null
                        ? 1_000L : pollIntervalMillis;
        drainTimeoutMillis =
                drainTimeoutMillis == null
                        ? 30_000L : drainTimeoutMillis;
        validateBound(
                maximumPollers,
                1,
                256,
                "maximumPollers");
        validateBound(
                initialDelayMillis,
                0,
                Duration.ofMinutes(5).toMillis(),
                "initialDelayMillis");
        validateBound(
                pollIntervalMillis,
                100,
                Duration.ofMinutes(1).toMillis(),
                "pollIntervalMillis");
        validateBound(
                drainTimeoutMillis,
                Duration.ofSeconds(1).toMillis(),
                Duration.ofHours(1).toMillis(),
                "drainTimeoutMillis");
        if (enabled) {
            requireIdentifier(
                    instanceId, 255, "instanceId");
            requireIdentifier(region, 64, "region");
            requireIdentifier(
                    environmentId,
                    255,
                    "environmentId");
            if (!Set.of("test", "staging")
                    .contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Enabled Scenario batch scheduler must target test or staging");
            }
        }
    }

    /** @return validated delay before the first local claim */
    public Duration initialDelay() {
        return Duration.ofMillis(initialDelayMillis);
    }

    /** @return validated fixed delay between local claims */
    public Duration pollInterval() {
        return Duration.ofMillis(pollIntervalMillis);
    }

    /** @return validated graceful shutdown drain interval */
    public Duration drainTimeout() {
        return Duration.ofMillis(drainTimeoutMillis);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireIdentifier(
            String value,
            int maximumLength,
            String field) {
        if (value.length() > maximumLength
                || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
    }

    private static void validateBound(
            long value,
            long minimum,
            long maximum,
            String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded Scenario batch scheduler policy");
        }
    }
}
