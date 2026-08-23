package com.singapore.weather.provider.openweathermap;

import com.singapore.weather.exception.AuthenticationFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.exception.ProviderException;
import com.singapore.weather.model.Weather;
import com.singapore.weather.service.WeatherProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * OpenWeatherMap adapter.
 *
 * <p>The request asks for {@code units=metric}, which returns Celsius but leaves
 * wind speed in metres per second. The conversion to km/h happens here so the
 * rest of the system only ever sees km/h.
 */
public class OpenWeatherMapProvider implements WeatherProvider {

    private final RestClient restClient;
    private final String apiKey;
    private final int priority;

    public OpenWeatherMapProvider(RestClient restClient, String apiKey, int priority) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.priority = priority;
    }

    @Override
    public String name() {
        return "openweathermap";
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Weather fetch(String city) {
        OpenWeatherMapResponse response;
        try {
            response = restClient.get()
                    .uri(builder -> builder.path("/data/2.5/weather")
                            .queryParam("q", city)
                            .queryParam("appid", apiKey)
                            .queryParam("units", "metric")
                            .build())
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, clientResponse) -> {
                                throw new CityNotFoundException(city);
                            })
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, clientResponse) -> {
                                throw new AuthenticationFailedException(
                                        "OpenWeatherMap rejected the API key (HTTP " + clientResponse.getStatusCode()
                                                .value() + ")");
                            })
                    .body(OpenWeatherMapResponse.class);
        } catch (CityNotFoundException | AuthenticationFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderException("OpenWeatherMap call failed: " + e.getMessage(), e);
        }

        if (response == null
                || response.main() == null || response.main().temp() == null
                || response.wind() == null || response.wind().speed() == null) {
            throw new ProviderException("OpenWeatherMap response is missing readings");
        }

        return Weather.ofMetresPerSecond(response.main().temp(), response.wind().speed());
    }
}
