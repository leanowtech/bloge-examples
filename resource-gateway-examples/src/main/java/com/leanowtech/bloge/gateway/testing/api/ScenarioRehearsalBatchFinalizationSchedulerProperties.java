package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict local configuration for autonomous Scenario batch evidence finalization.
 *
 * @param enabled explicit scheduler activation
 * @param instanceId stable opaque replica identity
 * @param region exact regional partition
 * @param environmentId exact non-production environment partition
 * @param maximumPollers maximum process-local KMS preparation lanes
 * @param initialDelayMillis delay before first claim
 * @param pollIntervalMillis fixed delay after each completed turn
 * @param drainTimeoutMillis graceful shutdown bound
 */
@ConfigurationProperties(
        prefix = ScenarioRehearsalBatchFinalizationSchedulerProperties
                .PREFIX,
        ignoreUnknownFields = false)
public record ScenarioRehearsalBatchFinalizationSchedulerProperties(
        Boolean enabled,
        String instanceId,
        String region,
        String environmentId,
        Integer maximumPollers,
        Long initialDelayMillis,
        Long pollIntervalMillis,
        Long drainTimeoutMillis) {
    /** Configuration prefix shared by deployment manifests and capability documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.scenario-batch.finalization-scheduler";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Applies conservative KMS defaults and rejects partial or production activation. */
    public ScenarioRehearsalBatchFinalizationSchedulerProperties {
        enabled = Boolean.TRUE.equals(enabled);
        instanceId = normalized(instanceId);
        region = normalized(region).toLowerCase(Locale.ROOT);
        environmentId = normalized(
                environmentId).toLowerCase(Locale.ROOT);
        maximumPollers =
                maximumPollers == null ? 1 : maximumPollers;
        initialDelayMillis =
                initialDelayMillis == null
                        ? 1_000L : initialDelayMillis;
        pollIntervalMillis =
                pollIntervalMillis == null
                        ? 1_000L : pollIntervalMillis;
        drainTimeoutMillis =
                drainTimeoutMillis == null
                        ? 30_000L : drainTimeoutMillis;
        bound(maximumPollers, 1, 32, "maximumPollers");
        bound(
                initialDelayMillis,
                0,
                Duration.ofMinutes(5).toMillis(),
                "initialDelayMillis");
        bound(
                pollIntervalMillis,
                100,
                Duration.ofMinutes(1).toMillis(),
                "pollIntervalMillis");
        bound(
                drainTimeoutMillis,
                Duration.ofSeconds(1).toMillis(),
                Duration.ofHours(1).toMillis(),
                "drainTimeoutMillis");
        if (enabled) {
            identifier(instanceId, 255, "instanceId");
            identifier(region, 64, "region");
            identifier(
                    environmentId,
                    255,
                    "environmentId");
            if (!Set.of("test", "staging")
                    .contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Enabled Scenario batch finalization scheduler must target test or staging");
            }
        }
    }

    /** @return validated delay before the first local claim */
    public Duration initialDelay() {
        return Duration.ofMillis(initialDelayMillis);
    }

    /** @return validated fixed delay after each turn */
    public Duration pollInterval() {
        return Duration.ofMillis(pollIntervalMillis);
    }

    /** @return validated graceful shutdown bound */
    public Duration drainTimeout() {
        return Duration.ofMillis(drainTimeoutMillis);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void identifier(
            String value,
            int maximumLength,
            String field) {
        if (value.length() > maximumLength
                || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
    }

    private static void bound(
            long value,
            long minimum,
            long maximum,
            String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded finalization scheduler policy");
        }
    }
}
