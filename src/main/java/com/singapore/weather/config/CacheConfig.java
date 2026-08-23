package com.singapore.weather.config;

import com.singapore.weather.cache.WeatherCache;
import com.singapore.weather.domain.ProviderChain;
import com.singapore.weather.domain.WeatherService;
import com.singapore.weather.domain.WeatherServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CacheConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WeatherCache weatherCache(Clock clock, WeatherProperties properties) {
        WeatherProperties.Cache cache = properties.cache();
        return new WeatherCache(clock, cache.freshTtl(), cache.staleRetention(), cache.maxSize());
    }

    /**
     * Publishes hit rate, size and eviction counts under the {@code cache.*}
     * meters. Without this binding {@code recordStats()} collects numbers nobody
     * can see.
     */
    @Bean
    InitializingBean weatherCacheMetrics(WeatherCache cache, MeterRegistry meterRegistry) {
        return () -> CaffeineCacheMetrics.monitor(meterRegistry, cache.caffeine(), "weather");
    }

    @Bean
    WeatherService weatherService(WeatherCache cache, ProviderChain chain,
                                  WeatherProperties properties) {
        return new WeatherServiceImpl(cache, chain, properties.cache().coldRefreshWait());
    }
}
