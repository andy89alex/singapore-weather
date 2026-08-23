package com.singapore.weather.model;

import java.time.Instant;

public record CachedWeather(Weather weather, Instant fetchedAt) {
}
