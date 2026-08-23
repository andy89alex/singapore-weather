package com.singapore.weather;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// As in FailoverIntegrationTest, WireMock builders stay qualified to avoid
// colliding with MockMvcRequestBuilders.get.
// Both test methods share the same static WireMock servers, and the second
// test leaves every provider stubbed to fail. JUnit 5's default method order
// is not declaration order, so without an explicit order the "never cached"
// test can run first and leave Weatherstack broken for the priming step of
// the other test. @Order pins the priming test first so the shared stub
// state behaves the way both tests assume.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaleAfterTotalOutageTest {

    static final WireMockServer weatherstack = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer openweathermap = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        weatherstack.start();
        openweathermap.start();

        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"current\":{\"temperature\":29,\"wind_speed\":20}}")));

        registry.add("weather.providers.weatherstack.base-url", weatherstack::baseUrl);
        registry.add("weather.providers.weatherstack.api-key", () -> "ws-key");
        registry.add("weather.providers.openweathermap.base-url", openweathermap::baseUrl);
        registry.add("weather.providers.openweathermap.api-key", () -> "owm-key");
        registry.add("weather.cache.fresh-ttl", () -> "-1s");
    }

    @AfterAll
    static void stopStubs() {
        weatherstack.stop();
        openweathermap.stop();
    }

    @Test
    @Order(1)
    void servesTheLastKnownReadingWhenEveryProviderGoesDown() throws Exception {
        // Prime the cache while the primary is healthy.
        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Weather-Stale"))
                .andExpect(jsonPath("$.temperature_degrees").value(29));

        // Now take every provider down.
        weatherstack.resetAll();
        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Weather-Stale", "true"))
                .andExpect(jsonPath("$.wind_speed").value(20))
                .andExpect(jsonPath("$.temperature_degrees").value(29));
    }

    @Test
    @Order(2)
    void reportsUnavailableForACityThatWasNeverCached() throws Exception {
        weatherstack.resetAll();
        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));

        mockMvc.perform(get("/v1/weather").param("city", "reykjavik"))
                .andExpect(status().isServiceUnavailable());
    }
}
