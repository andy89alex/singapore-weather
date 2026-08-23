package com.singapore.weather.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "weather.providers.weatherstack.api-key=ws-key",
        "weather.providers.openweathermap.api-key=owm-key"
})
class WeatherPropertiesTest {

    @Autowired
    WeatherProperties properties;

    @Test
    void bindsCacheDefaults() {
        assertThat(properties.cache().freshTtl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.cache().staleRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.cache().maxSize()).isEqualTo(1000);
        assertThat(properties.cache().coldRefreshWait()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void bindsProviderPriorityAndKeys() {
        assertThat(properties.providers().weatherstack().priority()).isEqualTo(1);
        assertThat(properties.providers().weatherstack().apiKey()).isEqualTo("ws-key");
        assertThat(properties.providers().openweathermap().priority()).isEqualTo(2);
        assertThat(properties.providers().openweathermap().apiKey()).isEqualTo("owm-key");
    }

    @Test
    void bindsResilienceDefaults() {
        assertThat(properties.resilience().slidingWindowSize()).isEqualTo(10);
        assertThat(properties.resilience().failureRateThreshold()).isEqualTo(50.0f);
        assertThat(properties.resilience().retryMaxAttempts()).isEqualTo(2);
        assertThat(properties.resilience().chainDeadline()).isEqualTo(Duration.ofMillis(8500));
    }
}
