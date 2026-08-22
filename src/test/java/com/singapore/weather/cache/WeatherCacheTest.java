package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCacheTest {

    private static final Weather SINGAPORE = new Weather(29.0, 20.0);

    private MutableClock clock;
    private WeatherCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-22T00:00:00Z"));
        cache = new WeatherCache(clock, Duration.ofSeconds(3), Duration.ofHours(24), 1000);
    }

    @Test
    void missesWhenNothingWasStored() {
        assertThat(cache.find("singapore")).isEmpty();
    }

    @Test
    void treatsAnEntryYoungerThanTheFreshTtlAsFresh() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofMillis(2900));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.isFresh(entry)).isTrue();
        assertThat(entry.weather()).isEqualTo(SINGAPORE);
    }

    @Test
    void treatsAnEntryExactlyAtTheFreshTtlBoundaryAsFresh() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofMillis(3000));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.isFresh(entry))
                .as("isFresh uses age <= freshTtl, so age == freshTtl exactly must still be fresh")
                .isTrue();
    }

    @Test
    void treatsAnEntryOlderThanTheFreshTtlAsStale() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofMillis(3100));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.isFresh(entry)).isFalse();
    }

    @Test
    void stillReturnsAStaleEntryWithinTheRetentionWindow() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofHours(23));

        assertThat(cache.find("singapore")).isPresent();
    }

    @Test
    void treatsAnEntryBeyondTheRetentionWindowAsAbsent() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofHours(25));

        assertThat(cache.find("singapore")).isEmpty();
    }

    @Test
    void reportsTheAgeOfAnEntry() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofSeconds(7));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.age(entry)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void keysEntriesByCity() {
        cache.put("singapore", SINGAPORE);
        cache.put("jakarta", new Weather(32.0, 9.0));

        assertThat(cache.find("singapore").orElseThrow().weather()).isEqualTo(SINGAPORE);
        assertThat(cache.find("jakarta").orElseThrow().weather()).isEqualTo(new Weather(32.0, 9.0));
    }
}
