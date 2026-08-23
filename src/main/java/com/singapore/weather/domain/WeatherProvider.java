package com.singapore.weather.domain;

/**
 * One weather vendor. Implementations translate their vendor's failure
 * vocabulary into {@link ProviderException} (infrastructure trouble, counts
 * against the circuit breaker) or {@link CityNotFoundException} (client error,
 * never counts against the circuit breaker).
 */
public interface WeatherProvider {

    String name();

    /** Lower runs first. */
    int priority();

    Weather fetch(String city);
}
