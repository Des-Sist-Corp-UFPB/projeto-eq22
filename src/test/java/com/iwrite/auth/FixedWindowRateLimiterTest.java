package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of {@link FixedWindowRateLimiter} itself (previously only exercised indirectly
 * through {@link LoginRateLimiter}/{@link RegistrationRateLimiter}), added for the race between
 * {@code admitNewKey}'s cleanup and a concurrent {@code reserve} renewing the exact window that
 * cleanup is inspecting. The cleanup used to read a window's {@code startMillis} without that
 * window's own monitor, so it could evict a window a concurrent caller had just legitimately
 * renewed (or hold a since-evicted reference and silently drop a reservation into it). Both are now
 * impossible by construction — cleanup and renewal are mutually exclusive on the same window's
 * monitor, and {@code reserve} detects and retries against an orphaned reference — but the race
 * tests below still run the two paths concurrently, many times, to prove the outcome is correct
 * regardless of which thread the scheduler lets win.
 */
class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void reservaEhRecusadaAposOLimiteEDevolvidaComRefund() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 10, Duration.ofMinutes(1), clock);

        FixedWindowRateLimiter.Reservation reservation = limiter.reserve("k");
        assertThat(reservation).isNotNull();
        assertThat(limiter.reserve("k")).isNull();

        reservation.refund();
        reservation.refund(); // idempotent: must not credit the budget twice

        assertThat(limiter.reserve("k")).isNotNull(); // exactly one unit was returned
        assertThat(limiter.reserve("k")).isNull();
    }

    @Test
    void refundDeUmaReservaAntigaNaoAfetaAJanelaRenovada() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 10, Duration.ofMinutes(1), clock);

        FixedWindowRateLimiter.Reservation stale = limiter.reserve("k");
        assertThat(stale).isNotNull();

        clock.advance(Duration.ofSeconds(61)); // the window expires
        assertThat(limiter.reserve("k")).isNotNull(); // renews into a fresh window, 1/1

        stale.refund(); // belongs to the old, since-renewed generation — must be a no-op

        assertThat(limiter.reserve("k")).isNull(); // the new window's own unit is still spent
    }

    @Test
    void limiteContinuaSendoRespeitadoAposORolloverDaJanela() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 10, Duration.ofMinutes(1), clock);

        assertThat(limiter.reserve("k")).isNotNull();
        assertThat(limiter.reserve("k")).isNotNull();
        assertThat(limiter.reserve("k")).isNull(); // budget spent in the first window

        clock.advance(Duration.ofSeconds(61)); // rollover

        assertThat(limiter.reserve("k")).isNotNull(); // 1/2 in the new window
        assertThat(limiter.reserve("k")).isNotNull(); // 2/2
        assertThat(limiter.reserve("k")).isNull(); // spent again — no leftover budget carried over
    }

    @Test
    void capacidadeMaxTrackedKeysContinuaFailClosed() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1000, 1, Duration.ofMinutes(1), clock);

        assertThat(limiter.reserve("only")).isNotNull();
        // The map is full of one live (unexpired) window: a brand-new key is refused rather than
        // evicting it.
        assertThat(limiter.reserve("newcomer")).isNull();
        // The existing key is entirely unaffected by the refused admission attempt.
        assertThat(limiter.reserve("only")).isNotNull();
    }

    /**
     * Item 1 of the fix: a window that a concurrent {@code reserve} renews must never be evicted by
     * a cleanup pass racing it, and the renewal's own unit must never be silently lost in the
     * process. Two keys share a two-slot map, both looking expired to a cleanup pass; one
     * ("renewed") is concurrently reserved again (a real renewal) while the other ("victim", never
     * touched again) is left for cleanup to reclaim unambiguously, so admitting the newcomer never
     * strictly requires evicting "renewed" — isolating the assertion to what the race actually did
     * to "renewed" specifically, on either side of the race.
     *
     * <p>Run many times: the exact interleaving is left to the scheduler, but the assertions must
     * hold under every one of them if the fix is correct — this reproduced the bug (a lost or
     * doubled unit) reliably within a few dozen iterations against the old, unsynchronized read.
     */
    @Test
    void limpezaConcorrenteNuncaPerdeOuDuplicaARenovacaoDeUmaJanelaAindaEmUso() throws Exception {
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                int iteration = i;
                MutableClock raceClock = new MutableClock();
                FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 2, Duration.ofMinutes(1), raceClock);
                assertThat(limiter.reserve("renewed")).isNotNull(); // 1/2
                assertThat(limiter.reserve("victim")).isNotNull();
                raceClock.advance(Duration.ofSeconds(61)); // both windows now look expired

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);

                Future<FixedWindowRateLimiter.Reservation> renewal = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return limiter.reserve("renewed"); // real renewal: resets to 1/2 in a fresh window
                });
                Future<FixedWindowRateLimiter.Reservation> admission = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return limiter.reserve("newcomer-" + iteration); // forces a cleanup pass
                });

                assertThat(ready.await(10, TimeUnit.SECONDS)).as("iteration %d", iteration).isTrue();
                go.countDown();
                FixedWindowRateLimiter.Reservation renewalResult = renewal.get(10, TimeUnit.SECONDS);
                FixedWindowRateLimiter.Reservation admissionResult = admission.get(10, TimeUnit.SECONDS);

                assertThat(renewalResult).as("iteration %d: renewal must never be silently lost", iteration).isNotNull();
                assertThat(admissionResult).as("iteration %d: victim's slot must free up for the newcomer", iteration).isNotNull();

                // The proof that the race neither lost nor doubled the renewal's unit: exactly one
                // more reservation is available in the fresh window (bringing it to 2/2), and the one
                // after that is refused. Under the old bug, a lost renewal would leave 2 units free
                // here instead of 1 (the race's own reservation silently vanished into an orphaned,
                // unreachable window) and a doubled one would leave the second call refused already.
                assertThat(limiter.reserve("renewed")).as("iteration %d: second unit still available", iteration).isNotNull();
                assertThat(limiter.reserve("renewed")).as("iteration %d: budget now exhausted, not short", iteration).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 2 of the fix: a thread that already holds a reference to a window a concurrent cleanup
     * is in the middle of evicting must detect the mismatch once it acquires the window's monitor,
     * and retry through fresh admission rather than reserving into an object nobody will ever read
     * back through the map again. Three keys, two slots: "k" is the one under race (renewed by one
     * thread while a cleanup pass may evict it), "decoy" is never touched again so it is always
     * unambiguously reclaimable — that guarantees a slot is available for "newcomer" without ever
     * having to evict "k" for capacity, so if "k" ever comes back refused it can only be the bug
     * (a lost retry), never legitimate capacity contention with "newcomer".
     */
    @Test
    void requisicaoComReferenciaOrfaTentaNovamenteEObtemUmaJanelaViva() throws Exception {
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                int iteration = i;
                MutableClock raceClock = new MutableClock();
                FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1000, 2, Duration.ofMinutes(1), raceClock);
                assertThat(limiter.reserve("k")).isNotNull();
                assertThat(limiter.reserve("decoy")).isNotNull();
                raceClock.advance(Duration.ofSeconds(61)); // both windows now look expired

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);

                Future<FixedWindowRateLimiter.Reservation> renewal = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return limiter.reserve("k"); // may race a cleanup evicting this exact window
                });
                Future<FixedWindowRateLimiter.Reservation> admission = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    // At capacity 2 with both "k" and "decoy" looking expired, this forces a cleanup
                    // pass — "decoy" alone always frees enough room, so "k" is never legitimately
                    // capacity-blocked by this admission.
                    return limiter.reserve("newcomer-" + iteration);
                });

                assertThat(ready.await(10, TimeUnit.SECONDS)).as("iteration %d", iteration).isTrue();
                go.countDown();
                FixedWindowRateLimiter.Reservation renewalResult = renewal.get(10, TimeUnit.SECONDS);
                FixedWindowRateLimiter.Reservation admissionResult = admission.get(10, TimeUnit.SECONDS);

                assertThat(admissionResult).as("iteration %d: decoy's slot must free up for the newcomer", iteration).isNotNull();
                // "decoy" alone always supplies the room "newcomer" needs, so "k" coming back null
                // here could only mean its retry-after-orphan path silently gave up.
                assertThat(renewalResult).as("iteration %d: retry after an orphaned reference must still succeed", iteration).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-08-02T12:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
