package com.singapore.weather.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `minimum-number-of-calls` and `sliding-window-size` are both 10 in
 * application.yml, so binding alone cannot show they are wired to different
 * places — nor that the minimum is no longer derived from the window size, as
 * it was when it lived in code. Setting them apart here proves both.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "weather.providers.weatherstack.api-key=ws",
        "weather.providers.openweathermap.api-key=owm",
        "weather.resilience.sliding-window-size=7",
        "weather.resilience.minimum-number-of-calls=3"
})
class ResilienceConfigTest {

    @Autowired
    CircuitBreakerRegistry registry;

    @Test
    void theWindowSizeAndTheEvaluationGateAreConfiguredIndependently() {
        CircuitBreakerConfig config = registry.getDefaultConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(7);
        assertThat(config.getMinimumNumberOfCalls())
                .as("the gate must come from its own property, not from the window size")
                .isEqualTo(3);
    }
}
