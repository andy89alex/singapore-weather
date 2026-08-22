package com.singapore.weather.domain;

import com.singapore.weather.cache.MutableClock;
import com.singapore.weather.cache.WeatherCache;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherServiceImplTest {

    private static final Weather FRESH = new Weather(29.0, 20.0);
    private static final Weather NEWER = new Weather(30.0, 22.0);

    private MutableClock clock;
    private WeatherCache cache;
    private AtomicInteger providerCalls;
    private AtomicReference<RuntimeException> providerFailure;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-22T00:00:00Z"));
        cache = new WeatherCache(clock, Duration.ofSeconds(3), Duration.ofHours(24), 1000);
        providerCalls = new AtomicInteger();
        providerFailure = new AtomicReference<>();
    }

    private WeatherServiceImpl service(Weather result) {
        WeatherProvider provider = new WeatherProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public Weather fetch(String city) {
                providerCalls.incrementAndGet();
                RuntimeException failure = providerFailure.get();
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().ignoreExceptions(CityNotFoundException.class).build());
        RetryRegistry retries = RetryRegistry.of(
                RetryConfig.custom().maxAttempts(1).build());
        return new WeatherServiceImpl(cache, new ProviderChain(List.of(provider), breakers, retries));
    }

    @Test
    void fetchesFromTheProviderOnAColdCache() {
        WeatherResult result = service(FRESH).get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isFalse();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void servesFromCacheWithinTheFreshWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofMillis(2900));
        WeatherResult result = service.get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isFalse();
        assertThat(providerCalls)
                .as("a fresh entry must not reach the provider")
                .hasValue(1);
    }

    @Test
    void refreshesOncePastTheFreshWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofMillis(3100));
        service.get("singapore");

        assertThat(providerCalls).hasValue(2);
    }

    @Test
    void servesStaleWhenEveryProviderIsDown() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofSeconds(10));
        providerFailure.set(new ProviderException("down"));
        WeatherResult result = service.get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isTrue();
        assertThat(result.age()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void raisesWhenEveryProviderIsDownAndNothingWasEverCached() {
        WeatherServiceImpl service = service(FRESH);
        providerFailure.set(new ProviderException("down"));

        assertThatThrownBy(() -> service.get("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void raisesWhenTheStaleEntryIsBeyondTheRetentionWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofHours(25));
        providerFailure.set(new ProviderException("down"));

        assertThatThrownBy(() -> service.get("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void propagatesCityNotFoundWithoutServingStale() {
        WeatherServiceImpl service = service(NEWER);
        providerFailure.set(new CityNotFoundException("atlantis"));

        assertThatThrownBy(() -> service.get("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }
}
