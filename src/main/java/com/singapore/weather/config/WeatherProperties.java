package com.singapore.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(Cache cache, Resilience resilience, Providers providers) {

    public record Cache(Duration freshTtl, Duration staleRetention, int maxSize,
                        Duration coldRefreshWait) {
    }

    public record Resilience(
            int slidingWindowSize,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState,
            int retryMaxAttempts,
            Duration retryWaitDuration) {
    }

    public record Providers(Provider weatherstack, Provider openweathermap) {
    }

    public record Provider(
            int priority,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
