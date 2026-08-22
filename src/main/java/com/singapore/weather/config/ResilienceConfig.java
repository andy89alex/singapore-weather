package com.singapore.weather.config;

import com.singapore.weather.domain.AuthenticationFailedException;
import com.singapore.weather.domain.CityNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j is wired by hand rather than through resilience4j-spring-boot3,
 * which still targets Spring Framework 6 while Boot 4.1 ships Spring Framework 7.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(WeatherProperties properties) {
        WeatherProperties.Resilience r = properties.resilience();
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(r.slidingWindowSize())
                .minimumNumberOfCalls(r.slidingWindowSize())
                .failureRateThreshold(r.failureRateThreshold())
                .waitDurationInOpenState(r.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(r.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // A city the provider does not know is a client error, not an outage.
                .ignoreExceptions(CityNotFoundException.class)
                .build());
    }

    @Bean
    RetryRegistry retryRegistry(WeatherProperties properties) {
        WeatherProperties.Resilience r = properties.resilience();
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(r.retryMaxAttempts())
                .waitDuration(r.retryWaitDuration())
                // Retrying will not teach a provider a city it has never heard of, nor
                // will it fix a bad API key — both fail the same way every time, so
                // retrying only doubles latency before failover. Note that
                // AuthenticationFailedException is intentionally NOT ignored on the
                // circuit breaker: an unusable provider is genuinely unhealthy and
                // must still count as a failure there.
                .ignoreExceptions(CityNotFoundException.class, AuthenticationFailedException.class)
                .build());
    }

    @Bean
    TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry registry,
                                                      MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    @Bean
    TaggedRetryMetrics retryMetrics(RetryRegistry registry, MeterRegistry meterRegistry) {
        TaggedRetryMetrics metrics = TaggedRetryMetrics.ofRetryRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
