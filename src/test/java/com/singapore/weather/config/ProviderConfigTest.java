package com.singapore.weather.config;

import com.singapore.weather.service.WeatherProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigTest {

    @SpringBootTest
    @TestPropertySource(properties = {
            "weather.providers.weatherstack.api-key=ws",
            "weather.providers.openweathermap.api-key=owm"
    })
    static class BothKeysPresent {

        @Autowired
        List<WeatherProvider> providers;

        @Test
        void registersBothProvidersInPriorityOrder() {
            assertThat(providers).hasSize(2);
            // weatherstack is priority 1 and openweathermap is priority 2 by
            // default (see application.yml), so asserting the exact order here
            // really does assert priority order, unlike containsExactlyInAnyOrder.
            assertThat(providers.stream().map(WeatherProvider::name))
                    .containsExactly("weatherstack", "openweathermap");
        }
    }

    @SpringBootTest
    @TestPropertySource(properties = {
            "weather.providers.weatherstack.api-key=",
            "weather.providers.openweathermap.api-key=owm"
    })
    static class OnlyFailoverKeyPresent {

        @Autowired
        List<WeatherProvider> providers;

        @Test
        void disablesTheProviderWithoutAKeyInsteadOfFailingStartup() {
            assertThat(providers).hasSize(1);
            assertThat(providers.getFirst().name()).isEqualTo("openweathermap");
        }
    }
}
