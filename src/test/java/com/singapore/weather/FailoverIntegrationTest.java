package com.singapore.weather;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// WireMock's static get(...) and MockMvcRequestBuilders.get(...) collide, so
// WireMock's builders stay qualified as WireMock.get(...) throughout this class.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailoverIntegrationTest {

    static final WireMockServer weatherstack = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer openweathermap = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        weatherstack.start();
        openweathermap.start();

        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"main\":{\"temp\":28.0},\"wind\":{\"speed\":5.0}}")));

        registry.add("weather.providers.weatherstack.base-url", weatherstack::baseUrl);
        registry.add("weather.providers.weatherstack.api-key", () -> "ws-key");
        registry.add("weather.providers.openweathermap.base-url", openweathermap::baseUrl);
        registry.add("weather.providers.openweathermap.api-key", () -> "owm-key");
        // Defeat the 3s cache so each request really reaches the chain.
        // -1s rather than 0s: isFresh() tests age <= freshTtl, so a zero TTL
        // would still count two requests in the same clock tick as fresh.
        registry.add("weather.cache.fresh-ttl", () -> "-1s");
    }

    @AfterAll
    static void stopStubs() {
        weatherstack.stop();
        openweathermap.stop();
    }

    @Test
    void servesFromTheFailoverProviderAndStopsCallingTheDeadPrimary() throws Exception {
        // 5.0 m/s is 18 km/h; 28.0 C stays 28.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.temperature_degrees").value(28))
                    .andExpect(jsonPath("$.wind_speed").value(18));
        }

        int callsBeforeCircuitOpened = weatherstack.getServeEvents().getServeEvents().size();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                    .andExpect(status().isOk());
        }

        // An open circuit must skip the provider entirely, not merely fail fast.
        Assertions.assertThat(weatherstack.getServeEvents().getServeEvents().size())
                .isEqualTo(callsBeforeCircuitOpened);
    }
}
