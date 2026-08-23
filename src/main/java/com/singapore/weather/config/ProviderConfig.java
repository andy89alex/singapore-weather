package com.singapore.weather.config;

import com.singapore.weather.service.ProviderChain;
import com.singapore.weather.service.WeatherProvider;
import com.singapore.weather.provider.openweathermap.OpenWeatherMapProvider;
import com.singapore.weather.provider.weatherstack.WeatherstackProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * A provider without an API key is disabled with a warning rather than failing
 * startup, so a reviewer holding only one key can still run the service. If no
 * provider is enabled at all, startup fails deliberately.
 */
@Slf4j
@Configuration
public class ProviderConfig {

    @Bean
    List<WeatherProvider> weatherProviders(WeatherProperties properties) {
        List<WeatherProvider> providers = new ArrayList<>();

        WeatherProperties.Provider ws = properties.providers().weatherstack();
        if (ws.isConfigured()) {
            providers.add(new WeatherstackProvider(RestClientConfig.forProvider(ws), ws.apiKey(), ws.priority()));
        } else {
            log.warn("Weatherstack disabled: WEATHERSTACK_API_KEY is not set");
        }

        WeatherProperties.Provider owm = properties.providers().openweathermap();
        if (owm.isConfigured()) {
            providers.add(new OpenWeatherMapProvider(RestClientConfig.forProvider(owm), owm.apiKey(), owm.priority()));
        } else {
            log.warn("OpenWeatherMap disabled: OPENWEATHERMAP_API_KEY is not set");
        }

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No weather provider is configured. Set WEATHERSTACK_API_KEY and/or OPENWEATHERMAP_API_KEY.");
        }
        return providers;
    }

    @Bean
    ProviderChain providerChain(List<WeatherProvider> providers,
                                CircuitBreakerRegistry breakers,
                                RetryRegistry retries,
                                WeatherProperties properties) {
        return new ProviderChain(providers, breakers, retries, properties.resilience().chainDeadline());
    }
}
