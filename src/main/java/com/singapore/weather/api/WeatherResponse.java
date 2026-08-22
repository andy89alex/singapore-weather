package com.singapore.weather.api;

import com.singapore.weather.domain.Weather;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The public contract: exactly two fields, in this order, as whole numbers.
 * Rounding here rather than in the domain keeps the response identical no
 * matter which provider answered.
 */
@JsonPropertyOrder({"wind_speed", "temperature_degrees"})
public record WeatherResponse(
        @JsonProperty("wind_speed") long windSpeed,
        @JsonProperty("temperature_degrees") long temperatureDegrees) {

    public static WeatherResponse from(Weather weather) {
        return new WeatherResponse(
                Math.round(weather.windSpeedKmh()),
                Math.round(weather.temperatureCelsius()));
    }
}
