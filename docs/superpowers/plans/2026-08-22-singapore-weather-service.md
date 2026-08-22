# Singapore Weather Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an HTTP service that returns Singapore weather as `{"wind_speed": N, "temperature_degrees": N}`, sourced from Weatherstack with automatic failover to OpenWeatherMap, cached for 3 seconds, and served stale when every provider is down.

**Architecture:** A `WeatherProvider` interface with one implementation per vendor, invoked in priority order by a `ProviderChain` that wraps each provider in a Resilience4j circuit breaker and retry. `WeatherServiceImpl` sits between the controller and the chain, consulting a Caffeine cache with a 3-second soft TTL over a 24-hour hard TTL, and falling back to the stale entry when the chain fails entirely. Striped locks ensure one refresh per city at a time.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven (with wrapper), Caffeine, Resilience4j core modules (used programmatically, not via the Spring starter), JUnit 5, WireMock 3.13.2, MockMvc.

**Design spec:** `docs/superpowers/specs/2026-08-22-singapore-weather-service-design.md`

## Global Constraints

- Java version: **25**. Maven property `<java.version>25</java.version>`.
- Spring Boot parent: **4.1.1**. Verified booting on Java 25 by spike before this plan.
- Base package: **`com.singapore.weather`**. If a different package is preferred, change it once at Task 1 and follow through consistently.
- Jackson: Boot 4.1.1 ships Jackson 3 (`tools.jackson.databind` 3.1.5). **Annotations are still `com.fasterxml.jackson.annotation`** — use those imports.
- Resilience4j: use **core modules only** (`resilience4j-circuitbreaker`, `resilience4j-retry`, `resilience4j-micrometer`, all `2.4.0`). Do **not** add `resilience4j-spring-boot3` — it targets Spring Framework 6 while Boot 4.1.1 ships Spring Framework 7.
- Response body is **exactly two fields** in this order: `wind_speed`, then `temperature_degrees`. Both are whole numbers.
- Units: wind in **km/h**, temperature in **degrees Celsius**.
- **No test may call `Thread.sleep`** and no test may reach the real internet. Time is controlled through an injected `Clock`; HTTP is stubbed with WireMock.
- API keys come from environment variables only. Never commit a key.
- Every task ends with a commit.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `pom.xml` | Dependencies, Java 25, Boot 4.1.1, plugins |
| `src/main/java/com/singapore/weather/WeatherApplication.java` | Entry point |
| `config/WeatherProperties.java` | Bound `weather.*` configuration |
| `config/RestClientConfig.java` | One `RestClient` per provider, with timeouts |
| `config/ResilienceConfig.java` | Circuit breaker and retry registries, metrics binding |
| `config/ProviderConfig.java` | Conditional provider beans, auto-disable on missing key |
| `domain/Weather.java` | Immutable weather value (double precision, unrounded) |
| `domain/WeatherProvider.java` | Provider contract |
| `domain/WeatherService.java` | Service contract (interface) |
| `domain/WeatherServiceImpl.java` | Cache-and-chain orchestration |
| `domain/WeatherResult.java` | Weather plus staleness metadata |
| `domain/ProviderChain.java` | Ordered failover with resilience decoration |
| `domain/ProviderException.java` | Infrastructure failure — counts against the circuit |
| `domain/CityNotFoundException.java` | Client error — never counts against the circuit |
| `domain/AllProvidersFailedException.java` | Every provider failed |
| `domain/InvalidCityException.java` | Malformed city input |
| `cache/CachedWeather.java` | Cached value plus fetch timestamp |
| `cache/WeatherCache.java` | Soft/hard TTL, striped refresh locks |
| `provider/weatherstack/WeatherstackProvider.java` | Weatherstack adapter |
| `provider/weatherstack/WeatherstackResponse.java` | Weatherstack DTO |
| `provider/openweathermap/OpenWeatherMapProvider.java` | OpenWeatherMap adapter |
| `provider/openweathermap/OpenWeatherMapResponse.java` | OpenWeatherMap DTO |
| `api/WeatherController.java` | HTTP endpoint |
| `api/WeatherResponse.java` | The two-field response record |
| `api/CityValidator.java` | Normalisation and validation of `city` |
| `api/GlobalExceptionHandler.java` | 400 / 404 / 503 problem responses |
| `health/ProviderHealthIndicator.java` | Circuit state in `/actuator/health` |

---

## Task 1: Project skeleton that boots

**Files:**
- Delete: `src/Main.java`, `singapore-weather.iml`
- Create: `pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd`
- Create: `src/main/java/com/singapore/weather/WeatherApplication.java`
- Create: `src/main/resources/application.yml`
- Modify: `.gitignore`
- Test: `src/test/java/com/singapore/weather/WeatherApplicationTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a bootable Spring Boot application and the full dependency set every later task relies on.

- [ ] **Step 1: Remove the IntelliJ scaffolding**

```bash
git rm -f --cached singapore-weather.iml 2>/dev/null || true
rm -f singapore-weather.iml src/Main.java
rmdir src 2>/dev/null || true
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>com.singapore</groupId>
  <artifactId>singapore-weather</artifactId>
  <version>1.0.0</version>
  <name>singapore-weather</name>
  <description>HTTP weather service with provider failover</description>

  <properties>
    <java.version>25</java.version>
    <resilience4j.version>2.4.0</resilience4j.version>
    <wiremock.version>3.13.2</wiremock.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>com.github.ben-manes.caffeine</groupId>
      <artifactId>caffeine</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-circuitbreaker</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-retry</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-micrometer</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.wiremock</groupId>
      <artifactId>wiremock-standalone</artifactId>
      <version>${wiremock.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Generate the Maven wrapper**

Run: `mvn -B wrapper:wrapper -Dmaven=3.9.9`
Expected: BUILD SUCCESS, and `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` appear.

- [ ] **Step 4: Write the application entry point**

`src/main/java/com/singapore/weather/WeatherApplication.java`:

```java
package com.singapore.weather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WeatherApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: singapore-weather
  threads:
    virtual:
      enabled: true

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 6: Replace `.gitignore`**

```gitignore
target/
!.mvn/wrapper/maven-wrapper.jar
.mvn/.gradle-enterprise/

.idea/
*.iml
*.iws
*.ipr

.env
.DS_Store
*.log

.superpowers/
```

- [ ] **Step 7: Write the context-loads test**

`src/test/java/com/singapore/weather/WeatherApplicationTests.java`:

```java
package com.singapore.weather;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WeatherApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 8: Run the test**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS, `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add Spring Boot 4.1.1 skeleton on Java 25"
```

---

## Task 2: Configuration properties

**Files:**
- Create: `src/main/java/com/singapore/weather/config/WeatherProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/singapore/weather/config/WeatherPropertiesTest.java`

**Interfaces:**
- Consumes: the application from Task 1.
- Produces:
  - `WeatherProperties.cache().freshTtl() -> Duration`
  - `WeatherProperties.cache().staleRetention() -> Duration`
  - `WeatherProperties.cache().maxSize() -> int`
  - `WeatherProperties.resilience().slidingWindowSize() -> int`, `.failureRateThreshold() -> float`, `.waitDurationInOpenState() -> Duration`, `.permittedCallsInHalfOpenState() -> int`, `.retryMaxAttempts() -> int`, `.retryWaitDuration() -> Duration`
  - `WeatherProperties.providers().weatherstack()` and `.openweathermap()`, each exposing `.priority() -> int`, `.baseUrl() -> String`, `.apiKey() -> String`, `.connectTimeout() -> Duration`, `.readTimeout() -> Duration`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/config/WeatherPropertiesTest.java`:

```java
package com.singapore.weather.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "weather.providers.weatherstack.api-key=ws-key",
        "weather.providers.openweathermap.api-key=owm-key"
})
class WeatherPropertiesTest {

    @Autowired
    WeatherProperties properties;

    @Test
    void bindsCacheDefaults() {
        assertThat(properties.cache().freshTtl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.cache().staleRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.cache().maxSize()).isEqualTo(1000);
    }

    @Test
    void bindsProviderPriorityAndKeys() {
        assertThat(properties.providers().weatherstack().priority()).isEqualTo(1);
        assertThat(properties.providers().weatherstack().apiKey()).isEqualTo("ws-key");
        assertThat(properties.providers().openweathermap().priority()).isEqualTo(2);
        assertThat(properties.providers().openweathermap().apiKey()).isEqualTo("owm-key");
    }

    @Test
    void bindsResilienceDefaults() {
        assertThat(properties.resilience().slidingWindowSize()).isEqualTo(10);
        assertThat(properties.resilience().failureRateThreshold()).isEqualTo(50.0f);
        assertThat(properties.resilience().retryMaxAttempts()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherPropertiesTest`
Expected: FAIL — compilation error, `WeatherProperties` does not exist.

- [ ] **Step 3: Write `WeatherProperties`**

`src/main/java/com/singapore/weather/config/WeatherProperties.java`:

```java
package com.singapore.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(Cache cache, Resilience resilience, Providers providers) {

    public record Cache(Duration freshTtl, Duration staleRetention, int maxSize) {
    }

    public record Resilience(
            int slidingWindowSize,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState,
            int retryMaxAttempts,
            Duration retryWaitDuration) {
    }

    public record Providers(Provider weatherstack, Provider openweathermap) {
    }

    public record Provider(
            int priority,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
```

- [ ] **Step 4: Add the defaults to `application.yml`**

Append to `src/main/resources/application.yml`:

```yaml
weather:
  cache:
    fresh-ttl: 3s
    stale-retention: 24h
    max-size: 1000
  resilience:
    sliding-window-size: 10
    failure-rate-threshold: 50
    wait-duration-in-open-state: 10s
    permitted-calls-in-half-open-state: 3
    retry-max-attempts: 2
    retry-wait-duration: 100ms
  providers:
    weatherstack:
      priority: 1
      base-url: http://api.weatherstack.com
      api-key: ${WEATHERSTACK_API_KEY:}
      connect-timeout: 1s
      read-timeout: 2s
    openweathermap:
      priority: 2
      base-url: https://api.openweathermap.org
      api-key: ${OPENWEATHERMAP_API_KEY:}
      connect-timeout: 1s
      read-timeout: 2s
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherPropertiesTest`
Expected: PASS, `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/singapore/weather/config src/main/resources/application.yml src/test/java/com/singapore/weather/config
git commit -m "Add bound and validated weather configuration properties"
```

---

## Task 3: Domain model, exceptions and provider contract

**Files:**
- Create: `src/main/java/com/singapore/weather/domain/Weather.java`
- Create: `src/main/java/com/singapore/weather/domain/WeatherProvider.java`
- Create: `src/main/java/com/singapore/weather/domain/ProviderException.java`
- Create: `src/main/java/com/singapore/weather/domain/CityNotFoundException.java`
- Create: `src/main/java/com/singapore/weather/domain/AllProvidersFailedException.java`
- Create: `src/main/java/com/singapore/weather/domain/InvalidCityException.java`
- Test: `src/test/java/com/singapore/weather/domain/WeatherTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `Weather(double temperatureCelsius, double windSpeedKmh)` — a record; values are **unrounded**, rounding happens at the API boundary.
  - `Weather.ofMetresPerSecond(double temperatureCelsius, double windSpeedMps) -> Weather`
  - `WeatherProvider` with `name()`, `priority()`, `fetch(String city)`
  - Four exception types used by every later task.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/domain/WeatherTest.java`:

```java
package com.singapore.weather.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeatherTest {

    @Test
    void convertsMetresPerSecondToKilometresPerHour() {
        Weather weather = Weather.ofMetresPerSecond(29.0, 5.5);

        assertThat(weather.windSpeedKmh()).isCloseTo(19.8, within(0.0001));
        assertThat(weather.temperatureCelsius()).isEqualTo(29.0);
    }

    @Test
    void treatsZeroWindAsZero() {
        assertThat(Weather.ofMetresPerSecond(30.0, 0.0).windSpeedKmh()).isZero();
    }

    @Test
    void keepsKilometresPerHourUnchanged() {
        Weather weather = new Weather(29.0, 20.0);

        assertThat(weather.windSpeedKmh()).isEqualTo(20.0);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherTest`
Expected: FAIL — compilation error, `Weather` does not exist.

- [ ] **Step 3: Write the domain types**

`src/main/java/com/singapore/weather/domain/Weather.java`:

```java
package com.singapore.weather.domain;

/**
 * Provider-agnostic weather reading. Values are unrounded; rounding happens
 * at the API boundary so the domain keeps full precision.
 */
public record Weather(double temperatureCelsius, double windSpeedKmh) {

    private static final double KMH_PER_MPS = 3.6;

    public static Weather ofMetresPerSecond(double temperatureCelsius, double windSpeedMps) {
        return new Weather(temperatureCelsius, windSpeedMps * KMH_PER_MPS);
    }
}
```

`src/main/java/com/singapore/weather/domain/WeatherProvider.java`:

```java
package com.singapore.weather.domain;

/**
 * One weather vendor. Implementations translate their vendor's failure
 * vocabulary into {@link ProviderException} (infrastructure trouble, counts
 * against the circuit breaker) or {@link CityNotFoundException} (client error,
 * never counts against the circuit breaker).
 */
public interface WeatherProvider {

    String name();

    /** Lower runs first. */
    int priority();

    Weather fetch(String city);
}
```

`src/main/java/com/singapore/weather/domain/ProviderException.java`:

```java
package com.singapore.weather.domain;

/** A provider failed for infrastructure reasons. Counts as a circuit breaker failure. */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/singapore/weather/domain/CityNotFoundException.java`:

```java
package com.singapore.weather.domain;

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
```

`src/main/java/com/singapore/weather/domain/AllProvidersFailedException.java`:

```java
package com.singapore.weather.domain;

/** Every provider failed. The caller decides whether stale data can be served. */
public class AllProvidersFailedException extends RuntimeException {

    public AllProvidersFailedException(String message) {
        super(message);
    }
}
```

`src/main/java/com/singapore/weather/domain/InvalidCityException.java`:

```java
package com.singapore.weather.domain;

/** The city parameter is malformed and was rejected before any provider was contacted. */
public class InvalidCityException extends RuntimeException {

    public InvalidCityException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherTest`
Expected: PASS, `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/singapore/weather/domain src/test/java/com/singapore/weather/domain
git commit -m "Add weather domain model, provider contract and exception types"
```

---

## Task 4: Provider chain with failover and resilience

**Files:**
- Create: `src/main/java/com/singapore/weather/domain/ProviderChain.java`
- Test: `src/test/java/com/singapore/weather/domain/ProviderChainTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherProvider`, `ProviderException`, `CityNotFoundException`, `AllProvidersFailedException` from Task 3.
- Produces:
  - `new ProviderChain(List<WeatherProvider> providers, CircuitBreakerRegistry cbRegistry, RetryRegistry retryRegistry)`
  - `ProviderChain.fetch(String city) -> Weather`, throwing `AllProvidersFailedException` or `CityNotFoundException`.

**Why the decoration order is `Retry(CircuitBreaker(call))`:** each retry attempt is
independently observed by the circuit breaker, so repeated failures inside one request still
move the circuit toward opening.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/domain/ProviderChainTest.java`:

```java
package com.singapore.weather.domain;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderChainTest {

    private CircuitBreakerRegistry breakers;
    private RetryRegistry retries;

    @BeforeEach
    void setUp() {
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .ignoreExceptions(CityNotFoundException.class)
                .build());
        retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1)
                .ignoreExceptions(CityNotFoundException.class)
                .build());
    }

    private static WeatherProvider provider(String name, int priority, Runnable onCall, Weather result) {
        return new WeatherProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Weather fetch(String city) {
                onCall.run();
                return result;
            }
        };
    }

    @Test
    void usesTheHighestPriorityProviderFirst() {
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("secondary", 2, secondCalls::incrementAndGet, new Weather(1, 1)),
                provider("primary", 1, () -> {
                }, new Weather(29, 20))), breakers, retries);

        Weather weather = chain.fetch("singapore");

        assertThat(weather).isEqualTo(new Weather(29, 20));
        assertThat(secondCalls).hasValue(0);
    }

    @Test
    void fallsBackToTheNextProviderWhenTheFirstFails() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                }, new Weather(28, 15))), breakers, retries);

        assertThat(chain.fetch("singapore")).isEqualTo(new Weather(28, 15));
    }

    @Test
    void throwsWhenEveryProviderFails() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                    throw new ProviderException("also down");
                }, null)), breakers, retries);

        assertThatThrownBy(() -> chain.fetch("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void triesEveryProviderBeforeReportingCityNotFound() {
        AtomicInteger secondCalls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new CityNotFoundException("atlantis");
                }, null),
                provider("secondary", 2, () -> {
                    secondCalls.incrementAndGet();
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries);

        assertThatThrownBy(() -> chain.fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void cityNotFoundNeverOpensTheCircuit() {
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    throw new CityNotFoundException("atlantis");
                }, null)), breakers, retries);

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> chain.fetch("atlantis"))
                    .isInstanceOf(CityNotFoundException.class);
        }

        assertThat(breakers.circuitBreaker("primary").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void repeatedProviderFailuresOpenTheCircuitAndStopCallingIt() {
        AtomicInteger calls = new AtomicInteger();
        ProviderChain chain = new ProviderChain(List.of(
                provider("primary", 1, () -> {
                    calls.incrementAndGet();
                    throw new ProviderException("down");
                }, null),
                provider("secondary", 2, () -> {
                }, new Weather(28, 15))), breakers, retries);

        for (int i = 0; i < 4; i++) {
            chain.fetch("singapore");
        }
        int callsBeforeOpen = calls.get();

        chain.fetch("singapore");

        assertThat(breakers.circuitBreaker("primary").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(calls.get())
                .as("an open circuit must not reach the provider at all")
                .isEqualTo(callsBeforeOpen);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=ProviderChainTest`
Expected: FAIL — compilation error, `ProviderChain` does not exist.

- [ ] **Step 3: Write `ProviderChain`**

`src/main/java/com/singapore/weather/domain/ProviderChain.java`:

```java
package com.singapore.weather.domain;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Calls providers in priority order, wrapping each in its own circuit breaker
 * and retry. A provider whose circuit is open is skipped immediately rather
 * than waiting for a timeout.
 */
public class ProviderChain {

    private static final Logger log = LoggerFactory.getLogger(ProviderChain.class);

    private final List<WeatherProvider> providers;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public ProviderChain(List<WeatherProvider> providers,
                         CircuitBreakerRegistry breakers,
                         RetryRegistry retries) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(WeatherProvider::priority))
                .toList();
        this.breakers = breakers;
        this.retries = retries;
    }

    public Weather fetch(String city) {
        boolean everyFailureWasCityNotFound = true;

        for (WeatherProvider provider : providers) {
            try {
                return call(provider, city);
            } catch (CityNotFoundException e) {
                log.debug("Provider {} does not recognise city {}", provider.name(), city);
            } catch (RuntimeException e) {
                everyFailureWasCityNotFound = false;
                log.warn("Provider {} failed for city {}: {}", provider.name(), city, e.toString());
            }
        }

        if (everyFailureWasCityNotFound && !providers.isEmpty()) {
            throw new CityNotFoundException(city);
        }
        throw new AllProvidersFailedException("All providers failed for city: " + city);
    }

    private Weather call(WeatherProvider provider, String city) {
        CircuitBreaker breaker = breakers.circuitBreaker(provider.name());
        Retry retry = retries.retry(provider.name());

        Supplier<Weather> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> provider.fetch(city)));

        return decorated.get();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=ProviderChainTest`
Expected: PASS, `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/singapore/weather/domain/ProviderChain.java src/test/java/com/singapore/weather/domain/ProviderChainTest.java
git commit -m "Add provider chain with circuit-breaker-backed failover"
```

---

## Task 5: Cache with soft and hard TTL

**Files:**
- Create: `src/main/java/com/singapore/weather/cache/CachedWeather.java`
- Create: `src/main/java/com/singapore/weather/cache/WeatherCache.java`
- Test: `src/test/java/com/singapore/weather/cache/MutableClock.java`
- Test: `src/test/java/com/singapore/weather/cache/WeatherCacheTest.java`

**Interfaces:**
- Consumes: `Weather` from Task 3.
- Produces:
  - `CachedWeather(Weather weather, Instant fetchedAt)`
  - `new WeatherCache(Clock clock, Duration freshTtl, Duration staleRetention, int maxSize)`
  - `WeatherCache.find(String city) -> Optional<CachedWeather>`
  - `WeatherCache.put(String city, Weather weather)`
  - `WeatherCache.isFresh(CachedWeather entry) -> boolean`
  - `WeatherCache.age(CachedWeather entry) -> Duration`

- [ ] **Step 1: Write the controllable clock**

`src/test/java/com/singapore/weather/cache/MutableClock.java`:

```java
package com.singapore.weather.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the tests move by hand, so no test ever sleeps. */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/singapore/weather/cache/WeatherCacheTest.java`:

```java
package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCacheTest {

    private static final Weather SINGAPORE = new Weather(29.0, 20.0);

    private MutableClock clock;
    private WeatherCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-22T00:00:00Z"));
        cache = new WeatherCache(clock, Duration.ofSeconds(3), Duration.ofHours(24), 1000);
    }

    @Test
    void missesWhenNothingWasStored() {
        assertThat(cache.find("singapore")).isEmpty();
    }

    @Test
    void treatsAnEntryYoungerThanTheFreshTtlAsFresh() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofMillis(2900));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.isFresh(entry)).isTrue();
        assertThat(entry.weather()).isEqualTo(SINGAPORE);
    }

    @Test
    void treatsAnEntryOlderThanTheFreshTtlAsStale() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofMillis(3100));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.isFresh(entry)).isFalse();
    }

    @Test
    void stillReturnsAStaleEntryWithinTheRetentionWindow() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofHours(23));

        assertThat(cache.find("singapore")).isPresent();
    }

    @Test
    void treatsAnEntryBeyondTheRetentionWindowAsAbsent() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofHours(25));

        assertThat(cache.find("singapore")).isEmpty();
    }

    @Test
    void reportsTheAgeOfAnEntry() {
        cache.put("singapore", SINGAPORE);
        clock.advance(Duration.ofSeconds(7));

        CachedWeather entry = cache.find("singapore").orElseThrow();

        assertThat(cache.age(entry)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void keysEntriesByCity() {
        cache.put("singapore", SINGAPORE);
        cache.put("jakarta", new Weather(32.0, 9.0));

        assertThat(cache.find("singapore").orElseThrow().weather()).isEqualTo(SINGAPORE);
        assertThat(cache.find("jakarta").orElseThrow().weather()).isEqualTo(new Weather(32.0, 9.0));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherCacheTest`
Expected: FAIL — compilation error, `WeatherCache` does not exist.

- [ ] **Step 4: Write the cache**

`src/main/java/com/singapore/weather/cache/CachedWeather.java`:

```java
package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;

import java.time.Instant;

public record CachedWeather(Weather weather, Instant fetchedAt) {
}
```

`src/main/java/com/singapore/weather/cache/WeatherCache.java`:

```java
package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * A single Caffeine cache with two time horizons: entries younger than the
 * fresh TTL are served directly, older entries are refresh candidates that
 * remain available as stale data until the retention window expires.
 */
public class WeatherCache {

    private final Cache<String, CachedWeather> cache;
    private final Clock clock;
    private final Duration freshTtl;

    public WeatherCache(Clock clock, Duration freshTtl, Duration staleRetention, int maxSize) {
        this.clock = clock;
        this.freshTtl = freshTtl;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(staleRetention)
                .maximumSize(maxSize)
                .recordStats()
                .ticker(() -> clock.instant().toEpochMilli() * 1_000_000L)
                .build();
    }

    public Optional<CachedWeather> find(String city) {
        return Optional.ofNullable(cache.getIfPresent(city));
    }

    public void put(String city, Weather weather) {
        cache.put(city, new CachedWeather(weather, clock.instant()));
    }

    public boolean isFresh(CachedWeather entry) {
        return age(entry).compareTo(freshTtl) <= 0;
    }

    public Duration age(CachedWeather entry) {
        return Duration.between(entry.fetchedAt(), clock.instant());
    }

    public Cache<String, CachedWeather> caffeine() {
        return cache;
    }
}
```

Note the `ticker`: Caffeine measures `expireAfterWrite` with its own nanosecond ticker, so
it must be driven from the same `Clock` or the retention test will not see time move.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherCacheTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/singapore/weather/cache src/test/java/com/singapore/weather/cache
git commit -m "Add Caffeine cache with 3s soft TTL over 24h stale retention"
```

---

## Task 6: Stampede protection with striped locks

**Files:**
- Modify: `src/main/java/com/singapore/weather/cache/WeatherCache.java`
- Test: `src/test/java/com/singapore/weather/cache/WeatherCacheStampedeTest.java`

**Interfaces:**
- Consumes: `WeatherCache` from Task 5.
- Produces: `WeatherCache.tryRefresh(String city, Supplier<T> refresh) -> Optional<T>` — runs
  `refresh` and returns its result when this caller wins the city's lock, or returns
  `Optional.empty()` immediately when another caller already holds it.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/cache/WeatherCacheStampedeTest.java`:

```java
package com.singapore.weather.cache;

import com.singapore.weather.domain.Weather;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCacheStampedeTest {

    private WeatherCache cache;

    @BeforeEach
    void setUp() {
        cache = new WeatherCache(new MutableClock(Instant.parse("2026-08-22T00:00:00Z")),
                Duration.ofSeconds(3), Duration.ofHours(24), 1000);
    }

    @Test
    void onlyOneConcurrentCallerRefreshesACity() throws Exception {
        int threads = 200;
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        CountDownLatch insideRefresh = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLine.await();
                    Optional<Weather> result = cache.tryRefresh("singapore", () -> {
                        refreshes.incrementAndGet();
                        await(insideRefresh);
                        return new Weather(29, 20);
                    });
                    if (result.isEmpty()) {
                        skipped.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startLine.countDown();
        // Wait for the winner to take the lock, then let every loser pile up
        // against it before releasing the winner. A one-phase spin that stops
        // as soon as refreshes > 0 can race ahead of the 200 virtual threads
        // still waiting for a carrier thread, letting some of them acquire the
        // now-free lock after the winner finishes.
        while (refreshes.get() == 0) {
            Thread.onSpinWait();
        }
        while (skipped.get() < threads - 1) {
            Thread.onSpinWait();
        }
        insideRefresh.countDown();

        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(refreshes).hasValue(1);
        assertThat(skipped).hasValue(threads - 1);
    }

    @Test
    void aLaterCallerCanRefreshOnceTheLockIsFree() {
        assertThat(cache.tryRefresh("singapore", () -> new Weather(29, 20))).isPresent();
        assertThat(cache.tryRefresh("singapore", () -> new Weather(30, 21))).isPresent();
    }

    @Test
    void releasesTheLockWhenTheRefreshThrows() {
        try {
            cache.tryRefresh("singapore", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the lock must still be released
        }

        assertThat(cache.tryRefresh("singapore", () -> new Weather(29, 20))).isPresent();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherCacheStampedeTest`
Expected: FAIL — compilation error, `tryRefresh` does not exist.

- [ ] **Step 3: Add striped locks to `WeatherCache`**

Add these imports to `WeatherCache.java`:

```java
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
```

Add the field and initialise it in the constructor:

```java
    private static final int STRIPES = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
```

At the top of the constructor body, add:

```java
        Arrays.setAll(stripes, i -> new ReentrantLock());
```

Add the methods:

```java
    /**
     * Runs {@code refresh} only if this caller wins the city's lock. Losers get
     * an empty result immediately instead of queueing, so a burst of concurrent
     * requests produces one upstream call rather than one per request.
     */
    public <T> Optional<T> tryRefresh(String city, Supplier<T> refresh) {
        ReentrantLock lock = lockFor(city);
        if (!lock.tryLock()) {
            return Optional.empty();
        }
        try {
            return Optional.of(refresh.get());
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String city) {
        return stripes[Math.floorMod(city.hashCode(), STRIPES)];
    }
```

`Math.floorMod` rather than `%`: `hashCode()` can be negative, and `%` would produce a
negative index for some city names only.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherCacheStampedeTest`
Expected: PASS, `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the whole cache suite**

Run: `./mvnw -B test -Dtest='WeatherCache*'`
Expected: PASS, 10 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/singapore/weather/cache/WeatherCache.java src/test/java/com/singapore/weather/cache/WeatherCacheStampedeTest.java
git commit -m "Add striped-lock stampede protection to the weather cache"
```

---

## Task 7: Weather service orchestration

**Files:**
- Create: `src/main/java/com/singapore/weather/domain/WeatherResult.java`
- Create: `src/main/java/com/singapore/weather/domain/WeatherService.java`
- Create: `src/main/java/com/singapore/weather/domain/WeatherServiceImpl.java`
- Test: `src/test/java/com/singapore/weather/domain/WeatherServiceImplTest.java`

**Interfaces:**
- Consumes: `WeatherCache` (Tasks 5–6), `ProviderChain` (Task 4), `Weather` (Task 3).
- Produces:
  - `WeatherResult(Weather weather, boolean stale, Duration age)`
  - `WeatherService.get(String city) -> WeatherResult` (interface)
  - `new WeatherServiceImpl(WeatherCache cache, ProviderChain chain)`

**Behaviour, in order:**
1. Fresh entry → return it, `stale = false`.
2. Otherwise try to win the refresh lock. On success, fetch and store; return `stale = false`.
3. Lost the lock and an old entry exists → return it, `stale = true`.
4. Refresh failed with `AllProvidersFailedException` and an old entry exists → return it, `stale = true`.
5. No entry at all → let the exception propagate.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/domain/WeatherServiceImplTest.java`:

```java
package com.singapore.weather.domain;

import com.singapore.weather.cache.MutableClock;
import com.singapore.weather.cache.WeatherCache;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherServiceImplTest {

    private static final Weather FRESH = new Weather(29.0, 20.0);
    private static final Weather NEWER = new Weather(30.0, 22.0);

    private MutableClock clock;
    private WeatherCache cache;
    private AtomicInteger providerCalls;
    private AtomicReference<RuntimeException> providerFailure;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-22T00:00:00Z"));
        cache = new WeatherCache(clock, Duration.ofSeconds(3), Duration.ofHours(24), 1000);
        providerCalls = new AtomicInteger();
        providerFailure = new AtomicReference<>();
    }

    private WeatherServiceImpl service(Weather result) {
        WeatherProvider provider = new WeatherProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public Weather fetch(String city) {
                providerCalls.incrementAndGet();
                RuntimeException failure = providerFailure.get();
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().ignoreExceptions(CityNotFoundException.class).build());
        RetryRegistry retries = RetryRegistry.of(
                RetryConfig.custom().maxAttempts(1).build());
        return new WeatherServiceImpl(cache, new ProviderChain(List.of(provider), breakers, retries));
    }

    @Test
    void fetchesFromTheProviderOnAColdCache() {
        WeatherResult result = service(FRESH).get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isFalse();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void servesFromCacheWithinTheFreshWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofMillis(2900));
        WeatherResult result = service.get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isFalse();
        assertThat(providerCalls)
                .as("a fresh entry must not reach the provider")
                .hasValue(1);
    }

    @Test
    void refreshesOncePastTheFreshWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofMillis(3100));
        service.get("singapore");

        assertThat(providerCalls).hasValue(2);
    }

    @Test
    void servesStaleWhenEveryProviderIsDown() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofSeconds(10));
        providerFailure.set(new ProviderException("down"));
        WeatherResult result = service.get("singapore");

        assertThat(result.weather()).isEqualTo(FRESH);
        assertThat(result.stale()).isTrue();
        assertThat(result.age()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void raisesWhenEveryProviderIsDownAndNothingWasEverCached() {
        WeatherServiceImpl service = service(FRESH);
        providerFailure.set(new ProviderException("down"));

        assertThatThrownBy(() -> service.get("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void raisesWhenTheStaleEntryIsBeyondTheRetentionWindow() {
        WeatherServiceImpl service = service(FRESH);
        service.get("singapore");

        clock.advance(Duration.ofHours(25));
        providerFailure.set(new ProviderException("down"));

        assertThatThrownBy(() -> service.get("singapore"))
                .isInstanceOf(AllProvidersFailedException.class);
    }

    @Test
    void propagatesCityNotFoundWithoutServingStale() {
        WeatherServiceImpl service = service(NEWER);
        providerFailure.set(new CityNotFoundException("atlantis"));

        assertThatThrownBy(() -> service.get("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherServiceImplTest`
Expected: FAIL — compilation error, `WeatherServiceImpl` does not exist.

- [ ] **Step 3: Write the service**

`src/main/java/com/singapore/weather/domain/WeatherResult.java`:

```java
package com.singapore.weather.domain;

import java.time.Duration;

/** A weather reading plus how old it is and whether it is being served stale. */
public record WeatherResult(Weather weather, boolean stale, Duration age) {

    public static WeatherResult fresh(Weather weather) {
        return new WeatherResult(weather, false, Duration.ZERO);
    }

    public static WeatherResult stale(Weather weather, Duration age) {
        return new WeatherResult(weather, true, age);
    }
}
```

`src/main/java/com/singapore/weather/domain/WeatherService.java`:

```java
package com.singapore.weather.domain;

public interface WeatherService {

    /**
     * @throws CityNotFoundException      no provider recognises the city
     * @throws AllProvidersFailedException every provider failed and no usable
     *                                     cached value exists
     */
    WeatherResult get(String city);
}
```

`src/main/java/com/singapore/weather/domain/WeatherServiceImpl.java`:

```java
package com.singapore.weather.domain;

import com.singapore.weather.cache.CachedWeather;
import com.singapore.weather.cache.WeatherCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WeatherServiceImpl implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherServiceImpl.class);

    private final WeatherCache cache;
    private final ProviderChain chain;

    public WeatherServiceImpl(WeatherCache cache, ProviderChain chain) {
        this.cache = cache;
        this.chain = chain;
    }

    @Override
    public WeatherResult get(String city) {
        Optional<CachedWeather> cached = cache.find(city);

        if (cached.isPresent() && cache.isFresh(cached.get())) {
            return WeatherResult.fresh(cached.get().weather());
        }

        Optional<WeatherResult> refreshed = cache.tryRefresh(city, () -> refresh(city, cached));
        if (refreshed.isPresent()) {
            return refreshed.get();
        }

        // Another caller is already refreshing this city. Do not queue behind it.
        return cached.map(entry -> WeatherResult.stale(entry.weather(), cache.age(entry)))
                .orElseGet(() -> refresh(city, Optional.empty()));
    }

    private WeatherResult refresh(String city, Optional<CachedWeather> fallback) {
        try {
            Weather weather = chain.fetch(city);
            cache.put(city, weather);
            return WeatherResult.fresh(weather);
        } catch (AllProvidersFailedException e) {
            CachedWeather entry = fallback.orElseThrow(() -> e);
            log.warn("Serving stale weather for {} — every provider failed", city);
            return WeatherResult.stale(entry.weather(), cache.age(entry));
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherServiceImplTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/singapore/weather/domain src/test/java/com/singapore/weather/domain/WeatherServiceImplTest.java
git commit -m "Add weather service orchestrating cache, failover and stale serving"
```

---

## Task 8: Weatherstack provider

**Files:**
- Create: `src/main/java/com/singapore/weather/provider/weatherstack/WeatherstackResponse.java`
- Create: `src/main/java/com/singapore/weather/provider/weatherstack/WeatherstackProvider.java`
- Test: `src/test/java/com/singapore/weather/provider/weatherstack/WeatherstackProviderTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherProvider`, `ProviderException`, `CityNotFoundException` (Task 3).
- Produces: `new WeatherstackProvider(RestClient restClient, String accessKey, int priority)`, whose `name()` returns `"weatherstack"`.

**The trap this task exists to close:** Weatherstack answers `HTTP 200` even on failure,
signalling the problem only through `success: false` in the body. Code that trusts the HTTP
status will never fail over and will hand callers a malformed payload while every health
signal stays green.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/provider/weatherstack/WeatherstackProviderTest.java`:

```java
package com.singapore.weather.provider.weatherstack;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WeatherstackProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private WeatherstackProvider provider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient client = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(factory)
                .build();
        return new WeatherstackProvider(client, "test-key", 1);
    }

    private void stub(int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void parsesASuccessfulResponse() {
        stub(200, """
                {"request":{"query":"Singapore"},
                 "current":{"temperature":29,"wind_speed":20,"humidity":70}}
                """);

        Weather weather = provider().fetch("singapore");

        assertThat(weather.temperatureCelsius()).isCloseTo(29.0, within(0.0001));
        assertThat(weather.windSpeedKmh()).isCloseTo(20.0, within(0.0001));
    }

    @Test
    void treatsSuccessFalseAsAFailureDespiteHttp200() {
        stub(200, """
                {"success":false,"error":{"code":104,"type":"usage_limit_reached",
                 "info":"Your monthly API request volume has been reached."}}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("104");
    }

    @Test
    void mapsTheUnresolvableLocationCodeToCityNotFound() {
        stub(200, """
                {"success":false,"error":{"code":615,"type":"request_failed",
                 "info":"Your API request failed."}}
                """);

        assertThatThrownBy(() -> provider().fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }

    @Test
    void treatsAMissingCurrentBlockAsAFailure() {
        stub(200, """
                {"request":{"query":"Singapore"}}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsMalformedJsonAsAFailure() {
        stub(200, "{not json");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAServerErrorAsAFailure() {
        stub(500, "upstream boom");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAReadTimeoutAsAFailure() {
        wiremock.stubFor(get(urlPathEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000)
                        .withBody("{}")));

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherstackProviderTest`
Expected: FAIL — compilation error, `WeatherstackProvider` does not exist.

- [ ] **Step 3: Write the DTO**

`src/main/java/com/singapore/weather/provider/weatherstack/WeatherstackResponse.java`:

```java
package com.singapore.weather.provider.weatherstack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherstackResponse(Boolean success, Error error, Current current) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("wind_speed") Double windSpeed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(Integer code, String type, String info) {
    }

    /** Weatherstack omits {@code success} on success and sets it to false on failure. */
    public boolean failed() {
        return Boolean.FALSE.equals(success);
    }
}
```

- [ ] **Step 4: Write the provider**

`src/main/java/com/singapore/weather/provider/weatherstack/WeatherstackProvider.java`:

```java
package com.singapore.weather.provider.weatherstack;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.singapore.weather.domain.WeatherProvider;
import org.springframework.web.client.RestClient;

import java.util.Set;

/**
 * Weatherstack adapter.
 *
 * <p>Weatherstack reports failures with HTTP 200 and {@code "success": false} in
 * the body, so the status code alone cannot be trusted — see {@link #fetch}.
 * Temperature is already Celsius and wind speed already km/h under the default
 * metric unit system, so no conversion is needed.
 */
public class WeatherstackProvider implements WeatherProvider {

    /** Weatherstack signals an unusable query with this error code. */
    private static final Set<Integer> CITY_NOT_FOUND_CODES = Set.of(615);

    private final RestClient restClient;
    private final String accessKey;
    private final int priority;

    public WeatherstackProvider(RestClient restClient, String accessKey, int priority) {
        this.restClient = restClient;
        this.accessKey = accessKey;
        this.priority = priority;
    }

    @Override
    public String name() {
        return "weatherstack";
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Weather fetch(String city) {
        WeatherstackResponse response;
        try {
            response = restClient.get()
                    .uri(builder -> builder.path("/current")
                            .queryParam("access_key", accessKey)
                            .queryParam("query", city)
                            .build())
                    .retrieve()
                    .body(WeatherstackResponse.class);
        } catch (RuntimeException e) {
            throw new ProviderException("Weatherstack call failed: " + e.getMessage(), e);
        }

        if (response == null) {
            throw new ProviderException("Weatherstack returned an empty body");
        }
        if (response.failed()) {
            throw toException(city, response.error());
        }
        if (response.current() == null
                || response.current().temperature() == null
                || response.current().windSpeed() == null) {
            throw new ProviderException("Weatherstack response is missing current readings");
        }

        return new Weather(response.current().temperature(), response.current().windSpeed());
    }

    private RuntimeException toException(String city, WeatherstackResponse.Error error) {
        if (error == null) {
            return new ProviderException("Weatherstack reported failure without an error body");
        }
        if (error.code() != null && CITY_NOT_FOUND_CODES.contains(error.code())) {
            return new CityNotFoundException(city);
        }
        // Unrecognised codes fail safe toward failover rather than a 404.
        return new ProviderException(
                "Weatherstack error %d (%s): %s".formatted(error.code(), error.type(), error.info()));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=WeatherstackProviderTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/singapore/weather/provider/weatherstack src/test/java/com/singapore/weather/provider/weatherstack
git commit -m "Add Weatherstack provider honouring the success flag"
```

---

## Task 9: OpenWeatherMap provider

**Files:**
- Create: `src/main/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapResponse.java`
- Create: `src/main/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapProvider.java`
- Test: `src/test/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapProviderTest.java`

**Interfaces:**
- Consumes: `Weather`, `WeatherProvider`, `ProviderException`, `CityNotFoundException` (Task 3); `Weather.ofMetresPerSecond` for the unit conversion.
- Produces: `new OpenWeatherMapProvider(RestClient restClient, String apiKey, int priority)`, whose `name()` returns `"openweathermap"`.

**Unit note:** the request sends `units=metric`, which gives Celsius but leaves wind in
**metres per second**. The conversion to km/h happens here so the rest of the system only
ever sees km/h.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapProviderTest.java`:

```java
package com.singapore.weather.provider.openweathermap;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OpenWeatherMapProviderTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OpenWeatherMapProvider provider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient client = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(factory)
                .build();
        return new OpenWeatherMapProvider(client, "test-key", 2);
    }

    private void stub(int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/data/2.5/weather"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void parsesAndConvertsWindToKilometresPerHour() {
        stub(200, """
                {"main":{"temp":29.4,"humidity":74},"wind":{"speed":5.5,"deg":90}}
                """);

        Weather weather = provider().fetch("singapore");

        assertThat(weather.temperatureCelsius()).isCloseTo(29.4, within(0.0001));
        assertThat(weather.windSpeedKmh())
                .as("5.5 m/s is 19.8 km/h")
                .isCloseTo(19.8, within(0.0001));
    }

    @Test
    void mapsHttp404ToCityNotFound() {
        stub(404, """
                {"cod":"404","message":"city not found"}
                """);

        assertThatThrownBy(() -> provider().fetch("atlantis"))
                .isInstanceOf(CityNotFoundException.class);
    }

    @Test
    void mapsUnauthorisedToAProviderFailure() {
        stub(401, """
                {"cod":401,"message":"Invalid API key."}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void mapsRateLimitingToAProviderFailure() {
        stub(429, """
                {"cod":429,"message":"Your account is temporary blocked."}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void mapsServerErrorsToAProviderFailure() {
        stub(500, "boom");

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAMissingWindBlockAsAFailure() {
        stub(200, """
                {"main":{"temp":29.4}}
                """);

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void treatsAReadTimeoutAsAFailure() {
        wiremock.stubFor(get(urlPathEqualTo("/data/2.5/weather"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));

        assertThatThrownBy(() -> provider().fetch("singapore"))
                .isInstanceOf(ProviderException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=OpenWeatherMapProviderTest`
Expected: FAIL — compilation error, `OpenWeatherMapProvider` does not exist.

- [ ] **Step 3: Write the DTO**

`src/main/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapResponse.java`:

```java
package com.singapore.weather.provider.openweathermap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenWeatherMapResponse(Main main, Wind wind) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Main(Double temp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Wind(Double speed) {
    }
}
```

- [ ] **Step 4: Write the provider**

`src/main/java/com/singapore/weather/provider/openweathermap/OpenWeatherMapProvider.java`:

```java
package com.singapore.weather.provider.openweathermap;

import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.ProviderException;
import com.singapore.weather.domain.Weather;
import com.singapore.weather.domain.WeatherProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * OpenWeatherMap adapter.
 *
 * <p>The request asks for {@code units=metric}, which returns Celsius but leaves
 * wind speed in metres per second. The conversion to km/h happens here so the
 * rest of the system only ever sees km/h.
 */
public class OpenWeatherMapProvider implements WeatherProvider {

    private final RestClient restClient;
    private final String apiKey;
    private final int priority;

    public OpenWeatherMapProvider(RestClient restClient, String apiKey, int priority) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.priority = priority;
    }

    @Override
    public String name() {
        return "openweathermap";
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Weather fetch(String city) {
        OpenWeatherMapResponse response;
        try {
            response = restClient.get()
                    .uri(builder -> builder.path("/data/2.5/weather")
                            .queryParam("q", city)
                            .queryParam("appid", apiKey)
                            .queryParam("units", "metric")
                            .build())
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, clientResponse) -> {
                                throw new CityNotFoundException(city);
                            })
                    .body(OpenWeatherMapResponse.class);
        } catch (CityNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderException("OpenWeatherMap call failed: " + e.getMessage(), e);
        }

        if (response == null
                || response.main() == null || response.main().temp() == null
                || response.wind() == null || response.wind().speed() == null) {
            throw new ProviderException("OpenWeatherMap response is missing readings");
        }

        return Weather.ofMetresPerSecond(response.main().temp(), response.wind().speed());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=OpenWeatherMapProviderTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/singapore/weather/provider/openweathermap src/test/java/com/singapore/weather/provider/openweathermap
git commit -m "Add OpenWeatherMap provider with m/s to km/h conversion"
```

---

## Task 10: HTTP API layer

**Files:**
- Create: `src/main/java/com/singapore/weather/api/CityValidator.java`
- Create: `src/main/java/com/singapore/weather/api/WeatherResponse.java`
- Create: `src/main/java/com/singapore/weather/api/WeatherController.java`
- Create: `src/main/java/com/singapore/weather/api/GlobalExceptionHandler.java`
- Test: `src/test/java/com/singapore/weather/api/CityValidatorTest.java`
- Test: `src/test/java/com/singapore/weather/api/WeatherControllerTest.java`

**Interfaces:**
- Consumes: `WeatherService`, `WeatherResult`, `Weather`, `CityNotFoundException`, `AllProvidersFailedException`, `InvalidCityException`.
- Produces:
  - `CityValidator.normalise(String raw) -> String` — trims, lowercases, defaults blank/null to `"singapore"`, throws `InvalidCityException` when malformed.
  - `WeatherResponse.from(Weather) -> WeatherResponse` — rounds to whole numbers.
  - `GET /v1/weather` returning the two-field body, plus `X-Weather-Stale` and `Age` headers when stale.

- [ ] **Step 1: Write the failing validator test**

`src/test/java/com/singapore/weather/api/CityValidatorTest.java`:

```java
package com.singapore.weather.api;

import com.singapore.weather.domain.InvalidCityException;
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
    void rejectsOverlyLongInput() {
        assertThatThrownBy(() -> CityValidator.normalise("a".repeat(65)))
                .isInstanceOf(InvalidCityException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=CityValidatorTest`
Expected: FAIL — compilation error, `CityValidator` does not exist.

- [ ] **Step 3: Write the validator**

`src/main/java/com/singapore/weather/api/CityValidator.java`:

```java
package com.singapore.weather.api;

import com.singapore.weather.domain.InvalidCityException;

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
        return trimmed.toLowerCase();
    }
}
```

- [ ] **Step 4: Run the validator test**

Run: `./mvnw -B test -Dtest=CityValidatorTest`
Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing controller test**

`src/test/java/com/singapore/weather/api/WeatherControllerTest.java`:

```java
package com.singapore.weather.api;

import com.singapore.weather.domain.AllProvidersFailedException;
import com.singapore.weather.domain.CityNotFoundException;
import com.singapore.weather.domain.Weather;
import com.singapore.weather.domain.WeatherResult;
import com.singapore.weather.domain.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherController.class)
class WeatherControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WeatherService weatherService;

    @Test
    void returnsExactlyTheSpecifiedPayload() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.0, 20.0)));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void roundsFractionalReadingsToWholeNumbers() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.4, 19.8)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void defaultsToSingaporeWhenCityIsOmitted() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.fresh(new Weather(29.0, 20.0)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk());
    }

    @Test
    void marksStaleResponsesWithHeadersButKeepsTheBodyUnchanged() throws Exception {
        given(weatherService.get("singapore"))
                .willReturn(WeatherResult.stale(new Weather(29.0, 20.0), Duration.ofSeconds(42)));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Weather-Stale", "true"))
                .andExpect(header().string("Age", "42"))
                .andExpect(content().json("""
                        {"wind_speed": 20, "temperature_degrees": 29}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void rejectsMalformedCityWithBadRequest() throws Exception {
        mockMvc.perform(get("/v1/weather").param("city", "<script>"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsUnknownCityAsNotFound() throws Exception {
        willThrow(new CityNotFoundException("atlantis"))
                .given(weatherService).get(eq("atlantis"));

        mockMvc.perform(get("/v1/weather").param("city", "atlantis"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsTotalOutageWithoutCacheAsServiceUnavailable() throws Exception {
        willThrow(new AllProvidersFailedException("all down"))
                .given(weatherService).get(eq("singapore"));

        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isServiceUnavailable());
    }
}
```

Two version-sensitive points here, both already accounted for:

- `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) replaces the old
  `@MockBean`, which Boot 4 **removed**. Do not reach for `@MockBean`.
- `JsonCompareMode` lives in `org.springframework.test.json`. If it cannot be resolved, use
  the boolean overload `content().json(expected, true)` — `true` means strict.

- [ ] **Step 6: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=WeatherControllerTest`
Expected: FAIL — compilation error, `WeatherController` does not exist.

- [ ] **Step 7: Write the response record**

`src/main/java/com/singapore/weather/api/WeatherResponse.java`:

```java
package com.singapore.weather.api;

import com.singapore.weather.domain.Weather;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The public contract: exactly two fields, in this order, as whole numbers.
 * Rounding here rather than in the domain keeps the response identical no
 * matter which provider answered.
 */
@JsonPropertyOrder({"wind_speed", "temperature_degrees"})
public record WeatherResponse(
        @JsonProperty("wind_speed") long windSpeed,
        @JsonProperty("temperature_degrees") long temperatureDegrees) {

    public static WeatherResponse from(Weather weather) {
        return new WeatherResponse(
                Math.round(weather.windSpeedKmh()),
                Math.round(weather.temperatureCelsius()));
    }
}
```

- [ ] **Step 8: Write the controller**

`src/main/java/com/singapore/weather/api/WeatherController.java`:

```java
package com.singapore.weather.api;

import com.singapore.weather.domain.WeatherResult;
import com.singapore.weather.domain.WeatherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/v1/weather")
    public ResponseEntity<WeatherResponse> weather(
            @RequestParam(name = "city", required = false) String city) {

        WeatherResult result = weatherService.get(CityValidator.normalise(city));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();

        if (result.stale()) {
            response.header("X-Weather-Stale", "true")
                    .header("Age", Long.toString(result.age().toSeconds()));
        }

        return response.body(WeatherResponse.from(result.weather()));
    }
}
```

- [ ] **Step 9: Write the exception handler**

`src/main/java/com/singapore/weather/api/GlobalExceptionHandler.java`:

```java
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
```

- [ ] **Step 10: Run the controller test**

Run: `./mvnw -B test -Dtest=WeatherControllerTest`
Expected: PASS, `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/singapore/weather/api src/test/java/com/singapore/weather/api
git commit -m "Add weather endpoint, response contract and problem responses"
```

---

## Task 11: Spring wiring

**Files:**
- Create: `src/main/java/com/singapore/weather/config/RestClientConfig.java`
- Create: `src/main/java/com/singapore/weather/config/ResilienceConfig.java`
- Create: `src/main/java/com/singapore/weather/config/ProviderConfig.java`
- Create: `src/main/java/com/singapore/weather/config/CacheConfig.java`
- Create: `src/main/java/com/singapore/weather/health/ProviderHealthIndicator.java`
- Test: `src/test/java/com/singapore/weather/config/ProviderConfigTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–10.
- Produces: a fully wired application — `WeatherCache`, `ProviderChain`, both providers, both registries, and a health indicator — plus the rule that a provider without an API key is disabled rather than fatal.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/singapore/weather/config/ProviderConfigTest.java`:

```java
package com.singapore.weather.config;

import com.singapore.weather.domain.WeatherProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigTest {

    @SpringBootTest
    @TestPropertySource(properties = {
            "weather.providers.weatherstack.api-key=ws",
            "weather.providers.openweathermap.api-key=owm"
    })
    static class BothKeysPresent {

        @Autowired
        List<WeatherProvider> providers;

        @Test
        void registersBothProvidersInPriorityOrder() {
            assertThat(providers).hasSize(2);
            assertThat(providers.stream().map(WeatherProvider::name))
                    .containsExactlyInAnyOrder("weatherstack", "openweathermap");
        }
    }

    @SpringBootTest
    @TestPropertySource(properties = {
            "weather.providers.weatherstack.api-key=",
            "weather.providers.openweathermap.api-key=owm"
    })
    static class OnlyFailoverKeyPresent {

        @Autowired
        List<WeatherProvider> providers;

        @Test
        void disablesTheProviderWithoutAKeyInsteadOfFailingStartup() {
            assertThat(providers).hasSize(1);
            assertThat(providers.getFirst().name()).isEqualTo("openweathermap");
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B test -Dtest=ProviderConfigTest`
Expected: FAIL — no `WeatherProvider` beans exist.

- [ ] **Step 3: Write `RestClientConfig`**

`src/main/java/com/singapore/weather/config/RestClientConfig.java`:

```java
package com.singapore.weather.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class RestClientConfig {

    private RestClientConfig() {
    }

    public static RestClient forProvider(WeatherProperties.Provider provider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(provider.connectTimeout());
        factory.setReadTimeout(provider.readTimeout());

        return RestClient.builder()
                .baseUrl(provider.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
```

- [ ] **Step 4: Write `ResilienceConfig`**

`src/main/java/com/singapore/weather/config/ResilienceConfig.java`:

```java
package com.singapore.weather.config;

import com.singapore.weather.domain.CityNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j is wired by hand rather than through resilience4j-spring-boot3,
 * which still targets Spring Framework 6 while Boot 4.1 ships Spring Framework 7.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(WeatherProperties properties) {
        WeatherProperties.Resilience r = properties.resilience();
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(r.slidingWindowSize())
                .minimumNumberOfCalls(r.slidingWindowSize())
                .failureRateThreshold(r.failureRateThreshold())
                .waitDurationInOpenState(r.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(r.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // A city the provider does not know is a client error, not an outage.
                .ignoreExceptions(CityNotFoundException.class)
                .build());
    }

    @Bean
    RetryRegistry retryRegistry(WeatherProperties properties) {
        WeatherProperties.Resilience r = properties.resilience();
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(r.retryMaxAttempts())
                .waitDuration(r.retryWaitDuration())
                // Retrying will not teach a provider a city it has never heard of.
                .ignoreExceptions(CityNotFoundException.class)
                .build());
    }

    @Bean
    TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry registry,
                                                      MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
```

- [ ] **Step 5: Write `CacheConfig`**

`src/main/java/com/singapore/weather/config/CacheConfig.java`:

```java
package com.singapore.weather.config;

import com.singapore.weather.cache.WeatherCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CacheConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WeatherCache weatherCache(Clock clock, WeatherProperties properties) {
        WeatherProperties.Cache cache = properties.cache();
        return new WeatherCache(clock, cache.freshTtl(), cache.staleRetention(), cache.maxSize());
    }

    /**
     * Publishes hit rate, size and eviction counts under the {@code cache.*}
     * meters. Without this binding {@code recordStats()} collects numbers nobody
     * can see.
     */
    @Bean
    InitializingBean weatherCacheMetrics(WeatherCache cache, MeterRegistry meterRegistry) {
        return () -> CaffeineCacheMetrics.monitor(meterRegistry, cache.caffeine(), "weather");
    }
}
```

- [ ] **Step 6: Write `ProviderConfig`**

`src/main/java/com/singapore/weather/config/ProviderConfig.java`:

```java
package com.singapore.weather.config;

import com.singapore.weather.domain.ProviderChain;
import com.singapore.weather.domain.WeatherProvider;
import com.singapore.weather.provider.openweathermap.OpenWeatherMapProvider;
import com.singapore.weather.provider.weatherstack.WeatherstackProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * A provider without an API key is disabled with a warning rather than failing
 * startup, so a reviewer holding only one key can still run the service. If no
 * provider is enabled at all, startup fails deliberately.
 */
@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    @Bean
    List<WeatherProvider> weatherProviders(WeatherProperties properties) {
        List<WeatherProvider> providers = new java.util.ArrayList<>();

        WeatherProperties.Provider ws = properties.providers().weatherstack();
        if (ws.isConfigured()) {
            providers.add(new WeatherstackProvider(RestClientConfig.forProvider(ws), ws.apiKey(), ws.priority()));
        } else {
            log.warn("Weatherstack disabled: WEATHERSTACK_API_KEY is not set");
        }

        WeatherProperties.Provider owm = properties.providers().openweathermap();
        if (owm.isConfigured()) {
            providers.add(new OpenWeatherMapProvider(RestClientConfig.forProvider(owm), owm.apiKey(), owm.priority()));
        } else {
            log.warn("OpenWeatherMap disabled: OPENWEATHERMAP_API_KEY is not set");
        }

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No weather provider is configured. Set WEATHERSTACK_API_KEY and/or OPENWEATHERMAP_API_KEY.");
        }
        return providers;
    }

    @Bean
    ProviderChain providerChain(List<WeatherProvider> providers,
                                CircuitBreakerRegistry breakers,
                                RetryRegistry retries) {
        return new ProviderChain(providers, breakers, retries);
    }
}
```

- [ ] **Step 7: Write the health indicator**

`src/main/java/com/singapore/weather/health/ProviderHealthIndicator.java`:

```java
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
```

If `org.springframework.boot.health.contributor.HealthIndicator` cannot be resolved, use
`org.springframework.boot.actuate.health.HealthIndicator` and
`org.springframework.boot.actuate.health.Health` instead — Boot 4 relocated these types.

- [ ] **Step 8: Run the wiring test**

Run: `./mvnw -B test -Dtest=ProviderConfigTest`
Expected: PASS, `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 9: Run the full suite**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS, all tests from Tasks 1–11 pass.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/singapore/weather/config src/main/java/com/singapore/weather/health src/test/java/com/singapore/weather/config
git commit -m "Wire providers, cache, resilience registries and health indicator"
```

---

## Task 12: End-to-end integration tests

**Files:**
- Create: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/singapore/weather/FailoverIntegrationTest.java`
- Test: `src/test/java/com/singapore/weather/StaleAfterTotalOutageTest.java`

**Interfaces:**
- Consumes: the fully wired application from Task 11.
- Produces: proof that the two headline requirements — fast failover and mandatory stale serving — hold end to end.

- [ ] **Step 1: Write the test profile**

`src/test/resources/application-test.yml`:

```yaml
weather:
  resilience:
    # A smaller window opens the circuit quickly and deterministically.
    # The production window of 10 would make these tests slow and brittle.
    sliding-window-size: 4
    failure-rate-threshold: 50
    wait-duration-in-open-state: 10s
    permitted-calls-in-half-open-state: 2
    retry-max-attempts: 1
    retry-wait-duration: 10ms
```

- [ ] **Step 2: Write the failover test**

`src/test/java/com/singapore/weather/FailoverIntegrationTest.java`:

```java
package com.singapore.weather;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// WireMock's static get(...) and MockMvcRequestBuilders.get(...) collide, so
// WireMock's builders stay qualified as WireMock.get(...) throughout this class.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailoverIntegrationTest {

    static final WireMockServer weatherstack = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer openweathermap = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        weatherstack.start();
        openweathermap.start();

        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"main\":{\"temp\":28.0},\"wind\":{\"speed\":5.0}}")));

        registry.add("weather.providers.weatherstack.base-url", weatherstack::baseUrl);
        registry.add("weather.providers.weatherstack.api-key", () -> "ws-key");
        registry.add("weather.providers.openweathermap.base-url", openweathermap::baseUrl);
        registry.add("weather.providers.openweathermap.api-key", () -> "owm-key");
        // Defeat the 3s cache so each request really reaches the chain.
        // -1s rather than 0s: isFresh() tests age <= freshTtl, so a zero TTL
        // would still count two requests in the same clock tick as fresh.
        registry.add("weather.cache.fresh-ttl", () -> "-1s");
    }

    @AfterAll
    static void stopStubs() {
        weatherstack.stop();
        openweathermap.stop();
    }

    @Test
    void servesFromTheFailoverProviderAndStopsCallingTheDeadPrimary() throws Exception {
        // 5.0 m/s is 18 km/h; 28.0 C stays 28.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.temperature_degrees").value(28))
                    .andExpect(jsonPath("$.wind_speed").value(18));
        }

        int callsBeforeCircuitOpened = weatherstack.getServeEvents().getServeEvents().size();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                    .andExpect(status().isOk());
        }

        // An open circuit must skip the provider entirely, not merely fail fast.
        Assertions.assertThat(weatherstack.getServeEvents().getServeEvents().size())
                .isEqualTo(callsBeforeCircuitOpened);
    }
}
```

- [ ] **Step 3: Write the stale-serving test**

`src/test/java/com/singapore/weather/StaleAfterTotalOutageTest.java`:

```java
package com.singapore.weather;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// As in FailoverIntegrationTest, WireMock builders stay qualified to avoid
// colliding with MockMvcRequestBuilders.get.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaleAfterTotalOutageTest {

    static final WireMockServer weatherstack = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer openweathermap = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        weatherstack.start();
        openweathermap.start();

        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"current\":{\"temperature\":29,\"wind_speed\":20}}")));

        registry.add("weather.providers.weatherstack.base-url", weatherstack::baseUrl);
        registry.add("weather.providers.weatherstack.api-key", () -> "ws-key");
        registry.add("weather.providers.openweathermap.base-url", openweathermap::baseUrl);
        registry.add("weather.providers.openweathermap.api-key", () -> "owm-key");
        registry.add("weather.cache.fresh-ttl", () -> "-1s");
    }

    @AfterAll
    static void stopStubs() {
        weatherstack.stop();
        openweathermap.stop();
    }

    @Test
    void servesTheLastKnownReadingWhenEveryProviderGoesDown() throws Exception {
        // Prime the cache while the primary is healthy.
        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Weather-Stale"))
                .andExpect(jsonPath("$.temperature_degrees").value(29));

        // Now take every provider down.
        weatherstack.resetAll();
        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Weather-Stale", "true"))
                .andExpect(jsonPath("$.wind_speed").value(20))
                .andExpect(jsonPath("$.temperature_degrees").value(29));
    }

    @Test
    void reportsUnavailableForACityThatWasNeverCached() throws Exception {
        weatherstack.resetAll();
        weatherstack.stubFor(WireMock.get(WireMock.urlPathEqualTo("/current"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));
        openweathermap.stubFor(WireMock.get(WireMock.urlPathEqualTo("/data/2.5/weather"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("boom")));

        mockMvc.perform(get("/v1/weather").param("city", "reykjavik"))
                .andExpect(status().isServiceUnavailable());
    }
}
```

- [ ] **Step 4: Run the integration tests**

Run: `./mvnw -B test -Dtest='FailoverIntegrationTest,StaleAfterTotalOutageTest'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the entire suite**

Run: `./mvnw -B verify`
Expected: BUILD SUCCESS with every test green.

- [ ] **Step 6: Commit**

```bash
git add src/test
git commit -m "Add end-to-end failover and stale-serving integration tests"
```

---

## Task 13: Packaging, CI and README

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `.env.example`
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: the complete, tested application.
- Produces: the submission artefacts — reproducible build, green CI, and the documentation the brief asks for.

- [ ] **Step 1: Write the Dockerfile**

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/singapore-weather-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: Write `.dockerignore` and `.env.example`**

`.dockerignore`:

```
target/
.git/
.idea/
docs/
*.md
```

`.env.example`:

```bash
# Obtain from https://weatherstack.com/ — free tier is HTTP-only
WEATHERSTACK_API_KEY=your_weatherstack_key

# Obtain from https://openweathermap.org/api
OPENWEATHERMAP_API_KEY=your_openweathermap_key
```

- [ ] **Step 3: Write the CI workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
      - run: ./mvnw -B verify
```

- [ ] **Step 4: Verify the Docker build**

Run: `docker build -t singapore-weather .`
Expected: build succeeds and prints a final image id.

If Docker is unavailable on this machine, skip this step and note it in the README's
trade-offs section rather than leaving an untested claim.

- [ ] **Step 5: Write the README**

`README.md` must contain these sections, in this order. Write real prose in each — no
headings without content.

1. **What this is** — one paragraph, plus the example request and its response:

   ```bash
   curl "http://localhost:8080/v1/weather?city=singapore"
   # {"wind_speed":20,"temperature_degrees":29}
   ```

2. **Quick start** — prerequisites (JDK 25, or Docker), the two environment variables,
   `./mvnw spring-boot:run`, and the Docker alternative:

   ```bash
   export WEATHERSTACK_API_KEY=...
   export OPENWEATHERMAP_API_KEY=...
   ./mvnw spring-boot:run
   ```

3. **Obtaining API keys** — signup links for both providers, and the note that the
   Weatherstack free tier is HTTP-only, which is why its base URL is `http://`.

4. **Architecture** — the flow diagram from the design spec plus the layer table.

5. **Adding a new provider** — the four-step recipe, written so someone can follow it:
   implement `WeatherProvider` in a new `provider.<vendor>` package; give it a `priority`;
   add a configuration block under `weather.providers`; register it in `ProviderConfig` and
   write one WireMock test. State explicitly that no other file needs to change.

6. **Resilience behaviour** — the 3-second cache, stale serving, circuit breaker thresholds,
   and stampede protection, with the reasoning for each.

7. **Configuration reference** — a table of every `weather.*` property, its default, and the
   environment variable that overrides it.

8. **Testing** — `./mvnw verify`, and what each of the four layers covers.

9. **Observability** — `/actuator/health` (including per-provider circuit state) and
   `/actuator/metrics`.

10. **Trade-offs and what I'd do differently** — copy the table from §11 of the design spec.
    This section is explicitly requested by the brief; do not abbreviate it.

- [ ] **Step 6: Verify the service really works end to end**

With real API keys exported:

```bash
./mvnw -B spring-boot:run &
sleep 20
curl -s "http://localhost:8080/v1/weather?city=singapore"
curl -s "http://localhost:8080/actuator/health"
```

Expected: the weather call returns a two-field JSON body, and health reports `UP` with a
circuit state for each provider. Stop the process afterwards.

If no real API keys are available, say so plainly in the README rather than implying the
live path was verified.

- [ ] **Step 7: Final full verification**

Run: `./mvnw -B clean verify`
Expected: BUILD SUCCESS, every test green, and `target/singapore-weather-1.0.0.jar` produced.

- [ ] **Step 8: Commit**

```bash
git add Dockerfile .dockerignore .env.example .github README.md
git commit -m "Add Docker packaging, CI workflow and README"
```

---

## Definition of Done

- [ ] `./mvnw clean verify` passes from a clean checkout.
- [ ] `curl "http://localhost:8080/v1/weather?city=singapore"` returns exactly
      `{"wind_speed":N,"temperature_degrees":N}` — two fields, that order, whole numbers.
- [ ] Killing the primary provider still returns 200, sourced from the failover.
- [ ] Killing both providers still returns 200 with `X-Weather-Stale: true`, provided the
      city was cached within the last 24 hours.
- [ ] No API key appears anywhere in git history.
- [ ] No test calls `Thread.sleep` and no test reaches the real internet.
- [ ] README contains the trade-offs section the brief asks for.
