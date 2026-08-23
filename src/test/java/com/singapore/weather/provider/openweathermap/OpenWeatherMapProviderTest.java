package com.singapore.weather.provider.openweathermap;

import com.singapore.weather.exception.AuthenticationFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.exception.ProviderException;
import com.singapore.weather.model.Weather;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OpenWeatherMapProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OpenWeatherMapProvider provider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient client = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(factory)
                .build();
        return new OpenWeatherMapProvider(client, "test-key", 2);
    }

    private void stub(int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/data/2.5/weather"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void parsesAndConvertsWindToKilometresPerHour() {
        // units=metric matters: without it OpenWeatherMap returns Kelvin, which
        // would still parse cleanly and be wrong by ~273 degrees.
        wiremock.stubFor(get(urlPathEqualTo("/data/2.5/weather"))
                .withQueryParam("q", equalTo("singapore"))
                .withQueryParam("appid", equalTo("test-key"))
                .withQueryParam("units", equalTo("metric"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"main":{"temp":29.4,"humidity":74},"wind":{"speed":5.5,"deg":90}}
                                """)));

        Weather weather = provider().fetch("singapore");

        assertThat(weather.temperatureCelsius()).isCloseTo(29.4, within(0.0001));
        assertThat(weather.windSpeedKmh())
                .as("5.5 m/s is 19.8 km/h")
                .isCloseTo(19.8, within(0.0001));
    }

    @Test
    void mapsHttp404ToCityNotFound() {
        stub(404, """
                {"cod":"404","message":"city not found"}
                """);

        assertThatThrownBy(() -> provider().fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }

    @Test
    void mapsUnauthorisedToAnAuthenticationFailure() {
        stub(401, """
                {"cod":401,"message":"Invalid API key."}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void mapsForbiddenToAnAuthenticationFailure() {
        stub(403, """
                {"cod":403,"message":"Forbidden."}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void mapsRateLimitingToAProviderFailure() {
        stub(429, """
                {"cod":429,"message":"Your account is temporary blocked."}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void mapsServerErrorsToAProviderFailure() {
        stub(500, "boom");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAMissingWindBlockAsAFailure() {
        stub(200, """
                {"main":{"temp":29.4}}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsMalformedJsonAsAFailure() {
        stub(200, "{not json");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAReadTimeoutAsAFailure() {
        wiremock.stubFor(get(urlPathEqualTo("/data/2.5/weather"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }
}
