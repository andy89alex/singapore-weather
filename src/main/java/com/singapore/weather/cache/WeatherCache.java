package com.singapore.weather.cache;

import com.singapore.weather.model.CachedWeather;
import com.singapore.weather.model.Weather;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
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

    /**
     * The most recent failed refresh on each stripe. A caller that waited for the
     * lock and then found the cache empty needs to know whether the holder it
     * waited on was working on <em>its</em> city and failed, or on a different
     * city that merely shares the stripe. Without the city recorded here, a
     * stripe collision would make an untried city fail immediately.
     */
    private final AtomicReferenceArray<FailedRefresh> lastFailure = new AtomicReferenceArray<>(STRIPES);

    private record FailedRefresh(String city, Instant at) {
    }

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
    /** Records that a refresh of this city just failed with nothing to fall back on. */
    public void recordFailedRefresh(String city) {
        lastFailure.set(stripeIndex(city), new FailedRefresh(city, clock.instant()));
    }

    /**
     * True when a refresh of this exact city failed within {@code within}. Callers
     * that just waited on the lock use this to avoid repeating a chain that failed
     * moments ago — which would otherwise serialise one full chain per waiter.
     */
    public boolean refreshJustFailed(String city, Duration within) {
        FailedRefresh failure = lastFailure.get(stripeIndex(city));
        return failure != null
                && failure.city().equals(city)
                && Duration.between(failure.at(), clock.instant()).compareTo(within) <= 0;
    }

    /** Clears the marker once this city is known good again. */
    public void clearFailedRefresh(String city) {
        FailedRefresh failure = lastFailure.get(stripeIndex(city));
        if (failure != null && failure.city().equals(city)) {
            lastFailure.compareAndSet(stripeIndex(city), failure, null);
        }
    }

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
        return stripes[stripeIndex(city)];
    }

    private static int stripeIndex(String city) {
        return Math.floorMod(city.hashCode(), STRIPES);
    }
}
