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
    void aWaitingCallerAcquiresTheLockOnceTheWinnerFinishes() throws Exception {
        CountDownLatch winnerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicInteger waiterRefreshes = new AtomicInteger();

        Thread winner = Thread.ofVirtual().start(() -> cache.tryRefresh("singapore", () -> {
            winnerHoldsLock.countDown();
            await(releaseWinner);
            return new Weather(29, 20);
        }));

        assertThat(winnerHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

        Thread waiter = Thread.ofVirtual().start(() ->
                cache.tryRefresh("singapore", Duration.ofSeconds(5), () -> {
                    waiterRefreshes.incrementAndGet();
                    return new Weather(30, 21);
                }));

        releaseWinner.countDown();
        winner.join();
        waiter.join();

        assertThat(waiterRefreshes)
                .as("a bounded wait must acquire the lock rather than give up")
                .hasValue(1);
    }

    @Test
    void aWaitingCallerGivesUpWhenTheWinnerHoldsTheLockTooLong() throws Exception {
        CountDownLatch winnerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        Thread winner = Thread.ofVirtual().start(() -> cache.tryRefresh("singapore", () -> {
            winnerHoldsLock.countDown();
            await(releaseWinner);
            return new Weather(29, 20);
        }));

        assertThat(winnerHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

        Optional<Weather> result =
                cache.tryRefresh("singapore", Duration.ofMillis(50), () -> new Weather(30, 21));

        releaseWinner.countDown();
        winner.join();

        assertThat(result).isEmpty();
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
