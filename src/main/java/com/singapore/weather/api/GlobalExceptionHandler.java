package com.singapore.weather.api;

import com.singapore.weather.domain.AllProvidersFailedException;
import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.InvalidCityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCityException.class)
    ProblemDetail invalidCity(InvalidCityException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid city", e.getMessage());
    }

    @ExceptionHandler(CityNotFoundException.class)
    ProblemDetail cityNotFound(CityNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "City not found", e.getMessage());
    }

    @ExceptionHandler(AllProvidersFailedException.class)
    ProblemDetail allProvidersFailed(AllProvidersFailedException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Weather unavailable",
                "Every weather provider is unavailable and no cached reading exists.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
