package com.singapore.weather.exception;

/** The city parameter is malformed and was rejected before any provider was contacted. */
public class InvalidCityException extends RuntimeException {

    public InvalidCityException(String message) {
        super(message);
    }
}
