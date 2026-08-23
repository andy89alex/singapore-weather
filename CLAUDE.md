# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An HTTP service reporting weather for a city (default `singapore`), sourced from Weatherstack
with automatic failover to OpenWeatherMap. Built as a job-application take-home, so the
literal response contract and the honesty of `README.md` / `docs/DESIGN.md` matter as much as
the code.

`docs/DESIGN.md` records why the trade-offs were chosen. Read it before changing resilience,
caching, or the response shape — most non-obvious decisions are argued there.

## Commands

```bash
./mvnw clean verify                 # full build + all tests (81), produces the jar
./mvnw test                         # tests only
./mvnw spring-boot:run              # run locally on :8080

./mvnw test -Dtest=WeatherCacheTest                              # one class
./mvnw test -Dtest='WeatherCacheTest#missesWhenNothingWasStored' # one method
./mvnw test -Dtest='ProviderConfigTest$OnlyFailoverKeyPresent'   # one nested class
./mvnw test -Dtest='WeatherCache*'                               # pattern
```

Both API keys come from the environment and a provider without one is silently disabled:

```bash
export WEATHERSTACK_API_KEY=...
export OPENWEATHERMAP_API_KEY=...
```

`pom.xml` overrides Surefire's `<includes>`/`<excludes>` because its defaults drop every class
whose name contains `$`, which silently skipped the nested `@SpringBootTest` scenarios in
`ProviderConfigTest`. Don't remove that configuration.

## Architecture

Request flow:

```
WeatherController → CityValidator.normalise → WeatherServiceImpl
                                                    ↓
                                        WeatherCache (fresh? serve)
                                                    ↓ stale/miss
                                        ProviderChain (priority order)
                                          1. WeatherstackProvider
                                          2. OpenWeatherMapProvider
```

Packages: `api` (the only layer that knows HTTP), `service` (orchestration + the
`WeatherProvider` contract), `model`, `exception`, `cache`, `provider.<vendor>`, `config`,
`health`.

### Things that are load-bearing and easy to break

**The response body is exactly two fields, in order: `wind_speed`, then
`temperature_degrees`.** Whole numbers, km/h and Celsius. `WeatherControllerTest` asserts this
in `JsonCompareMode.STRICT`, so a third field fails the build — that is deliberate. Staleness
is signalled with the `X-Weather-Stale` and `Age` headers, never in the body.

**Rounding happens only in `WeatherResponse.from`.** The domain keeps unrounded doubles so the
response looks identical whichever provider answered.

**Exception type decides HTTP status and resilience accounting**, so pick carefully:

| Exception | Status | Circuit breaker | Retried |
| --- | --- | --- | --- |
| `InvalidCityException` | 400 | n/a — thrown before any provider call | n/a |
| `CityNotFoundException` | 404 | ignored | no |
| `AuthenticationFailedException` (extends `ProviderException`) | 503 | counts as failure | no |
| `ProviderException` | 503 | counts as failure | yes |
| `AllProvidersFailedException` | 503 | n/a | n/a |

Every error response carries `Cache-Control: no-store`, and 503 additionally carries
`Retry-After` sourced from `weather.resilience.wait-duration-in-open-state`. Success responses
carry neither — don't let them leak onto the 200 path.

Only `InvalidCityException`, `CityNotFoundException` and `AllProvidersFailedException` have
handlers in `GlobalExceptionHandler`. The provider-level exceptions never reach the API layer:
`ProviderChain` catches them, tries the next provider, and raises `AllProvidersFailedException`
if none succeeds — which is why they map to 503 indirectly.

`CityNotFoundException` is ignored by the breaker so caller-supplied garbage cannot open a
circuit on a healthy provider, but it still falls through to the next provider — vendor
gazetteers differ, and only an all-not-found result yields 404.

**Weatherstack's HTTP status is not a failure signal.** It reports errors in the body via
`"success": false` and `error.code`, and the status may be 200 *or* 4xx (an unresolvable city
returns 400). `WeatherstackProvider` suppresses default status handling with
`.onStatus(HttpStatusCode::isError, (req, res) -> { })` so the body is parsed either way. This
was a live-only defect: every stubbed test passed while unknown cities returned 503.

**Unit conversion lives in the adapter.** Weatherstack already returns km/h; OpenWeatherMap
returns m/s and converts via `Weather.ofMetresPerSecond`. Nothing outside
`provider.openweathermap` should ever see m/s.

### Caching and concurrency

One Caffeine cache with two horizons: a 3s soft TTL (freshness checked in code) over a 24h
hard TTL (`expireAfterWrite`). Older-than-3s entries stay available as stale data, which is
how the service keeps answering when every provider is down.

Refreshes are arbitrated by 64 striped `ReentrantLock`s (`Math.floorMod(city.hashCode(), 64)`
— `%` would give a negative index for some names). A loser holding a stale value is served it
immediately; a loser with nothing cached waits up to `cold-refresh-wait` and re-checks the
cache under the lock before calling a provider. Bypassing that wait reintroduces the stampede
at start-up.

If the holder failed, waiters must **not** each repeat the chain — that serialises one full
chain per waiter (measured: five concurrent callers finishing 2.2s apart, the last at 11.2s).
`WeatherCache` records the last failed refresh per stripe **with the city name**, and
`refreshUnlessAlreadyFilled` short-circuits on it. The city matters: 64 stripes are shared by
many cities, and keying on the stripe alone would fail a city nobody ever tried.

Timeouts are a derived budget, not independent numbers: per attempt 2s, per provider 4.1s,
whole chain 8.2s, so `chain-deadline` is 8.5s and `cold-refresh-wait` 9s. The arithmetic is
commented in `application.yml`. **If you change `read-timeout` or `retry-max-attempts`, redo
that arithmetic and update all three values together.**

### Wiring conventions

- **Constructor injection only.** Several classes take plain values (`Duration`, API key
  `String`, `int priority`) that cannot be autowired, and unit tests construct their subjects
  directly. Do not introduce `@Autowired`/`@Resource` field injection.
- `WeatherServiceImpl` deliberately carries **no** stereotype annotation — it is registered as
  an explicit `@Bean` in `CacheConfig` because of that `Duration` argument.
- Service layer follows the `Interface` + `Impl` convention; other components are concrete
  classes.
- Resilience4j is wired **programmatically** in `ResilienceConfig`. Do not add
  `resilience4j-spring-boot3` or `@CircuitBreaker`/`@Retry` annotations — that starter targets
  Spring Framework 6 while Boot 4.1.1 ships Spring Framework 7.
- `sliding-window-size` and `minimum-number-of-calls` are independent, and both come from
  configuration. Resilience4j defaults both to 100; for a COUNT_BASED window it caps the
  minimum at the window size, but a TIME_BASED window applies it literally — so never leave it
  to the default.
- Lombok is present for `@Slf4j` only. Value types are records; `@Data` would add setters to
  objects held in the cache.
- Spring Boot 4.1.1 ships Jackson 3 (`tools.jackson.databind`) but annotations remain
  `com.fasterxml.jackson.annotation`. `@WebMvcTest`/`@AutoConfigureMockMvc` live in
  `org.springframework.boot.webmvc.test.autoconfigure`, and `@MockBean` is removed — use
  `@MockitoBean`.

## Testing rules

- **No test may call `Thread.sleep`** and none may reach the real internet. Cache timing runs
  on an injected `Clock` (`MutableClock` in tests); all provider HTTP is stubbed with WireMock.
- Concurrency tests gate on a thread actually reaching `TIMED_WAITING`/`WAITING` via
  `awaitState` before releasing the lock holder. Without that gate they pass without ever
  contending. Don't delete it as ceremony.
- Provider stubs assert query parameter names and values, not just the path — that is the only
  guard against a renamed `access_key`/`appid`/`units` slipping through.
- `application-test.yml` shrinks the circuit breaker window to 4 and retries to 1 so the
  integration tests are deterministic, and sets `fresh-ttl: -1s` (not `0s`, since `isFresh`
  tests `age <= freshTtl`). `fresh-ttl` is intentionally left out of the Bean Validation
  constraints for that reason.

## Keeping docs honest

`README.md` and `docs/DESIGN.md` state specific numbers, status codes and property defaults.
When behaviour changes, update them in the same commit — a document that overstates what the
code does is treated here as worse than a terse one.
