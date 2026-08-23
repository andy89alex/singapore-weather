package com.singapore.weather.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(@Valid Cache cache, @Valid Resilience resilience, @Valid Providers providers) {

    public record Cache(
            // Deliberately unconstrained: the integration tests set this to -1s to
            // defeat the cache without introducing a zero-TTL race (isFresh() tests
            // age <= freshTtl, so a 0s TTL would still let two requests landing in
            // the same clock tick both count as fresh). Do not add @NotNull/positive
            // constraints here or those tests will fail to start the context.
            Duration freshTtl,
            @NotNull Duration staleRetention,
            @Positive int maxSize,
            @NotNull Duration coldRefreshWait) {
    }

    public record Resilience(
            @Positive int slidingWindowSize,
            @Positive int minimumNumberOfCalls,
            @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            @NotNull Duration waitDurationInOpenState,
            @Positive int permittedCallsInHalfOpenState,
            @Positive int retryMaxAttempts,
            @NotNull Duration retryWaitDuration,
            @NotNull Duration chainDeadline) {
    }

    public record Providers(@Valid Provider weatherstack, @Valid Provider openweathermap) {
    }

    public record Provider(
            @Positive int priority,
            @NotBlank String baseUrl,
            String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
