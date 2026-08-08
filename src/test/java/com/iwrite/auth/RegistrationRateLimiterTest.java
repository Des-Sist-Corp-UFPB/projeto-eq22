package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void permiteAteOLimitePorOrigemEDepoisRejeita() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(2, 10_000, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.2.3.4");
        limiter.checkOrigin("1.2.3.4");

        assertThatThrownBy(() -> limiter.checkOrigin("1.2.3.4"))
                .isInstanceOf(RegistrationRateLimitExceededException.class);
    }

    @Test
    void origensDistintasTemOrcamentosIndependentes() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(1, 10_000, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.1.1.1");
        assertThatThrownBy(() -> limiter.checkOrigin("1.1.1.1"))
                .isInstanceOf(RegistrationRateLimitExceededException.class);

        assertThatCode(() -> limiter.checkOrigin("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void janelaExpiradaLiberaTentativasParaAMesmaOrigem() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(1, 10_000, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.2.3.4");
        assertThatThrownBy(() -> limiter.checkOrigin("1.2.3.4"))
                .isInstanceOf(RegistrationRateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.checkOrigin("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void mapaCheioMantemOrigemExistenteERecusaOrigemNova() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(2, 1, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.1.1.1");
        limiter.checkOrigin("1.1.1.1");

        assertThatThrownBy(() -> limiter.checkOrigin("1.1.1.1"))
                .isInstanceOf(RegistrationRateLimitExceededException.class);

        // A brand-new origin, with the map at max-tracked-keys=1 capacity, fails closed instead of
        // evicting "1.1.1.1"'s live window.
        assertThatThrownBy(() -> limiter.checkOrigin("2.2.2.2"))
                .isInstanceOf(RegistrationRateLimitExceededException.class);
    }

    @Test
    void admissoesConcorrentesNuncaUltrapassamOLimiteDaCapacidade() throws Exception {
        int capacity = 5;
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(1000, capacity, Duration.ofMinutes(1), clock);

        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        limiter.checkOrigin("origin-" + index);
                        admitted.incrementAndGet();
                    } catch (RegistrationRateLimitExceededException expected) {
                        // Fail-closed once capacity is spent.
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(admitted.get()).isLessThanOrEqualTo(capacity);
    }

    @Test
    void configuracaoInvalidaFalhaNaConstrucao() {
        assertThatThrownBy(() -> new RegistrationRateLimiter(0, 10, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrationRateLimiter(1, 0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrationRateLimiter(1, 10, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
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
