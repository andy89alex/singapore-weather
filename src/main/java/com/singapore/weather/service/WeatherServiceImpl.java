package com.singapore.weather.service;

import com.singapore.weather.cache.WeatherCache;
import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.model.CachedWeather;
import com.singapore.weather.model.Weather;
import com.singapore.weather.model.WeatherResult;
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

        // Nothing was stored, so the holder we waited on did not succeed. If it was
        // working on *this* city and failed, running the same chain again now would
        // fail the same way — and because each waiter would do it in turn, every
        // caller would pay a full chain one after another. Give up instead.
        //
        // The city is checked, not just the stripe: 64 stripes are shared by many
        // cities, and a collision must not make an untried city fail.
        if (filled.isEmpty() && cache.refreshJustFailed(city, coldRefreshWait)) {
            throw new AllProvidersFailedException(
                    "No weather provider could be reached for '" + city
                            + "', and no earlier reading is available to fall back on."
                            + " Retry after the interval given in the Retry-After header.");
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
                        "A refresh of '" + city + "' is already in progress but did not"
                                + " finish in time, and no earlier reading is available."
                                + " Retry after the interval given in the Retry-After header."));
    }

    private WeatherResult refresh(String city, Optional<CachedWeather> fallback) {
        try {
            Weather weather = chain.fetch(city);
            cache.put(city, weather);
            cache.clearFailedRefresh(city);
            return WeatherResult.fresh(weather);
        } catch (AllProvidersFailedException e) {
            // Tell anyone waiting on this city's lock that the chain just failed, so
            // they do not repeat it in turn. Recorded before the fallback check: a
            // caller served stale still learns the providers are down.
            cache.recordFailedRefresh(city);
            CachedWeather entry = fallback.orElseThrow(() -> e);
            log.warn("Serving stale weather for {} — every provider failed", city);
            return WeatherResult.stale(entry.weather(), cache.age(entry));
        }
    }
}
