package com.singapore.weather.domain;

import com.singapore.weather.cache.CachedWeather;
import com.singapore.weather.cache.WeatherCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Deliberately carries no stereotype annotation yet. Its collaborators become
 * Spring beans in Task 11; annotating it here would fail every context-loading
 * test for four tasks and hide real regressions behind an expected red suite.
 */
public class WeatherServiceImpl implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherServiceImpl.class);

    private final WeatherCache cache;
    private final ProviderChain chain;

    public WeatherServiceImpl(WeatherCache cache, ProviderChain chain) {
        this.cache = cache;
        this.chain = chain;
    }

    @Override
    public WeatherResult get(String city) {
        Optional<CachedWeather> cached = cache.find(city);

        if (cached.isPresent() && cache.isFresh(cached.get())) {
            return WeatherResult.fresh(cached.get().weather());
        }

        Optional<WeatherResult> refreshed = cache.tryRefresh(city, () -> refresh(city, cached));
        if (refreshed.isPresent()) {
            return refreshed.get();
        }

        // Another caller is already refreshing this city. Do not queue behind it.
        return cached.map(entry -> WeatherResult.stale(entry.weather(), cache.age(entry)))
                .orElseGet(() -> refresh(city, Optional.empty()));
    }

    private WeatherResult refresh(String city, Optional<CachedWeather> fallback) {
        try {
            Weather weather = chain.fetch(city);
            cache.put(city, weather);
            return WeatherResult.fresh(weather);
        } catch (AllProvidersFailedException e) {
            CachedWeather entry = fallback.orElseThrow(() -> e);
            log.warn("Serving stale weather for {} — every provider failed", city);
            return WeatherResult.stale(entry.weather(), cache.age(entry));
        }
    }
}
