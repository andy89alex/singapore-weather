package com.singapore.weather.provider.weatherstack;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.singapore.weather.domain.WeatherProvider;
import org.springframework.web.client.RestClient;

import java.util.Set;

/**
 * Weatherstack adapter.
 *
 * <p>Weatherstack reports failures with HTTP 200 and {@code "success": false} in
 * the body, so the status code alone cannot be trusted — see {@link #fetch}.
 * Temperature is already Celsius and wind speed already km/h under the default
 * metric unit system, so no conversion is needed.
 */
public class WeatherstackProvider implements WeatherProvider {

    /** Weatherstack signals an unusable query with this error code. */
    private static final Set<Integer> CITY_NOT_FOUND_CODES = Set.of(615);

    private final RestClient restClient;
    private final String accessKey;
    private final int priority;

    public WeatherstackProvider(RestClient restClient, String accessKey, int priority) {
        this.restClient = restClient;
        this.accessKey = accessKey;
        this.priority = priority;
    }

    @Override
    public String name() {
        return "weatherstack";
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Weather fetch(String city) {
        WeatherstackResponse response;
        try {
            response = restClient.get()
                    .uri(builder -> builder.path("/current")
                            .queryParam("access_key", accessKey)
                            .queryParam("query", city)
                            .build())
                    .retrieve()
                    .body(WeatherstackResponse.class);
        } catch (RuntimeException e) {
            throw new ProviderException("Weatherstack call failed: " + e.getMessage(), e);
        }

        if (response == null) {
            throw new ProviderException("Weatherstack returned an empty body");
        }
        if (response.failed()) {
            throw toException(city, response.error());
        }
        if (response.current() == null
                || response.current().temperature() == null
                || response.current().windSpeed() == null) {
            throw new ProviderException("Weatherstack response is missing current readings");
        }

        return new Weather(response.current().temperature(), response.current().windSpeed());
    }

    private RuntimeException toException(String city, WeatherstackResponse.Error error) {
        if (error == null) {
            return new ProviderException("Weatherstack reported failure without an error body");
        }
        if (error.code() != null && CITY_NOT_FOUND_CODES.contains(error.code())) {
            return new CityNotFoundException(city);
        }
        // Unrecognised codes fail safe toward failover rather than a 404.
        return new ProviderException(
                "Weatherstack error %d (%s): %s".formatted(error.code(), error.type(), error.info()));
    }
}
