package com.singapore.weather.provider.weatherstack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherstackResponse(Boolean success, Error error, Current current) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("wind_speed") Double windSpeed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(Integer code, String type, String info) {
    }

    /** Weatherstack omits {@code success} on success and sets it to false on failure. */
    public boolean failed() {
        return Boolean.FALSE.equals(success);
    }
}
