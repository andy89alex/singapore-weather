package com.singapore.weather.health;

import com.singapore.weather.domain.WeatherProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/** Surfaces each provider's circuit state, which is what an operator wants at 3am. */
@Component
public class ProviderHealthIndicator implements HealthIndicator {

    private final List<WeatherProvider> providers;
    private final CircuitBreakerRegistry breakers;

    public ProviderHealthIndicator(List<WeatherProvider> providers, CircuitBreakerRegistry breakers) {
        this.providers = providers;
        this.breakers = breakers;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean anyClosed = false;

        for (WeatherProvider provider : providers) {
            CircuitBreaker.State state = breakers.circuitBreaker(provider.name()).getState();
            builder.withDetail(provider.name(), state.name());
            anyClosed |= state != CircuitBreaker.State.OPEN;
        }

        return anyClosed ? builder.build() : builder.status("DEGRADED").build();
    }
}
