package com.singapore.weather.domain;

/** Every provider failed. The caller decides whether stale data can be served. */
public class AllProvidersFailedException extends RuntimeException {

    public AllProvidersFailedException(String message) {
        super(message);
    }
}
