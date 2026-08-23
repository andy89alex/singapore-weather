package com.singapore.weather.api;

import com.singapore.weather.exception.InvalidCityException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises and validates the {@code city} parameter before any provider is
 * contacted. Also the first line of defence for the cache and the lock stripes,
 * which are keyed by this value.
 */
public final class CityValidator {

    public static final String DEFAULT_CITY = "singapore";

    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L} .,'-]+");

    private CityValidator() {
    }

    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CITY;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new InvalidCityException(
                    "city must be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new InvalidCityException(
                    "city may contain only letters, spaces, hyphens, apostrophes, periods and commas");
        }
        // Use Locale.ROOT to avoid locale-dependent lowercasing (e.g., Turkish "I" -> "ı").
        // This ensures consistent cache keys and lock-stripe mapping across all JVM locales.
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
