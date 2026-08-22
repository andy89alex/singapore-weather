package com.singapore.weather.api;

import com.singapore.weather.domain.AllProvidersFailedException;
import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.Weather;
import com.singapore.weather.domain.WeatherResult;
import com.singapore.weather.domain.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherController.class)
class WeatherControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WeatherService weatherService;

    @Test
    void returnsExactlyTheSpecifiedPayload() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.0, 20.0)));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void roundsFractionalReadingsToWholeNumbers() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.4, 19.8)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void defaultsToSingaporeWhenCityIsOmitted() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.0, 20.0)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk());
    }

    @Test
    void marksStaleResponsesWithHeadersButKeepsTheBodyUnchanged() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.stale(new Weather(29.0, 20.0), Duration.ofSeconds(42)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Weather-Stale", "true"))
                .andExpect(header().string("Age", "42"))
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void rejectsMalformedCityWithBadRequest() throws Exception {
        mockMvc.perform(get("/v1/weather").param("city", "<script>"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsUnknownCityAsNotFound() throws Exception {
        willThrow(new CityNotFoundException("atlantis"))
                .given(weatherService).get(eq("atlantis"));

        mockMvc.perform(get("/v1/weather").param("city", "atlantis"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsTotalOutageWithoutCacheAsServiceUnavailable() throws Exception {
        willThrow(new AllProvidersFailedException("all down"))
                .given(weatherService).get(eq("singapore"));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isServiceUnavailable());
    }
}
