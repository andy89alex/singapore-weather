package com.singapore.weather.api;

import com.singapore.weather.exception.InvalidCityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CityValidatorTest {

    @Test
    void defaultsToSingaporeWhenMissing() {
        assertThat(CityValidator.normalise(null)).isEqualTo("singapore");
        assertThat(CityValidator.normalise("")).isEqualTo("singapore");
        assertThat(CityValidator.normalise("   ")).isEqualTo("singapore");
    }

    @Test
    void trimsAndLowercases() {
        assertThat(CityValidator.normalise("  SinGaPore ")).isEqualTo("singapore");
    }

    @Test
    void acceptsRealWorldCityNames() {
        assertThat(CityValidator.normalise("Kuala Lumpur")).isEqualTo("kuala lumpur");
        assertThat(CityValidator.normalise("Stoke-on-Trent")).isEqualTo("stoke-on-trent");
        assertThat(CityValidator.normalise("N'Djamena")).isEqualTo("n'djamena");
        assertThat(CityValidator.normalise("Washington, D.C.")).isEqualTo("washington, d.c.");
    }

    @Test
    void rejectsCharactersOutsideTheAllowedSet() {
        assertThatThrownBy(() -> CityValidator.normalise("singapore; DROP TABLE"))
                .isInstanceOf(InvalidCityException.class);
        assertThatThrownBy(() -> CityValidator.normalise("<script>"))
                .isInstanceOf(InvalidCityException.class);
        assertThatThrownBy(() -> CityValidator.normalise("city123"))
                .isInstanceOf(InvalidCityException.class);
    }

    @Test
    void acceptsMaxLengthInput() {
        // Verify the accepted side of the length boundary: exactly 64 characters should be accepted
        String maxLength = "a".repeat(64);
        assertThat(CityValidator.normalise(maxLength)).isEqualTo(maxLength);
    }

    @Test
    void rejectsOverlyLongInput() {
        assertThatThrownBy(() -> CityValidator.normalise("a".repeat(65)))
                .isInstanceOf(InvalidCityException.class);
    }
}
