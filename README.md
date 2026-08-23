# Singapore Weather Service

An HTTP service that reports current weather, sourcing from [Weatherstack](https://weatherstack.com/)
(primary) with automatic failover to [OpenWeatherMap](https://openweathermap.org/api). It
returns a provider-agnostic JSON payload, caches results for 3 seconds, and keeps serving
the last known value when every provider is down rather than erroring immediately.

```bash
curl "http://localhost:8080/v1/weather?city=singapore"
# {"wind_speed":20,"temperature_degrees":29}
```

`city` is optional and defaults to `singapore`. `wind_speed` is in km/h, `temperature_degrees`
is in degrees Celsius, and both are whole numbers regardless of which provider answered.

## Quick start

**Prerequisites:** JDK 25, or Docker if you'd rather not install a JDK. Either way you need
at least one provider API key (see [Obtaining API keys](#obtaining-api-keys) below) — the
service will disable a provider it has no key for and only refuses to start if it has none.

```bash
export WEATHERSTACK_API_KEY=...
export OPENWEATHERMAP_API_KEY=...
./mvnw spring-boot:run
```

Or with Docker:

```bash
docker build -t singapore-weather .
docker run -p 8080:8080 \
  -e WEATHERSTACK_API_KEY=... \
  -e OPENWEATHERMAP_API_KEY=... \
  singapore-weather
```

Then:

```bash
curl "http://localhost:8080/v1/weather?city=singapore"
```

### Keeping keys out of the repository

If you would rather not re-export the variables every session, copy the example file and fill
in your keys:

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` is git-ignored. Environment variables still win over it, so CI and
container deployments need no file at all.

**Do not put a real key in `application.yml`** — not even as a `${VAR:default}` fallback. That
default is a literal, and it would be committed. This repository is public, git history is
permanent, and providers commonly revoke keys that turn up in public scans.

## Obtaining API keys

- **Weatherstack** — sign up at <https://weatherstack.com/>. The free tier is **HTTP-only**
  (no HTTPS), which is why `weather.providers.weatherstack.base-url` is `http://` rather than
  `https://` — this is not an oversight.
- **OpenWeatherMap** — sign up at <https://openweathermap.org/api>. New keys can take up to a
  couple of hours to activate.

You don't need both. If only one key is set, the other provider logs a WARN at startup and
is excluded from the chain; the service still runs with a single provider and no failover.
If neither key is set, startup fails on purpose — a weather service with no data source
should fail at boot, not at the first request.

## Architecture

The reasoning behind these choices — the alternatives weighed and why each was rejected — is
in [`docs/DESIGN.md`](docs/DESIGN.md).

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
   1. WeatherstackProvider   [CircuitBreaker + Retry]
   2. OpenWeatherMapProvider [CircuitBreaker + Retry]
   (socket timeouts per provider; a chain-wide deadline bounds the whole loop)
        |
        +-- any success --> normalise units --> cache.put --> FRESH
        |
        +-- all failed --> stale entry exists? --yes--> return STALE (200)
                                              --no---> 503
```

| Package | Contents | Responsibility |
| --- | --- | --- |
| `api` | `WeatherController`, `WeatherResponse`, `GlobalExceptionHandler`, `CityValidator` | HTTP contract and JSON shape. The only layer that knows about HTTP. |
| `model` | `Weather`, `WeatherResult`, `CachedWeather` | Immutable value types shared across layers. |
| `exception` | `ProviderException`, `CityNotFoundException`, `AuthenticationFailedException`, `AllProvidersFailedException`, `InvalidCityException` | Failure vocabulary shared across layers. |
| `service` | `WeatherService`/`WeatherServiceImpl`, `ProviderChain`, `WeatherProvider` | Orchestration. Knows nothing about any specific vendor. |
| `cache` | `WeatherCache` | Soft-TTL / hard-TTL logic and stampede protection. |
| `provider.weatherstack` | `WeatherstackProvider` and its response DTOs | Vendor detail, fully isolated. |
| `provider.openweathermap` | `OpenWeatherMapProvider` and its response DTOs | Vendor detail, fully isolated. |
| `config` | `WeatherProperties`, `ProviderConfig`, `ResilienceConfig`, `RestClientConfig`, `CacheConfig` | Timeouts, base URLs, credentials, wiring of resilience components. |
| `health` | `ProviderHealthIndicator` | Surfaces each provider's circuit breaker state to Actuator. |

`WeatherServiceImpl` never learns which provider answered a request. Providers don't know a
cache exists. The controller doesn't know failover exists. Each layer is testable alone.

## Adding a new provider

Adding a provider touches one new package and three existing files: `application.yml`,
`WeatherProperties.Providers`, and `ProviderConfig`. Nothing else — not `ProviderChain`, not
`WeatherServiceImpl`, not the controller, not the cache.

1. Implement `WeatherProvider` in a new package `provider.<vendor>` — a `name()`, a
   `priority()` (lower runs first), and `fetch(String city)` that returns a `Weather` or
   throws `ProviderException` (or `CityNotFoundException` for an unresolvable city).
2. Give it a `priority` value that places it correctly relative to Weatherstack (1) and
   OpenWeatherMap (2).
3. Add a configuration block for it under `weather.providers` in `application.yml`
   (`base-url`, `api-key`, `connect-timeout`, `read-timeout`) and a matching nested record
   under `WeatherProperties.Providers`.
4. Register it in `ProviderConfig.weatherProviders(...)`, following the same
   "disabled with a WARN if unconfigured" pattern used for the existing two, and write one
   WireMock test for it modelled on `WeatherstackProviderTest` / `OpenWeatherMapProviderTest`
   — at minimum the happy path, a failure that should trigger failover, and the vendor's
   unknown-city signal mapped to `CityNotFoundException`.

`ProviderChain` sorts by `priority()` and iterates the injected `List<WeatherProvider>`
without knowing how many there are, so a third provider participates in failover for free.

## Resilience behaviour

- **3-second soft TTL, 24-hour hard TTL.** Entries younger than 3 seconds are served
  directly with no provider call. Older entries are refresh candidates but remain usable as
  stale data for up to 24 hours; beyond that the entry is treated as absent and a total
  outage yields 503. Serving day-old weather as if it were current would be worse than
  admitting the service doesn't know.
- **Stale serving.** When every provider fails but a cached value exists (age between 3s and
  24h), the response is still 200, with `X-Weather-Stale: true` and an `Age` header carrying
  the entry's age in seconds. The response body is unchanged — still exactly the two fields
  — so the staleness signal never has to be inferred from the payload shape.
- **Cache stampede protection.** A `city` parameter that's fully caller-controlled means an
  unbounded `Map<String, Lock>` is a memory-exhaustion vector, so locking is done with a
  fixed array of 64 striped `ReentrantLock`s (`Math.floorMod(city.hashCode(), 64)`) rather
  than one lock per city. On a cache expiry, one thread wins the lock via `tryLock()` and
  refreshes; losers with a stale value in hand are served it immediately rather than queuing
  — they're actually answered faster than the winner. A loser with nothing cached (a cold
  city, or the first moments after startup) waits up to `weather.cache.cold-refresh-wait`
  (9s) for the lock and re-checks the cache before ever calling a provider itself, so a cold
  start doesn't turn into one upstream call per concurrent request.
- **Where the 9 seconds comes from.** That wait has to cover the lock winner's real worst
  case, which is the whole chain rather than one call: per attempt `connect 1s + read 1s =
  2s`; per provider `2 attempts x 2s + one 100ms retry wait = 4.1s`; two providers = **8.2s**.
  `weather.resilience.chain-deadline` is set just above that at 8.5s and is enforced inside
  `ProviderChain` — before starting another provider it checks whether the budget is spent —
  so no request runs appreciably longer even if more providers are added. `cold-refresh-wait`
  is then 9s so a waiting caller never gives up *before* the winner could still succeed.
  Earlier these two numbers were picked independently, and the mismatch meant concurrent
  cold-start callers received a 503 several seconds before a healthy provider answered.
- **Circuit breakers per provider.** Each provider has its own Resilience4j
  `CircuitBreaker` (sliding window 10 calls, 50% failure threshold, 10s open-state wait, 3
  trial calls in half-open) plus a retry policy (2 attempts, 100ms wait) for transient
  failures. Once a circuit opens, that provider is skipped entirely — no timeout is paid —
  which is what keeps failover fast rather than merely eventual.
- **Failures tell clients what to do next.** A 503 carries `Retry-After`, set from the
  circuit breaker's open-state wait (10s) because that is genuinely when a provider is next
  attempted — without it clients back off on their own schedule and retry hardest while the
  providers are down, which is when this service can least afford the load. Every error
  response also carries `Cache-Control: no-store`, so an intermediary cannot keep serving a
  failure after the service has recovered. Successful responses are untouched.
- **404 does not open the circuit.** A well-formed city that no provider recognises
  (`CityNotFoundException`) is excluded from the circuit breaker's failure count and is never
  retried. Without that exclusion, a client hammering the endpoint with made-up city names
  could open the circuit on an otherwise-healthy provider — bad input turning into a
  self-inflicted outage.
- **Authentication failures are not retried either.** A bad or expired API key surfaces as
  `AuthenticationFailedException` (Weatherstack error code 101; HTTP 401/403 from
  OpenWeatherMap). Retrying cannot fix a wrong key — it only doubles the latency paid before
  failing over — so it is excluded from the retry policy. It *does* still count against the
  circuit breaker, because a provider we cannot authenticate against is genuinely unusable.

## Configuration reference

| Property | Default | Environment variable |
| --- | --- | --- |
| `weather.cache.fresh-ttl` | `3s` | — |
| `weather.cache.stale-retention` | `24h` | — |
| `weather.cache.max-size` | `1000` | — |
| `weather.cache.cold-refresh-wait` | `9s` | — |
| `weather.resilience.sliding-window-size` | `10` | — |
| `weather.resilience.failure-rate-threshold` | `50` | — |
| `weather.resilience.wait-duration-in-open-state` | `10s` | — |
| `weather.resilience.permitted-calls-in-half-open-state` | `3` | — |
| `weather.resilience.retry-max-attempts` | `2` | — |
| `weather.resilience.retry-wait-duration` | `100ms` | — |
| `weather.resilience.chain-deadline` | `8500ms` | — |
| `weather.providers.weatherstack.priority` | `1` | — |
| `weather.providers.weatherstack.base-url` | `http://api.weatherstack.com` | — |
| `weather.providers.weatherstack.api-key` | *(empty — provider disabled)* | `WEATHERSTACK_API_KEY` |
| `weather.providers.weatherstack.connect-timeout` | `1s` | — |
| `weather.providers.weatherstack.read-timeout` | `1s` | — |
| `weather.providers.openweathermap.priority` | `2` | — |
| `weather.providers.openweathermap.base-url` | `https://api.openweathermap.org` | — |
| `weather.providers.openweathermap.api-key` | *(empty — provider disabled)* | `OPENWEATHERMAP_API_KEY` |
| `weather.providers.openweathermap.connect-timeout` | `1s` | — |
| `weather.providers.openweathermap.read-timeout` | `1s` | — |

None of the numeric/timing properties are wired to an environment variable — override them
by editing `application.yml` or supplying a `-D`/`SPRING_APPLICATION_JSON` override if you
need to change them without a rebuild. Only the two API keys are designed to come from the
environment, since those are the only values that must never be committed.

## Testing

```bash
./mvnw verify
```

No test calls `Thread.sleep` and no test reaches the real internet — cache timing is tested
by advancing an injected `Clock`, and all provider HTTP calls are stubbed with WireMock.

- **Unit tests** (`cache`, `model`, `service`, `config`, `api` packages) — cache freshness and TTL
  boundaries at the millisecond level via `MutableClock`, stampede protection under 200
  concurrent threads producing exactly one provider call, provider-chain failover and
  priority ordering, city validation rules, and configuration property binding.
- **Provider integration tests** (`WeatherstackProviderTest`, `OpenWeatherMapProviderTest`) —
  each provider's `base-url` points at a WireMock instance via `@DynamicPropertySource`, so
  production code runs unmodified. Covers the normal payload, Weatherstack's body-based
  failure reporting on both `HTTP 200` and `HTTP 4xx` (the most important tests in the
  project — see below), timeouts,
  malformed JSON, and both providers' unknown-city signals.
- **End-to-end integration tests** (`FailoverIntegrationTest`, `StaleAfterTotalOutageTest`) —
  full Spring context with both providers stubbed. Confirms failover actually reaches
  OpenWeatherMap when Weatherstack is down and that the circuit then opens and stops
  Weatherstack being contacted at all, and confirms a primed cache is served stale (200 +
  `X-Weather-Stale: true`) rather than 503 when every provider fails.
- **Contract tests** (`WeatherControllerTest`) — MockMvc assertions in STRICT JSON compare
  mode, so a stray third field in the response would fail the build.

The suite is 78 tests and runs in a few seconds.

The service has also been run against the real Weatherstack and OpenWeatherMap APIs. That
run is what caught the Weatherstack status-code defect described above — every stubbed test
passed while an unknown city was returning 503 instead of 404.

## Observability

- `GET /actuator/health` — overall status plus a per-provider circuit breaker state
  (`CLOSED` / `OPEN` / `HALF_OPEN`) reported by `ProviderHealthIndicator`. If every
  configured provider's circuit is open, the indicator reports `DEGRADED` instead of `UP`.
- `GET /actuator/metrics` — includes cache hit/miss statistics from Caffeine
  (`recordStats()`) and per-provider circuit breaker / retry metrics exported through
  `resilience4j-micrometer`.

## Trade-offs and what I'd do differently

| Trade-off / omission | Reasoning and next step |
| --- | --- |
| In-memory per-instance cache | Fine for one node. Behind a load balancer, each node caches independently and provider calls multiply by node count. Next: Redis as a shared cache with the local cache kept as an L1 layer. |
| 24-hour stale ceiling | Beyond it, 503. Day-old weather presented as current is worse than admitting ignorance. |
| A cold-cache caller can wait up to 9s | Only when the cache holds nothing for that city *and* another request is already refreshing it — in practice the first moments after startup, or the first request for a new city. The alternative was letting each caller make its own provider call, which reintroduces the stampede at start-up. Bounding the chain (see Resilience behaviour) is what keeps this figure from being ~12s. With more time I would refresh in the background so the waiting caller is never the slowest one. |
| Staleness can reach 3s plus one provider round trip | A deliberate consequence of the non-blocking `tryLock`; trades sub-second accuracy for materially better p99 latency. |
| Values rounded to whole numbers | Makes the response identical across providers so callers cannot infer the source. Sub-degree precision is meaningless for weather. |
| Wind unified to km/h | Weatherstack's native unit, so the primary path needs no conversion; OpenWeatherMap's m/s is converted in its adapter. |
| Java 25 | Latest LTS, virtual threads enabled. The Dockerfile removes the reviewer-JDK risk. |
| Resilience4j wired by hand | `resilience4j-spring-boot3` still targets Spring Framework 6 while Boot 4.1.1 ships Spring Framework 7, so the annotation-driven starter was rejected. The core modules have no Spring coupling. |
| Weatherstack free tier is HTTP-only | Which is why its base URL is `http://`. |
| Hand-written striped locks | Five lines, versus adding Guava solely for `Striped<Lock>`. |
| No auth or rate limiting on our own endpoint | Outside the brief. Production would need an API key or an upstream gateway. |
| No load testing | Scalability claims rest on design, not measurement. Next: a k6 scenario demonstrating 1,000 rps yields one provider call per 3 seconds. |
| No distributed tracing | Actuator metrics suffice for a single service; OpenTelemetry becomes worthwhile at service number two. |
| Weatherstack's free tier rate-limits quickly | Verified against the live API: sustained calls return error 106 (`rate_limit_reached`), which is treated as a provider failure and fails over to OpenWeatherMap. On a free key that means the primary provider is unavailable more often than a paid deployment would see. |
| Docker image build unverified in this environment | Docker is not installed in the environment this was built in, so `docker build` could not actually be run here. The base image tags (`maven:3.9-eclipse-temurin-25`, `eclipse-temurin:25-jre`) were confirmed to exist via the Docker Hub API, and the Dockerfile mirrors the multi-stage layout used successfully during earlier spikes, but the build itself has not been executed end to end. |
| `X-Weather-Stale` staleness signal lives in a header, not the body | The brief's example payload has exactly two fields and is the kind of thing that gets compared verbatim; a header carries the same signal without risking a mismatch. Adding it to the body later is a one-line change if a caller ever needs it there instead. |
