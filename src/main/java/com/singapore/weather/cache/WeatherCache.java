package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A single Caffeine cache with two time horizons: entries younger than the
 * fresh TTL are served directly, older entries are refresh candidates that
 * remain available as stale data until the retention window expires.
 */
public class WeatherCache {

    private static final int STRIPES = 64;

    private final Cache<String, CachedWeather> cache;
    private final Clock clock;
    private final Duration freshTtl;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    public WeatherCache(Clock clock, Duration freshTtl, Duration staleRetention, int maxSize) {
        Arrays.setAll(stripes, i -> new ReentrantLock());
        this.clock = clock;
        this.freshTtl = freshTtl;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(staleRetention)
                .maximumSize(maxSize)
                .recordStats()
                .ticker(() -> clock.instant().toEpochMilli() * 1_000_000L)
                .build();
    }

    public Optional<CachedWeather> find(String city) {
        return Optional.ofNullable(cache.getIfPresent(city));
    }

    public void put(String city, Weather weather) {
        cache.put(city, new CachedWeather(weather, clock.instant()));
    }

    public boolean isFresh(CachedWeather entry) {
        return age(entry).compareTo(freshTtl) <= 0;
    }

    public Duration age(CachedWeather entry) {
        return Duration.between(entry.fetchedAt(), clock.instant());
    }

    public Cache<String, CachedWeather> caffeine() {
        return cache;
    }

    /**
     * Runs {@code refresh} only if this caller wins the city's lock. Losers get
     * an empty result immediately instead of queueing, so a burst of concurrent
     * requests produces one upstream call rather than one per request.
     */
    public <T> Optional<T> tryRefresh(String city, Supplier<T> refresh) {
        return tryRefresh(city, Duration.ZERO, refresh);
    }

    /**
     * As above, but waits up to {@code maxWait} for the lock. Callers with a
     * stale value to fall back on pass {@link Duration#ZERO} and never wait;
     * callers with nothing to serve wait, because returning immediately would
     * mean either failing a request the providers could answer or making an
     * unsynchronised call that reintroduces the stampede on a cold cache.
     */
    public <T> Optional<T> tryRefresh(String city, Duration maxWait, Supplier<T> refresh) {
        ReentrantLock lock = lockFor(city);
        boolean acquired;
        try {
            acquired = maxWait.isZero()
                    ? lock.tryLock()
                    : lock.tryLock(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquired) {
            return Optional.empty();
        }
        try {
            return Optional.of(refresh.get());
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String city) {
        return stripes[Math.floorMod(city.hashCode(), STRIPES)];
    }
}
