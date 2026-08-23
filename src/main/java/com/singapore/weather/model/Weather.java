package com.singapore.weather.model;

/**
 * Provider-agnostic weather reading. Values are unrounded; rounding happens
 * at the API boundary so the domain keeps full precision.
 */
public record Weather(double temperatureCelsius, double windSpeedKmh) {

    private static final double KMH_PER_MPS = 3.6;

    public static Weather ofMetresPerSecond(double temperatureCelsius, double windSpeedMps) {
        return new Weather(temperatureCelsius, windSpeedMps * KMH_PER_MPS);
    }
}
