package com.singapore.weather.api;

import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The handler's {@code @Value} default happens to equal the value in
 * application.yml, so the main controller test cannot tell a configured
 * Retry-After from a hardcoded one. Overriding the property here proves the
 * header actually tracks the circuit breaker's open-state wait.
 */
@WebMvcTest(WeatherController.class)
@TestPropertySource(properties = "weather.resilience.wait-duration-in-open-state=42s")
class RetryAfterIsConfigDrivenTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WeatherService weatherService;

    @Test
    void retryAfterFollowsTheConfiguredOpenStateWait() throws Exception {
        willThrow(new AllProvidersFailedException("all down"))
                .given(weatherService).get(eq("singapore"));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "42"));
    }
}
