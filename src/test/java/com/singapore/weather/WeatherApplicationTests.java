package com.singapore.weather;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "weather.providers.weatherstack.api-key=ws-key",
        "weather.providers.openweathermap.api-key=owm-key"
})
class WeatherApplicationTests {

    @Test
    void contextLoads() {
    }
}
