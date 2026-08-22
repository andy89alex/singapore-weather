package com.singapore.weather.api;

import com.singapore.weather.domain.WeatherResult;
import com.singapore.weather.domain.WeatherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/v1/weather")
    public ResponseEntity<WeatherResponse> weather(
            @RequestParam(name = "city", required = false) String city) {

        WeatherResult result = weatherService.get(CityValidator.normalise(city));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();

        if (result.stale()) {
            response.header("X-Weather-Stale", "true")
                    .header("Age", Long.toString(result.age().toSeconds()));
        }

        return response.body(WeatherResponse.from(result.weather()));
    }
}
