# Singapore Weather Service — Design

Date: 2026-08-22

This document records why the service is built the way it is — the alternatives that were
weighed and why each was rejected. `README.md` covers what it does and how to run it.

## 1. Purpose

An HTTP service that reports current weather, sourcing from Weatherstack (primary) with
automatic failover to OpenWeatherMap. It returns a provider-agnostic JSON payload, caches
results for 3 seconds, and keeps serving the last known value when every provider is down.

The service must be safe for new developers to change. That is treated as a design
requirement, not a documentation task: it is met through a single provider abstraction, an
enforced response contract, and tests that fail loudly when either is violated.

## 2. Requirements

### Functional

1. `GET /v1/weather?city={city}` returns `{"wind_speed": <n>, "temperature_degrees": <n>}`.
2. `city` is optional and defaults to `singapore`. Matching is case-insensitive.
3. Arbitrary cities are supported — the value is forwarded to the providers. Malformed
   input returns 400; a well-formed city no provider recognises returns 404.
4. Weatherstack is tried first; OpenWeatherMap is the failover.
5. Results are cached for up to 3 seconds to avoid hammering providers.
6. When all providers fail, the cached value MUST be served as stale rather than erroring.

### Non-functional

1. Failover must be fast — a request must not pay a full timeout for a provider already
   known to be down.
2. Adding a third provider must require no changes outside the new provider's own package
   and one configuration block.
3. Test suite must be deterministic and fast: no `Thread.sleep`, no real network calls.
4. API keys must never be committed.

### Out of scope

Authentication and rate limiting on our own endpoint; distributed caching; load testing;
distributed tracing. Each is recorded in §11 with the reasoning.

## 3. Technology choices

| Choice | Decision | Reasoning |
| --- | --- | --- |
| Language / runtime | Java 25 (LTS) | Latest LTS; virtual threads make blocking provider calls cheap. Adoption risk for reviewers is mitigated by a Dockerfile. |
| Framework | Spring Boot 4.1.1 | Latest stable. Verified compiling, packaging and booting on Java 25 before the stack was committed to. |
| Build | Maven, with the Maven Wrapper committed | Reviewers run `./mvnw` with nothing installed. |
| Cache | Caffeine | In-process, high throughput, native support for size bounds and stats. |
| Resilience | Resilience4j **core modules**, used programmatically | See below — the Spring Boot starter is deliberately not used. |
| HTTP client | Spring `RestClient` | Per-provider instances with independent timeouts. |
| Testing | JUnit 5, WireMock, MockMvc | Covers logic, real HTTP parsing, and the response contract respectively. |

Virtual threads are enabled (`spring.threads.virtual.enabled=true`) so blocking provider
calls do not pin platform threads.

### Why Resilience4j is wired by hand

The usual choice would be `resilience4j-spring-boot3` with `@CircuitBreaker` annotations.
That artifact, at its current 2.4.0 release, depends on `resilience4j-spring6` — built
against Spring Framework 6 — while Spring Boot 4.1.1 ships Spring Framework 7. No
`resilience4j-spring7` artifact exists. Building the annotation-driven integration on Boot 4
therefore risks AOP and auto-configuration breakage for no gain.

The core modules carry no such coupling: `resilience4j-circuitbreaker` depends only on
`resilience4j-core` and `slf4j-api`. They were verified against Spring Boot 4.1.1 on Java 25
against Spring Boot 4.1.1 on Java 25 before the stack was committed to.

Wiring them by hand costs little here, because `ProviderChain` already invokes providers
explicitly — there was never a need for AOP to intercept anything. It also makes the
resilience behaviour visible at the call site rather than hidden behind an annotation, which
serves the "new developers can change this safely" requirement.

This is the one place where an alternative would change the stack: staying on Spring Boot
3.5.x would allow the annotation-based starter. That trade is rejected because Boot 4.1.1
runs on Java 25 today, whereas Boot 3.5 does not list Java 25 as supported.

## 4. Architecture

```
GET /v1/weather?city=singapore
        |
        v
 WeatherController ------ validate & normalise city (blank -> "singapore")
        |
        v
 WeatherService  <---------------+
        |                        |
        v                        |
 WeatherCache (Caffeine)         |
   entry age <= 3s? --yes--> return FRESH
        | no                     |
        v                        |
 Provider chain -----------------+ store result
   1. WeatherstackProvider   [CircuitBreaker + Timeout + Retry]
   2. OpenWeatherMapProvider [CircuitBreaker + Timeout + Retry]
        |
        +-- any success --> normalise units --> cache.put --> FRESH
        |
        +-- all failed --> stale entry exists? --yes--> return STALE (200)
                                              --no---> 503
```

### Package layout (`com.singapore.weather`)

| Package | Contents | Responsibility |
| --- | --- | --- |
| `api` | `WeatherController`, `WeatherResponse`, `GlobalExceptionHandler` | HTTP contract and JSON shape. The only layer that knows about HTTP. |
| `model` | `Weather`, `WeatherResult`, `CachedWeather` | Immutable value types shared across layers. |
| `exception` | `ProviderException`, `CityNotFoundException`, `AuthenticationFailedException`, `AllProvidersFailedException`, `InvalidCityException` | Failure vocabulary shared across layers. |
| `service` | `WeatherProvider`, `WeatherService` (interface), `WeatherServiceImpl`, `ProviderChain` | Orchestration. Knows nothing about Weatherstack or OpenWeatherMap. |
| `cache` | `WeatherCache` | Soft-TTL / hard-TTL logic and stampede protection. Uses an injected `Clock`. |
| `provider.weatherstack` | `WeatherstackProvider` and its response DTOs | Vendor detail, fully isolated. |
| `provider.openweathermap` | `OpenWeatherMapProvider` and its response DTOs | Vendor detail, fully isolated. |
| `config` | `WeatherProperties`, `RestClientConfig` | Timeouts, base URLs, credentials. |

### The provider abstraction

```java
public interface WeatherProvider {
    String name();
    int priority();              // lower runs first
    Weather fetch(String city);  // throws ProviderException on any failure
}
```

Spring injects `List<WeatherProvider>`, sorted by `priority()`. `WeatherServiceImpl` iterates
the list and never learns which provider answered.

### Service layer convention

The service layer follows the project's `Interface` + `Impl` convention: `WeatherService`
declares the contract, `WeatherServiceImpl` provides the single implementation, and
`WeatherController` depends only on the interface.

The convention stops at the service layer. `WeatherCache` stays a concrete class — it has
one obvious shape and no second implementation is anticipated within this scope — and
`WeatherProvider` is an interface on its own merits, since it genuinely has two
implementations and gaining a third is an explicit requirement.

Isolation boundaries that must hold:

- `WeatherServiceImpl` does not know which provider responded.
- Providers do not know a cache exists.
- The controller does not know failover exists.

Each of the three can be tested alone. If a change requires touching all three, the
boundaries have drifted and should be corrected rather than worked around.

## 5. API contract

### Success — 200

```json
{
  "wind_speed": 20,
  "temperature_degrees": 29
}
```

Exactly two fields, in this order, enforced by a `record` plus `@JsonPropertyOrder` and
verified by a MockMvc assertion in STRICT comparison mode. Strict mode is deliberate: if
anyone later adds a third field, that test fails.

`wind_speed` is in **km/h**. `temperature_degrees` is in **degrees Celsius**. Both are
rounded to the nearest whole number.

Rounding is a deliberate choice. Weatherstack returns integers and OpenWeatherMap returns
decimals; rounding both makes the response shape identical regardless of which provider
served it, so callers cannot infer the source. Sub-degree precision carries no meaning for
a weather report.

### Non-success

| Situation | Status | Body |
| --- | --- | --- |
| All providers down, cached value exists | 200 + `X-Weather-Stale: true` and `Age: <seconds>` | Normal payload |
| All providers down, no cached value ever | 503 + `Retry-After` and `Cache-Control: no-store` | `ProblemDetail` (RFC 7807) |
| `city` malformed | 400 | `ProblemDetail` |
| `city` well-formed but unknown to every provider | 404 | `ProblemDetail` |

### Input validation and unknown cities

Because arbitrary cities are supported, two distinct rejection cases must not be conflated.

**Malformed input — 400.** A city is accepted when, after trimming, it is 1–64 characters
and contains only letters, spaces, hyphens, apostrophes, periods and commas. Anything else
is rejected before any provider is contacted. This is both a correctness rule and the first
line of defence for the cache and lock stripes.

**Unknown city — 404.** A well-formed city that no provider recognises is a client error,
not an outage. It gets its own exception type, `CityNotFoundException`, which is handled
differently from `ProviderException` in three ways:

1. It does **not** count as a circuit breaker failure. Without this separation, a client
   requesting nonexistent cities could drive the failure rate past the threshold and open
   the circuit on a perfectly healthy provider — user input causing a self-inflicted outage.
2. It is **not** retried. The answer will not change on a second attempt.
3. It still **falls through to the next provider**, since provider gazetteers differ and
   OpenWeatherMap may recognise a city Weatherstack does not. Only when every provider
   reports not-found does the service return 404.

Not-found results are not cached; the volume does not justify a second cache, and the cost
of a miss is one cheap provider call.

The stale marker lives in a header rather than the body. This is a judgement call, not a
rule the brief imposes: the brief asks for a response *containing* temperature and wind
speed, which is inclusive wording and does not forbid a third field — and where the brief
does mean to compel, it says so in capitals ("MUST be served as stale").

The reasoning is narrower. The expected output is given as a literal payload, and such
examples are frequently compared verbatim. Returning a body identical to the example removes
the only way this design could be judged non-conforming, and costs nothing, because the
header carries the same signal to anyone operating the service. Should a caller ever need
staleness in the body, it is a one-line addition to `WeatherResponse` — the decision is
cheap to reverse.

## 6. Provider integration

| | Weatherstack (primary) | OpenWeatherMap (failover) |
| --- | --- | --- |
| Endpoint | `/current?access_key=…&query={city}` | `/data/2.5/weather?q={city}&appid=…&units=metric` |
| Temperature | `current.temperature`, °C with default `units=m` | `main.temp`, Kelvin by default — `units=metric` requested to get °C |
| Wind | `current.wind_speed`, **km/h** | `wind.speed`, **m/s** |
| Failure reporting | **`{"success": false, "error": {...}}` in the body; the HTTP status may be 200 *or* 4xx** | Conventional HTTP status codes |
| Unknown city | Signalled through an `error.code` in the `success: false` body | HTTP 404 with `{"cod": "404", "message": "city not found"}` |
| Transport | Free tier is HTTP-only | HTTPS |

Each adapter is responsible for translating its vendor's unknown-city signal into
`CityNotFoundException` and every other failure into `ProviderException`. That mapping is
the whole reason the distinction in §5 survives past the adapter boundary.

### The Weatherstack success flag

Weatherstack reports errors in the body, through `success: false` and an `error.code`. The
HTTP status is not a reliable signal in either direction: some failures come back as 200,
and an unresolvable city comes back as **400** while still carrying code 615 in the body.

Both halves of that matter. Trusting the status to mean success hands callers a malformed
payload while every health signal stays green. Trusting it to mean failure is what the first
implementation did — `retrieve()` raised on the 400 before the body was ever inspected, so
error 615 never reached the mapping and an unknown city surfaced as **503 instead of 404**,
counting against the circuit breaker on the way. That defect survived every WireMock test,
because the stubs modelled the documented 200 case; only a call against the live API exposed
it. The adapter now suppresses default status handling and parses the body either way.

`WeatherstackProvider` therefore inspects the `success` flag and throws `ProviderException`
when it is false. This is covered by a dedicated WireMock test (§9), because without it the
circuit breaker is decorative for Weatherstack's most common failure mode.

Free-tier limits and the HTTP-only constraint are to be confirmed against live provider
documentation during implementation, and the confirmed figures recorded in the README.

### Unit normalisation

Wind speed is unified to **km/h**, Weatherstack's native unit. OpenWeatherMap values are
multiplied by 3.6 inside `OpenWeatherMapProvider`. Keeping the primary path conversion-free
avoids rounding error on the common path, and the conversion stays contained in the adapter
that needs it. Temperature is unified to °C by requesting `units=metric` from
OpenWeatherMap.

## 7. Caching

```java
record CachedWeather(Weather weather, Instant fetchedAt) {}

Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofHours(24))  // hard TTL = stale retention window
    .maximumSize(1_000)                      // bound on distinct cities
    .recordStats()                           // exported to Actuator
```

One cache, one source of truth. Freshness is evaluated in code against an injected `Clock`:

- age <= 3s — serve as fresh.
- age > 3s — attempt refresh; on success store and serve, on total failure serve stale.
- age > 24h — treat as absent; a total outage lasting more than a day yields 503.

Because a `Clock` decides the time, TTL behaviour is tested by advancing it explicitly. No
test sleeps, so the suite finishes in milliseconds and does not flake on slow CI.

`maximumSize(1_000)` exists because arbitrary cities are supported: without a bound, the
`city` parameter is a memory-exhaustion vector.

Serving weather more than a day old as current is worse than admitting we do not know,
which is why the 24-hour ceiling exists rather than unbounded retention.

### Cache stampede protection

Check-then-act is not atomic. While the first thread spends ~200ms calling a provider, the
cache still holds the stale value, so every concurrent request also concludes it must
refresh. At 1,000 rps with a 200ms provider, one expiry produces roughly 200 identical
upstream calls — every 3 seconds, forever. That exhausts the free tier immediately and can
trigger provider rate limiting, which the circuit breaker would then read as an outage. The
service would cause its own failure.

Only one refresh per city is permitted:

```java
Lock lock = lockFor(city);

if (lock.tryLock()) {                    // winner refreshes
    try {
        Weather fresh = providerChain.fetch(city);
        cache.put(city, fresh);
        return fresh;
    } finally {
        lock.unlock();
    }
}

return staleEntry;                       // losers do not queue
```

`tryLock()` does not block. Losers are served the ~3.05s-old value immediately, so they are
in fact answered faster than the winner — p99 latency improves rather than degrades. The
cost is that staleness can reach 3s plus one provider round trip. For temperature and wind
speed, 200ms is physically meaningless.

#### The cold-cache case

The code above assumes a stale entry exists to fall back on. On a cold cache — the first
burst of traffic after start-up, or the first request for a city — a loser has nothing to
serve, and returning immediately would mean either failing a request the providers could
have answered, or making an unsynchronised provider call. The second choice would move the
stampede from the moment of expiry to the moment of start-up rather than eliminating it: at
1,000 rps, the first second after boot would still produce hundreds of identical calls.

So a loser with no fallback waits for the lock with a bounded timeout
(`weather.cache.cold-refresh-wait`, 9s — derived from the chain deadline in §8, not from a
single provider's timeout; an earlier draft used 3s, which meant waiting callers gave up
seconds before a healthy provider had answered).
On acquiring it, it re-checks the cache **before** calling any provider, because the thread
it was waiting on has almost certainly just filled it. If the wait times out, it re-checks
once more and serves whatever is there, failing only if there is still nothing.

The two paths differ because their alternatives differ: a warm loser has something better to
do than wait, and a cold loser does not.

#### When the caller you waited on failed

Waiting is only half the answer. If the holder fails to reach any provider, the cache is still
empty when a waiter acquires the lock — and the obvious next step, running the chain itself,
is wrong: the same chain failed milliseconds ago, and because each waiter takes the lock in
turn, every caller pays a full chain sequentially.

This was measured rather than reasoned about. Five concurrent requests against a cold cache
with every provider timing out finished **2.2s apart** — at 2.2s, 4.5s, 6.7s, 8.9s and 11.2s.
The last figure also exceeds `cold-refresh-wait`, because that bound governs *acquiring* the
lock, not the request as a whole: the real worst case is the wait plus a chain.

So a failed refresh is recorded per stripe, and a waiter that finds the cache empty gives up
instead of repeating it. Under the same conditions all five now finish together at 2.3s, at
the cost of one chain.

The marker records the **city**, not just the stripe. With 64 stripes shared by many cities, a
marker keyed on the stripe alone would make an untried city fail because an unrelated one had
just failed on the same lock — trading a latency bug for a correctness bug.

### Striped locks

A `Map<String, Lock>` keyed by city grows without bound, because `city` is caller-supplied;
a script sending invented city names exhausts the heap. Removing entries after unlock
introduces a race: a thread holding a reference to a removed lock and a thread creating a
replacement will hold different objects for the same city, and both will call the provider —
the protection leaks exactly when it is needed. Closing that hole requires manual reference
counting.

Instead, a fixed array of locks is used:

```java
private static final int STRIPES = 64;
private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

private ReentrantLock lockFor(String city) {
    return stripes[Math.floorMod(city.hashCode(), STRIPES)];
}
```

Memory is ~3 KB, fixed forever, regardless of how many distinct cities are requested.

`Math.floorMod` rather than `%`: `hashCode()` can be negative and `%` would yield a negative
index and an `ArrayIndexOutOfBoundsException` for some names only — a bug that survives
casual testing.

Two cities will sometimes share a stripe. The consequence is bounded: the second city's
refresh is deferred by one provider round trip and it is served stale in the meantime. The
lock guards *who may call a provider*, not the data — cache entries remain keyed by city, so
a stripe collision cannot return one city's weather for another.

This is the standard lock-striping pattern (Guava's `Striped<Lock>`; `ConcurrentHashMap`
used it internally before Java 8). Five lines are written directly rather than adding Guava
for this alone.

## 8. Resilience and configuration

### Circuit breakers

Because the Spring Boot starter is not used, the `resilience4j:` configuration namespace is
not available. Settings live under our own `weather.resilience` prefix and are turned into
`CircuitBreakerConfig` / `RetryConfig` objects by a `ResilienceConfig` `@Configuration`
class, one registry entry per provider.

```yaml
weather:
  resilience:
    sliding-window-size: 10               # how many recent outcomes are remembered
    minimum-number-of-calls: 10           # how many must be seen before judging at all
    failure-rate-threshold: 50            # percent
    wait-duration-in-open-state: 10s
    permitted-calls-in-half-open-state: 3
    retry-max-attempts: 2
    retry-wait-duration: 100ms
    chain-deadline: 8500ms
```

`ignoreExceptions` is where the §5 rules are enforced. `ProviderException` counts as a
circuit breaker failure; `CityNotFoundException` counts as neither failure nor success on
either the breaker or the retry.

Metrics are bound to Micrometer through `TaggedCircuitBreakerMetrics` and `TaggedRetryMetrics`
from `resilience4j-micrometer`, which need no Spring integration module — they take the
registry and a `MeterRegistry` directly.

HTTP timeouts per provider: connect 1s, read 1s.

Three rules that are easy to get wrong:

1. **Retry only transient failures** — timeouts, I/O errors, 5xx. A bad API key will not heal
   on retry; retrying merely doubles the latency paid before failover. Authentication
   failures therefore surface as their own type, `AuthenticationFailedException`
   (Weatherstack error code 101; HTTP 401/403 from OpenWeatherMap), which is excluded from
   the retry policy but still counts against the circuit breaker — a provider we cannot
   authenticate against is genuinely unusable.
2. **An open circuit skips the provider immediately**, with no timeout paid. This is what
   makes failover fast: once Weatherstack is failing, subsequent requests reach
   OpenWeatherMap in microseconds.
3. **The chain needs its own budget.** Per-provider timeouts bound one call, not the loop:
   two providers retrying twice each is `2 x (2 x 2s + 0.1s) = 8.2s`. `chain-deadline` caps
   the whole of `ProviderChain.fetch` at 8.5s, checked before starting each provider, so the
   figure stays bounded as providers are added. The cold-cache wait in §7 is derived from
   this number rather than guessed alongside it.

### Configuration

```yaml
weather:
  cache:
    fresh-ttl: 3s
    stale-retention: 24h
  providers:
    weatherstack:
      priority: 1
      base-url: http://api.weatherstack.com
      access-key: ${WEATHERSTACK_API_KEY:}
    openweathermap:
      priority: 2
      base-url: https://api.openweathermap.org
      api-key: ${OPENWEATHERMAP_API_KEY:}
```

Bound through validated `@ConfigurationProperties`. Credentials come from environment
variables only; a `.env.example` documents them. No key is ever committed.

When a provider's key is absent, that provider is disabled with a WARN log rather than
failing startup, so a reviewer holding only one API key can still run the service. If *no*
provider is enabled, startup fails deliberately — a weather service with no data source is a
failure waiting to happen at request time instead of at boot.

### Observability

Actuator exposes `/actuator/health` with a per-provider health indicator reporting circuit
state (CLOSED / OPEN / HALF_OPEN), and `/actuator/metrics` for cache hit rate and provider
call counts. Every failover and every stale response is logged at WARN.

### Packaging

A multi-stage `Dockerfile` is included. It makes the build-and-run instructions work on
machines without JDK 25, which removes the only practical risk of that choice.

That only holds if the image actually works, so CI builds it, starts it, and waits for
`/actuator/health` on every push. `docker build` alone would not be enough: it never runs the
`ENTRYPOINT`, so it cannot show that the application boots on the JRE-only second stage.

## 9. Testing strategy

No test sleeps and no test touches the real internet.

### Unit — no Spring context

| Test class | What it proves |
| --- | --- |
| `WeatherCacheTest` | age 2.9s serves from cache and the provider is not called; age 3.1s refreshes; age beyond 24h is treated as absent |
| `StaleServingTest` | all providers fail with an existing entry returns stale and marks it; all fail with no entry raises |
| `CacheStampedeTest` | 200 concurrent threads hitting a just-expired entry produce exactly 1 provider call (`AtomicInteger` + `CountDownLatch`) |
| `ProviderChainTest` | first throws so second is used; both throw yields `AllProvidersFailedException`; priority order is honoured |
| `CityNotFoundTest` | not-found falls through to the next provider; all-not-found yields 404; a not-found never counts as a circuit breaker failure and is never retried |
| `CityValidationTest` | trimming, case folding, length bounds, rejected character classes |
| `UnitConversionTest` | m/s to km/h, rounding, and boundary values (0, negative, very large) |

### Provider integration — WireMock

Each provider's `base-url` is pointed at a WireMock port via `@DynamicPropertySource`, so
production code runs unmodified with no test-only branches.

| Scenario | Expectation |
| --- | --- |
| Weatherstack returns a normal payload | parsed correctly, units correct |
| **Weatherstack returns `200 OK` with `{"success": false}`** | **throws `ProviderException`, failover triggers** |
| **Weatherstack returns `400` with `{"success": false, "error": {"code": 615}}`** | **throws `CityNotFoundException` — the live API's actual unknown-city response** |
| Weatherstack hangs past the read timeout | throws rather than hanging |
| Weatherstack returns malformed JSON | throws rather than silently returning null |
| OpenWeatherMap returns 401 / 403 | throws `AuthenticationFailedException`; not retried, but counts against the circuit |
| OpenWeatherMap returns 429 / 500 | throws `ProviderException` |
| OpenWeatherMap returns 404 `city not found` | throws `CityNotFoundException`, not `ProviderException` |
| Weatherstack reports an unresolvable location | throws `CityNotFoundException`; circuit stays CLOSED after repeated occurrences |

The second row is the most important test in the project.

### End-to-end integration

- `FailoverIntegrationTest` — Weatherstack always 500, OpenWeatherMap healthy: response is
  200 and sourced from OpenWeatherMap. Then assert the circuit opens and Weatherstack stops
  being contacted at all (`verify(exactly(n), ...)`).
- `StaleAfterTotalOutageTest` — prime the cache, fail both stubs, advance the clock: expect
  200 with `X-Weather-Stale: true`, not 503. This maps directly onto the brief's "MUST be
  served as stale".

`application-test.yml` shrinks the circuit breaker sliding window (e.g. to 4 calls) so the
circuit opens quickly and deterministically. The production window of 10 would make these
tests slow and brittle.

### Contract — MockMvc

```java
mockMvc.perform(get("/v1/weather?city=singapore"))
       .andExpect(status().isOk())
       .andExpect(content().json("""
           {"wind_speed": 20, "temperature_degrees": 29}
           """, JsonCompareMode.STRICT));
```

Plus: missing `city` defaults to singapore; blank `city` returns 400; total failure without
cache returns a 503 `ProblemDetail`.

### CI

`.github/workflows/ci.yml` runs `./mvnw verify` on every push. The submission checklist asks
for a public repository; a green badge is the cheapest possible evidence that the project
compiles and its tests pass.

## 10. README outline

1. What this is — one paragraph, plus the example `curl` and its response
2. Quick start — prerequisites, two environment variables, `./mvnw spring-boot:run`, Docker alternative
3. Obtaining API keys — signup links, Weatherstack free-tier HTTP-only note
4. Architecture — flow diagram and layer table
5. **Adding a new provider** — a concrete four-step recipe: implement `WeatherProvider`, set
   `priority`, add a configuration block, write one WireMock test. This is the evidence for
   "new developers can make changes safely" — an instruction to follow, not a claim
6. Resilience behaviour — 3-second cache, stale serving, circuit breaker, stampede protection
7. Configuration reference — properties, defaults, environment variables
8. Testing — how to run, what each layer covers
9. Observability — available Actuator endpoints
10. Trade-offs and next steps (§11)

## 11. Trade-offs and next steps

| Trade-off / omission | Reasoning and next step |
| --- | --- |
| In-memory per-instance cache | Sufficient for one node. Behind a load balancer, each node caches independently and provider calls multiply by node count. Next: Redis as a shared cache with the local cache retained as an L1 layer. |
| 24-hour stale ceiling | Beyond it, 503. Serving day-old weather as current is worse than admitting we do not know. |
| Staleness can reach 3s + provider latency | A deliberate consequence of non-blocking `tryLock`. Trades sub-second accuracy for materially better p99 latency. |
| Values rounded to integers | Makes the response identical across providers so callers cannot infer the source. Sub-degree precision is meaningless for weather. |
| Java 25 | Latest LTS with virtual threads. Reviewer-JDK risk is covered by the Dockerfile. |
| No auth or rate limiting on our endpoint | Outside the brief. Production would need an API key or an upstream gateway. |
| No load testing | Scalability claims rest on design, not measurement. Next: a k6 scenario demonstrating that 1,000 rps yields one provider call per 3 seconds. |
| No distributed tracing | Actuator metrics suffice for a single service. OpenTelemetry becomes worthwhile at service number two. |
| Hand-written striped locks | Five lines, versus pulling in Guava solely for `Striped<Lock>`. |

## 12. Known limitations

- **Weatherstack free-tier call limits and its HTTP-only constraint** were taken from the
  vendor's documentation, not measured against a live account. The base URL is `http://`
  for that reason.
- **The Weatherstack error codes** mapped to `CityNotFoundException` and
  `AuthenticationFailedException` are the documented ones. Any unrecognised code falls back
  to `ProviderException`, which fails safe toward failover rather than toward a 404.
- **The live path has now been exercised**, and it changed the design. Running against real
  keys showed that Weatherstack answers an unresolvable city with **HTTP 400** rather than the
  documented 200, which meant the error body was never parsed and an unknown city surfaced as
  503 instead of 404. Every WireMock test passed throughout, because the stubs modelled the
  documented behaviour. The adapter now reads the body regardless of status, and regression
  tests cover the 400 and 401 shapes the live API actually returns.
- **Weatherstack's free tier rate-limits quickly.** Sustained calls return error 106,
  which is correctly treated as a provider failure and fails over. A free key therefore sees
  the primary provider unavailable more often than a paid deployment would.
