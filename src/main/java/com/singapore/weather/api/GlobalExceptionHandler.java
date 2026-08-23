package com.singapore.weather.api;

import com.singapore.weather.exception.AllProvidersFailedException;
import com.singapore.weather.exception.CityNotFoundException;
import com.singapore.weather.exception.InvalidCityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Duration retryAfter;

    /**
     * {@code Retry-After} is taken from the circuit breaker's open-state wait,
     * because that is genuinely when a provider will next be attempted. A default
     * is supplied so web-layer test slices need no property source of their own.
     */
    public GlobalExceptionHandler(
            @Value("${weather.resilience.wait-duration-in-open-state:10s}") Duration retryAfter) {
        this.retryAfter = retryAfter;
    }

    @ExceptionHandler(InvalidCityException.class)
    ResponseEntity<ProblemDetail> invalidCity(InvalidCityException e) {
        return builder(HttpStatus.BAD_REQUEST)
                .body(problem(HttpStatus.BAD_REQUEST, "Invalid city", e.getMessage()));
    }

    @ExceptionHandler(CityNotFoundException.class)
    ResponseEntity<ProblemDetail> cityNotFound(CityNotFoundException e) {
        return builder(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "City not found", e.getMessage()));
    }

    @ExceptionHandler(AllProvidersFailedException.class)
    ResponseEntity<ProblemDetail> allProvidersFailed(AllProvidersFailedException e) {
        // Surfacing the real message distinguishes "every provider failed" from
        // "timed out waiting for an in-flight refresh" — the same 503 status
        // otherwise hides which of those two very different situations occurred.
        //
        // Retry-After matters more than it looks: without it clients back off on
        // their own schedule and tend to retry hardest exactly while the providers
        // are down, which is when this service can least afford the load.
        return builder(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.toSeconds()))
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Weather unavailable", e.getMessage()));
    }

    /**
     * A cached failure outlives the failure itself: an intermediary that stored one
     * of these would keep serving it after the service had recovered.
     */
    private static ResponseEntity.BodyBuilder builder(HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
