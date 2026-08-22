package com.singapore.weather.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeatherTest {

    @Test
    void convertsMetresPerSecondToKilometresPerHour() {
        Weather weather = Weather.ofMetresPerSecond(29.0, 5.5);

        assertThat(weather.windSpeedKmh()).isCloseTo(19.8, within(0.0001));
        assertThat(weather.temperatureCelsius()).isEqualTo(29.0);
    }

    @Test
    void treatsZeroWindAsZero() {
        assertThat(Weather.ofMetresPerSecond(30.0, 0.0).windSpeedKmh()).isZero();
    }

    @Test
    void keepsKilometresPerHourUnchanged() {
        Weather weather = new Weather(29.0, 20.0);

        assertThat(weather.windSpeedKmh()).isEqualTo(20.0);
    }
}
