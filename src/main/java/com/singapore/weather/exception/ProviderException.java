package com.singapore.weather.exception;

/** A provider failed for infrastructure reasons. Counts as a circuit breaker failure. */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
