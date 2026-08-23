package com.singapore.weather.service;

import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.model.Weather;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Calls providers in priority order, wrapping each in its own circuit breaker
 * and retry. A provider whose circuit is open is skipped immediately rather
 * than waiting for a timeout.
 *
 * <p>The whole chain is bounded by {@code chainDeadline}: with multiple
 * providers each carrying their own retries, the worst case of trying every
 * provider is significantly more than one provider's timeout. The deadline is
 * checked before starting each provider — an attempt already in flight is
 * never interrupted — so a caller is never left waiting for a provider that
 * could not plausibly start and finish within the remaining budget.
 */
@Slf4j
public class ProviderChain {

    private final List<WeatherProvider> providers;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final Duration chainDeadline;

    public ProviderChain(List<WeatherProvider> providers,
                         CircuitBreakerRegistry breakers,
                         RetryRegistry retries,
                         Duration chainDeadline) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(WeatherProvider::priority))
                .toList();
        this.breakers = breakers;
        this.retries = retries;
        this.chainDeadline = chainDeadline;
    }

    public Weather fetch(String city) {
        boolean everyFailureWasCityNotFound = true;
        boolean anyProviderAttempted = false;
        long start = System.nanoTime();
        long deadlineNanos = chainDeadline.toNanos();

        for (WeatherProvider provider : providers) {
            if (System.nanoTime() - start >= deadlineNanos) {
                log.warn("Chain deadline of {} exhausted before trying provider {} for city {}; "
                        + "not attempting it", chainDeadline, provider.name(), city);
                break;
            }
            anyProviderAttempted = true;
            try {
                return call(provider, city);
            } catch (CityNotFoundException e) {
                log.debug("Provider {} does not recognise city {}", provider.name(), city);
            } catch (RuntimeException e) {
                everyFailureWasCityNotFound = false;
                log.warn("Provider {} failed for city {}: {}", provider.name(), city, e.toString());
            }
        }

        if (anyProviderAttempted && everyFailureWasCityNotFound) {
            throw new CityNotFoundException(city);
        }
        throw new AllProvidersFailedException("All providers failed for city: " + city);
    }

    private Weather call(WeatherProvider provider, String city) {
        CircuitBreaker breaker = breakers.circuitBreaker(provider.name());
        Retry retry = retries.retry(provider.name());

        Supplier<Weather> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> provider.fetch(city)));

        return decorated.get();
    }
}
