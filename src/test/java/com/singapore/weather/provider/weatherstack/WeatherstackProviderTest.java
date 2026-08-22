package com.singapore.weather.provider.weatherstack;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WeatherstackProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private WeatherstackProvider provider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient client = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(factory)
                .build();
        return new WeatherstackProvider(client, "test-key", 1);
    }

    private void stub(int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void parsesASuccessfulResponse() {
        stub(200, """
                {"request":{"query":"Singapore"},
                 "current":{"temperature":29,"wind_speed":20,"humidity":70}}
                """);

        Weather weather = provider().fetch("singapore");

        assertThat(weather.temperatureCelsius()).isCloseTo(29.0, within(0.0001));
        assertThat(weather.windSpeedKmh()).isCloseTo(20.0, within(0.0001));
    }

    @Test
    void treatsSuccessFalseAsAFailureDespiteHttp200() {
        stub(200, """
                {"success":false,"error":{"code":104,"type":"usage_limit_reached",
                 "info":"Your monthly API request volume has been reached."}}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("104");
    }

    @Test
    void mapsTheUnresolvableLocationCodeToCityNotFound() {
        stub(200, """
                {"success":false,"error":{"code":615,"type":"request_failed",
                 "info":"Your API request failed."}}
                """);

        assertThatThrownBy(() -> provider().fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }

    @Test
    void treatsAMissingCurrentBlockAsAFailure() {
        stub(200, """
                {"request":{"query":"Singapore"}}
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
    void treatsAServerErrorAsAFailure() {
        stub(500, "upstream boom");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAReadTimeoutAsAFailure() {
        wiremock.stubFor(get(urlPathEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000)
                        .withBody("{}")));

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }
}
