package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict activation properties for continuous production outcome source polling. */
@ConfigurationProperties(
        prefix = AuthoritativeOutcomeSourceSchedulerProperties.PREFIX,
        ignoreUnknownFields = false)
public record AuthoritativeOutcomeSourceSchedulerProperties(
        Boolean enabled,
        String instanceId,
        String region,
        String environmentId,
        Integer maximumPollers,
        Long initialDelayMillis,
        Long pollIntervalMillis,
        Long drainTimeoutMillis
) {
    /** Configuration prefix for source scheduling. */
    public static final String PREFIX =
            "gateway.testing.mirror.outcome-source.scheduler";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Set<String> RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    /** Applies bounded defaults and rejects partial production-targeted activation. */
    public AuthoritativeOutcomeSourceSchedulerProperties {
        enabled = Boolean.TRUE.equals(enabled);
        instanceId = normalized(instanceId);
        region = normalized(region).toLowerCase(Locale.ROOT);
        environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
        maximumPollers = maximumPollers == null ? 2 : maximumPollers;
        initialDelayMillis = initialDelayMillis == null ? 1_000L : initialDelayMillis;
        pollIntervalMillis = pollIntervalMillis == null ? 1_000L : pollIntervalMillis;
        drainTimeoutMillis = drainTimeoutMillis == null ? 30_000L : drainTimeoutMillis;
        bounded(maximumPollers, 1, 64, "maximumPollers");
        bounded(initialDelayMillis, 0, Duration.ofMinutes(5).toMillis(),
                "initialDelayMillis");
        bounded(pollIntervalMillis, 100, Duration.ofMinutes(1).toMillis(),
                "pollIntervalMillis");
        bounded(drainTimeoutMillis, Duration.ofSeconds(1).toMillis(),
                Duration.ofHours(1).toMillis(), "drainTimeoutMillis");
        if (enabled) {
            identifier(instanceId, 255, "instanceId");
            identifier(region, 96, "region");
            identifier(environmentId, 255, "environmentId");
            if (RESERVED_PRODUCTION_ENVIRONMENTS.contains(environmentId)) {
                throw new IllegalArgumentException(
                        "enabled outcome source scheduler cannot target production");
            }
        }
    }

    /** @return validated first-poll delay */
    public Duration initialDelay() {
        return Duration.ofMillis(initialDelayMillis);
    }

    /** @return validated fixed delay */
    public Duration pollInterval() {
        return Duration.ofMillis(pollIntervalMillis);
    }

    /** @return validated graceful drain interval */
    public Duration drainTimeout() {
        return Duration.ofMillis(drainTimeoutMillis);
    }

    private static void identifier(String value, int maximum, String field) {
        if (value.length() > maximum || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void bounded(long value, long min, long max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " is outside the bounded source policy");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
