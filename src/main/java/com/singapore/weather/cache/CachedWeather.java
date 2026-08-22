package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;

import java.time.Instant;

public record CachedWeather(Weather weather, Instant fetchedAt) {
}
