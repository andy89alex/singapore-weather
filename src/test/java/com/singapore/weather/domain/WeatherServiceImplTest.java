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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        return service(result, Duration.ofSeconds(3));
    }

    private WeatherServiceImpl service(Weather result, Duration coldRefreshWait) {
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
        return new WeatherServiceImpl(cache,
                new ProviderChain(List.of(provider), breakers, retries, Duration.ofSeconds(30)),
                coldRefreshWait);
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

    @Test
    void servesStaleRatherThanQueueingWhenAnotherCallerHoldsTheRefreshLock() throws Exception {
        WeatherServiceImpl service = service(NEWER);
        service.get("singapore");                 // prime the cache
        clock.advance(Duration.ofSeconds(10));    // now stale

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> cache.tryRefresh("singapore", () -> {
            holderHasLock.countDown();
            awaitQuietly(releaseHolder);
            return "held";
        }));
        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        int callsBefore = providerCalls.get();
        WeatherResult result = service.get("singapore");

        releaseHolder.countDown();
        holder.join();

        assertThat(result.stale()).isTrue();
        assertThat(result.weather()).isEqualTo(NEWER);
        assertThat(providerCalls)
                .as("a loser with a fallback must not call the provider")
                .hasValue(callsBefore);
    }

    @Test
    void coldCacheLoserWaitsForTheWinnerInsteadOfCallingTheProviderItself() throws Exception {
        WeatherServiceImpl service = service(FRESH);

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> cache.tryRefresh("singapore", () -> {
            holderHasLock.countDown();
            awaitQuietly(releaseHolder);
            cache.put("singapore", FRESH);        // the winner fills the cache
            return "held";
        }));
        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<WeatherResult> loserResult = new AtomicReference<>();
        Thread loser = Thread.ofVirtual().start(() -> loserResult.set(service.get("singapore")));

        // Without this gate, the loser's virtual thread might not be scheduled
        // before we release the holder: the holder would finish, fill the cache,
        // and the loser would then hit the very first fresh-cache branch in
        // WeatherServiceImpl.get, short-circuiting before it ever calls
        // cache.tryRefresh and blocks on the lock. Both assertions below would
        // still pass in that degenerate run, but the test would no longer be
        // proving that a cold-cache loser actually waits under contention. Do
        // not delete this as ceremony — it is what makes the test meaningful.
        awaitState(loser, 5, TimeUnit.SECONDS, Thread.State.TIMED_WAITING, Thread.State.WAITING);

        releaseHolder.countDown();
        holder.join();
        loser.join();

        assertThat(loserResult.get().weather()).isEqualTo(FRESH);
        assertThat(providerCalls)
                .as("the waiting caller must reuse what the winner stored, not fetch again")
                .hasValue(0);
    }

    @Test
    void coldCacheLoserGivesUpWhenTheWaitExpires() throws Exception {
        // Short enough that the test runs fast, long enough that the holder
        // thread below is reliably still parked on releaseHolder when the
        // wait expires — the holder is only released after we have already
        // observed the timeout outcome, so there is no race here.
        Duration shortColdRefreshWait = Duration.ofMillis(100);
        WeatherServiceImpl service = service(FRESH, shortColdRefreshWait);

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> cache.tryRefresh("singapore", () -> {
            holderHasLock.countDown();
            awaitQuietly(releaseHolder);
            cache.put("singapore", FRESH);
            return "held";
        }));
        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.get("singapore"))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessageContaining("Timed out waiting for an in-flight refresh");

        releaseHolder.countDown();
        holder.join();

        assertThat(providerCalls)
                .as("a caller that gave up waiting must not have made its own provider call")
                .hasValue(0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Spins until {@code thread} reports one of {@code expectedStates}, up to
     * {@code timeout}. Used to confirm a thread has actually parked on a lock
     * (tryLock reports TIMED_WAITING/WAITING) before the test proceeds, instead
     * of assuming scheduling happened fast enough. Fails loudly rather than
     * hanging if the deadline passes.
     */
    private static void awaitState(Thread thread, long timeout, TimeUnit unit, Thread.State... expectedStates) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            for (Thread.State expected : expectedStates) {
                if (state == expected) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Thread " + thread.getName() + " did not reach one of "
                + List.of(expectedStates) + " within " + timeout + " " + unit
                + "; actual state: " + thread.getState());
    }
}
