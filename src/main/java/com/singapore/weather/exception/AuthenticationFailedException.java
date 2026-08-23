package com.singapore.weather.exception;

/**
 * A provider rejected the request because the API key is missing, invalid or
 * revoked. This will not heal on retry — retrying merely doubles latency
 * before failover — so it is excluded from the retry policy. It still counts
 * as a circuit breaker failure: a provider an operator has misconfigured is
 * genuinely unusable and should be treated as unhealthy.
 */
public class AuthenticationFailedException extends ProviderException {

    public AuthenticationFailedException(String message) {
        super(message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
