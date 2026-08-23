package com.singapore.weather.model;

import java.time.Duration;

/** A weather reading plus how old it is and whether it is being served stale. */
public record WeatherResult(Weather weather, boolean stale, Duration age) {

    public static WeatherResult fresh(Weather weather) {
        return new WeatherResult(weather, false, Duration.ZERO);
    }

    public static WeatherResult stale(Weather weather, Duration age) {
        return new WeatherResult(weather, true, age);
    }
}
