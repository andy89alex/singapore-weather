package com.singapore.weather.exception;

/**
 * A provider does not recognise the city. This is a client error, not an
 * outage, so it must never open a circuit and must never be retried.
 */
public class CityNotFoundException extends RuntimeException {

    private final String city;

    public CityNotFoundException(String city) {
        super("No provider recognises city: " + city);
        this.city = city;
    }

    public String city() {
        return city;
    }
}
