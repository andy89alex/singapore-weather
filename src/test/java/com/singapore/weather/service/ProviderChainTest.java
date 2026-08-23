package com.singapore.weather.service;

import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.exception.AuthenticationFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.exception.ProviderException;
import com.singapore.weather.model.Weather;
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

    /** Generous enough that it never interferes with tests that are not about the deadline itself. */
    private static final Duration GENEROUS_DEADLINE = Duration.ofSeconds(30);

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
        // maxAttempts(2) rather than 1: with only one attempt allowed,
        // "CityNotFoundException is never retried" would be proven by
        // configuration rather than by behaviour. Keeping it at 2 lets the
        // relevant tests prove the ignoreExceptions wiring actually works.
        retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .ignoreExceptions(CityNotFoundException.class, AuthenticationFailedException.class)
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
                }, new Weather(29, 20))), breakers, retries, GENEROUS_DEADLINE);

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
                }, new Weather(28, 15))), breakers, retries, GENEROUS_DEADLINE);

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
                }, null)), breakers, retries, GENEROUS_DEADLINE);

        assertThatThrownBy(() -> chain.fetch("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void triesEveryProviderBeforeReportingCityNotFound() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    primaryCalls.incrementAndGet();
                    throw new CityNotFoundException("atlantis");
                }, null),
                provider("secondary", 2, () -> {
                    secondCalls.incrementAndGet();
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries, GENEROUS_DEADLINE);

        assertThatThrownBy(() -> chain.fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
        // maxAttempts is 2, so a call count of 1 proves the retry actually
        // skipped CityNotFoundException rather than merely being configured
        // to allow only one attempt in the first place.
        assertThat(primaryCalls)
                .as("CityNotFoundException must not be retried even though maxAttempts allows it")
                .hasValue(1);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void cityNotFoundNeverOpensTheCircuit() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries, GENEROUS_DEADLINE);

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
                }, new Weather(28, 15))), breakers, retries, GENEROUS_DEADLINE);

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

    @Test
    void authenticationFailureIsNotRetriedButStillFailsOver() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    primaryCalls.incrementAndGet();
                    throw new AuthenticationFailedException("bad key");
                }, null),
                provider("secondary", 2, secondCalls::incrementAndGet, new Weather(28, 15))),
                breakers, retries, GENEROUS_DEADLINE);

        Weather weather = chain.fetch("singapore");

        assertThat(weather).isEqualTo(new Weather(28, 15));
        // maxAttempts is 2: a value of 1 proves the retry policy really
        // excludes AuthenticationFailedException rather than the test merely
        // being unable to distinguish retried-once-and-gave-up from
        // never-retried.
        assertThat(primaryCalls)
                .as("a bad API key must not be retried")
                .hasValue(1);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void stopsIteratingOnceTheChainDeadlineIsExhausted() {
        // maxAttempts(3) with a real 60ms wait between attempts costs primary
        // roughly 120ms of genuine wall-clock time (two waits between three
        // attempts), comfortably clearing the 50ms chain deadline before the
        // loop ever reaches secondary.
        RetryRegistry slowRetries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(60))
                .ignoreExceptions(CityNotFoundException.class, AuthenticationFailedException.class)
                .build());
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, secondCalls::incrementAndGet, new Weather(28, 15))),
                breakers, slowRetries, Duration.ofMillis(50));

        assertThatThrownBy(() -> chain.fetch("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
        assertThat(secondCalls)
                .as("the chain deadline should be exhausted by primary's retries, so secondary is skipped")
                .hasValue(0);
    }
}
