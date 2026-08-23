package com.singapore.weather.provider.openweathermap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenWeatherMapResponse(Main main, Wind wind) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Main(Double temp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Wind(Double speed) {
    }
}
