package com.singapore.weather.domain;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderChainTest {

    private CircuitBreakerRegistry breakers;
    private RetryRegistry retries;

    @BeforeEach
    void setUp() {
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .ignoreExceptions(CityNotFoundException.class)
                .build());
        retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1)
                .ignoreExceptions(CityNotFoundException.class)
                .build());
    }

    private static WeatherProvider provider(String name, int priority, Runnable onCall, Weather result) {
        return new WeatherProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Weather fetch(String city) {
                onCall.run();
                return result;
            }
        };
    }

    @Test
    void usesTheHighestPriorityProviderFirst() {
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("secondary", 2, secondCalls::incrementAndGet, new Weather(1, 1)),
                provider("primary", 1, () -> {
                }, new Weather(29, 20))), breakers, retries);

        Weather weather = chain.fetch("singapore");

        assertThat(weather).isEqualTo(new Weather(29, 20));
        assertThat(secondCalls).hasValue(0);
    }

    @Test
    void fallsBackToTheNextProviderWhenTheFirstFails() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                }, new Weather(28, 15))), breakers, retries);

        assertThat(chain.fetch("singapore")).isEqualTo(new Weather(28, 15));
    }

    @Test
    void throwsWhenEveryProviderFails() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                    throw new ProviderException("also down");
                }, null)), breakers, retries);

        assertThatThrownBy(() -> chain.fetch("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void triesEveryProviderBeforeReportingCityNotFound() {
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new CityNotFoundException("atlantis");
                }, null),
                provider("secondary", 2, () -> {
                    secondCalls.incrementAndGet();
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries);

        assertThatThrownBy(() -> chain.fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void cityNotFoundNeverOpensTheCircuit() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries);

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> chain.fetch("atlantis"))
                    .isInstanceOf(CityNotFoundException.class);
        }

        assertThat(breakers.circuitBreaker("primary").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedProviderFailuresOpenTheCircuitAndStopCallingIt() {
        AtomicInteger calls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    calls.incrementAndGet();
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                }, new Weather(28, 15))), breakers, retries);

        for (int i = 0; i < 4; i++) {
            chain.fetch("singapore");
        }
        int callsBeforeOpen = calls.get();

        chain.fetch("singapore");

        assertThat(breakers.circuitBreaker("primary").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(calls.get())
                .as("an open circuit must not reach the provider at all")
                .isEqualTo(callsBeforeOpen);
    }
}
