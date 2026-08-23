package com.singapore.weather.domain;

import com.singapore.weather.cache.CachedWeather;
import com.singapore.weather.cache.WeatherCache;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

@Slf4j
public class WeatherServiceImpl implements WeatherService {

    private final WeatherCache cache;
    private final ProviderChain chain;
    private final Duration coldRefreshWait;

    public WeatherServiceImpl(WeatherCache cache, ProviderChain chain, Duration coldRefreshWait) {
        this.cache = cache;
        this.chain = chain;
        this.coldRefreshWait = coldRefreshWait;
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

        // Another caller is already refreshing this city.
        if (cached.isPresent()) {
            // We have something to serve, so do not queue behind them.
            return WeatherResult.stale(cached.get().weather(), cache.age(cached.get()));
        }

        // Cold cache: nothing to serve. Wait for the in-flight refresh rather than
        // making our own unsynchronised provider call, which would reintroduce the
        // stampede at start-up.
        return cache.tryRefresh(city, coldRefreshWait, () -> refreshUnlessAlreadyFilled(city))
                .orElseGet(() -> serveWhatIsCachedAfterTimeout(city));
    }

    /** Runs with the lock held, so the caller we waited on may already have filled the cache. */
    private WeatherResult refreshUnlessAlreadyFilled(String city) {
        Optional<CachedWeather> filled = cache.find(city);
        if (filled.isPresent() && cache.isFresh(filled.get())) {
            return WeatherResult.fresh(filled.get().weather());
        }
        return refresh(city, filled);
    }

    /** The wait timed out. Serve whatever landed in the cache meanwhile, or admit defeat. */
    private WeatherResult serveWhatIsCachedAfterTimeout(String city) {
        return cache.find(city)
                .map(entry -> cache.isFresh(entry)
                        ? WeatherResult.fresh(entry.weather())
                        : WeatherResult.stale(entry.weather(), cache.age(entry)))
                .orElseThrow(() -> new AllProvidersFailedException(
                        "Timed out waiting for an in-flight refresh of city: " + city));
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
