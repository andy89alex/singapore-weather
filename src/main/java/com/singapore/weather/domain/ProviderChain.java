package com.singapore.weather.domain;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Calls providers in priority order, wrapping each in its own circuit breaker
 * and retry. A provider whose circuit is open is skipped immediately rather
 * than waiting for a timeout.
 */
public class ProviderChain {

    private static final Logger log = LoggerFactory.getLogger(ProviderChain.class);

    private final List<WeatherProvider> providers;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public ProviderChain(List<WeatherProvider> providers,
                         CircuitBreakerRegistry breakers,
                         RetryRegistry retries) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(WeatherProvider::priority))
                .toList();
        this.breakers = breakers;
        this.retries = retries;
    }

    public Weather fetch(String city) {
        boolean everyFailureWasCityNotFound = true;

        for (WeatherProvider provider : providers) {
            try {
                return call(provider, city);
            } catch (CityNotFoundException e) {
                log.debug("Provider {} does not recognise city {}", provider.name(), city);
            } catch (RuntimeException e) {
                everyFailureWasCityNotFound = false;
                log.warn("Provider {} failed for city {}: {}", provider.name(), city, e.toString());
            }
        }

        if (everyFailureWasCityNotFound && !providers.isEmpty()) {
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
