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

class LoginRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    /**
     * One full failed login, in the order AuthController drives the limiter for a real request:
     * the reservation is taken before the (simulated) authenticate() call and never refunded,
     * exactly as a real {@link org.springframework.security.core.AuthenticationException} leaves it.
     */
    private static void attemptAndFail(LoginRateLimiter limiter, String origin, String email) {
        limiter.checkOrigin(origin);
        limiter.reserveAccountAttempt(email);
    }

    /** One full successful login: the reservation is taken, then refunded right away. */
    private static void attemptAndSucceed(LoginRateLimiter limiter, String origin, String email) {
        limiter.checkOrigin(origin);
        limiter.reserveAccountAttempt(email).refund();
    }

    @Test
    void permiteAteOLimitePorContaEDepoisRejeita() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        assertThatCode(() -> attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local")).doesNotThrowAnyException();
        assertThatCode(() -> attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local")).doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void distribuirTentativasEntreOrigensNaoEscapaOLimitePorConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.1.1.1", "victim@iwrite.local");
        attemptAndFail(limiter, "2.2.2.2", "victim@iwrite.local");

        // A third origin, same targeted account: the account budget is what is spent, not the origin's.
        limiter.checkOrigin("3.3.3.3");
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void concentrarContasDistintasNaMesmaOrigemNaoEscapaOLimitePorOrigem() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 100, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "9.9.9.9", "a@iwrite.local");
        attemptAndFail(limiter, "9.9.9.9", "b@iwrite.local");

        assertThatThrownBy(() -> limiter.checkOrigin("9.9.9.9"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void contaENormalizadaPorEmailSemDiferenciarCaixaOuEspacos() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", " Victim@IWrite.local ");

        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void janelaExpiradaLiberaTentativasParaAMesmaConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(61));

        // The account is never held shut past its own window, regardless of how many attempts hit it.
        assertThatCode(() -> attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void requisicaoSemEmailAindaContaNoOrcamentoDaOrigem() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 10_000, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.2.3.4");

        assertThatThrownBy(() -> limiter.checkOrigin("1.2.3.4"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void contaEsgotadaMaisAntigaContinuaRecusadaComOMapaCheio() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 1, 2, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4"; // Reused throughout: keeps the origin dimension out of this test.

        // Victim is the oldest tracked account and has already spent its one-attempt budget.
        attemptAndFail(limiter, origin, "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // A second, distinct account fills the account dimension to its max-tracked-keys capacity.
        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.reserveAccountAttempt("second@iwrite.local")).doesNotThrowAnyException();

        // The map is now full of two live windows. Querying the victim again must still be a 429,
        // from its own preserved window — never a freshly created, empty one that would readmit it.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void inundarComContasDescartaveisNaoLibertaAJanelaDaContaVitima() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 1, 2, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        attemptAndFail(limiter, origin, "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // A distributed attack floods the account dimension with many disposable emails, well past
        // capacity, trying to force an eviction of the victim's window to make room for itself.
        for (int i = 0; i < 50; i++) {
            limiter.checkOrigin(origin);
            try {
                limiter.reserveAccountAttempt("disposable-" + i + "@iwrite.local");
            } catch (LoginRateLimitExceededException expected) {
                // Fail-closed once capacity is spent — exactly the protection under test.
            }
        }

        // The victim's window survived every one of those admission attempts.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void chaveNovaComTodasAsEntradasAtivasRecebe429ESemCrescerAlemDoLimite() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 100, 2, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.reserveAccountAttempt("a@iwrite.local")).doesNotThrowAnyException();
        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.reserveAccountAttempt("b@iwrite.local")).doesNotThrowAnyException();

        // The map is at max-tracked-keys capacity (2), both entries active: a third, unseen key
        // fails closed rather than growing the map or evicting either existing entry.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("c@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // Neither existing entry was evicted to make room for the attempt above.
        assertThatCode(() -> limiter.reserveAccountAttempt("a@iwrite.local")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.reserveAccountAttempt("b@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void chaveNovaEhAdmitidaAposUmaEntradaExpirar() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 100, 1, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.reserveAccountAttempt("a@iwrite.local")).doesNotThrowAnyException();

        // At capacity (max-tracked-keys=1): a second, distinct account is refused while "a"'s window
        // is still active.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("b@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(61)); // "a"'s window expires; it is never revisited.

        // Admitting "b" reclaims the now-expired slot instead of refusing.
        assertThatCode(() -> limiter.reserveAccountAttempt("b@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void chaveExistenteAbaixoDoLimiteContinuaUtilizavelComOMapaCheio() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 3, 1, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        // One failure recorded (1/3), budget is 3: still has room, and the map (max-tracked-keys=1)
        // is already completely full with only this account tracked.
        attemptAndFail(limiter, origin, "a@iwrite.local");
        limiter.checkOrigin(origin);
        // A successful login in between: reserved then refunded right away, so it never accumulates
        // against the budget — the count is still 1/3 afterwards, not 2/3.
        assertThatCode(() -> limiter.reserveAccountAttempt("a@iwrite.local").refund())
                .doesNotThrowAnyException();

        // Re-checking and re-spending the same, already-tracked key is never treated as a "new key"
        // admission — its own count keeps accumulating (now 2/3) rather than being reset or evicted.
        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.reserveAccountAttempt("a@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void mapaDeOrigemCheioMantemOrigemExistenteERecusaOrigemNova() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 1000, 1, Duration.ofMinutes(1), clock);

        limiter.checkOrigin("1.1.1.1"); // 1/2
        limiter.checkOrigin("1.1.1.1"); // 2/2 — exhausts this origin's own budget.

        // The already-tracked origin keeps its own counter: refused for exceeding ITS budget, not
        // for map capacity — proving it was not reset just because the map is full.
        assertThatThrownBy(() -> limiter.checkOrigin("1.1.1.1"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // A brand-new origin, with the origin map at max-tracked-keys=1 capacity, fails closed
        // instead of evicting "1.1.1.1"'s live window.
        assertThatThrownBy(() -> limiter.checkOrigin("2.2.2.2"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void admissoesConcorrentesNuncaUltrapassamOLimiteNemExpulsamJanelaProtegida() throws Exception {
        int capacity = 5;
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 1, capacity, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        // A protected, already-exhausted victim — the one entry that must survive every concurrent
        // admission attempt below, no matter how the race between threads resolves.
        attemptAndFail(limiter, origin, "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        int threadCount = 50;
        // One thread per task, not a smaller pool: every task's first line counts down `ready` and
        // then immediately blocks on `go`, so a pool smaller than threadCount would deadlock — the
        // queued tasks can never get a thread to run their own countDown, `ready` never reaches
        // threadCount, and the main thread's ready.await() below blocks forever.
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
                        limiter.reserveAccountAttempt("attacker-" + index + "@iwrite.local");
                        admitted.incrementAndGet();
                    } catch (LoginRateLimitExceededException expected) {
                        // Fail-closed once capacity is spent — the expected outcome for most threads.
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("every thread should have reached the rendezvous point")
                    .isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        // capacity - 1: one slot was already spent by the victim before the race began. However the
        // race resolved, the map can never have admitted more new keys than the room actually left.
        assertThat(admitted.get()).isLessThanOrEqualTo(capacity - 1);

        // The victim's own window is exactly as it was — still refused, never silently recreated.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    /**
     * The concurrency guarantee the reservation contract exists for: N simultaneous requests against
     * the *same* account, from different origins, with the wrong password. At most
     * maxAttemptsPerAccount of them may ever reach the point that would call
     * {@code AuthenticationManager.authenticate} (simulated here by a successful
     * {@link LoginRateLimiter#reserveAccountAttempt}); every other request must be refused before
     * that point, exactly as {@link com.iwrite.auth.AuthController} refuses it before bcrypt runs.
     */
    @Test
    void reservaConcorrenteNuncaDeixaMaisQueOLimiteChegarAAutenticar() throws Exception {
        int maxAttemptsPerAccount = 4;
        LoginRateLimiter limiter = new LoginRateLimiter(1000, maxAttemptsPerAccount, 10_000, Duration.ofMinutes(1), clock);
        int threadCount = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reachedAuthenticate = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                // Each thread is its own origin, so only the account dimension is under test here.
                String origin = "10.0.0." + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    limiter.checkOrigin(origin);
                    try {
                        limiter.reserveAccountAttempt("victim@iwrite.local");
                        // Reservation granted: this is where AuthController would call
                        // AuthenticationManager.authenticate() and bcrypt would run.
                        reachedAuthenticate.incrementAndGet();
                    } catch (LoginRateLimitExceededException expected) {
                        // 429 before bcrypt — the expected outcome once the budget is spent.
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

        assertThat(reachedAuthenticate.get()).isEqualTo(maxAttemptsPerAccount);
    }

    @Test
    void reservaNaoDevolvidaAposFalhaContinuaConsumida() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local"); // 1/2, never refunded
        attemptAndFail(limiter, "5.6.7.8", "victim@iwrite.local"); // 2/2, never refunded

        // Budget fully spent by the two failures alone: a third reservation is refused before
        // authenticate() would ever run again.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void refundChamadoDuasVezesNaoDescontaDuasVezes() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        LoginRateLimiter.AccountAttemptReservation reservation =
                limiter.reserveAccountAttempt("owner@iwrite.local"); // 1/1
        reservation.refund(); // back to 0/1
        reservation.refund(); // idempotent: must not push the counter negative

        // If the second refund had (incorrectly) decremented again, the account would still accept
        // a reservation here regardless — so prove the counter is exactly 0/1, not -1/1, by spending
        // the single unit twice in a row and seeing the third one refused.
        assertThatCode(() -> limiter.reserveAccountAttempt("owner@iwrite.local")).doesNotThrowAnyException(); // 1/1
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("owner@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void refundAposJanelaRenovarNaoAfetaJanelaNova() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        // Reserved against the original window, then never refunded before it expires — as if the
        // request handling it hung long enough for the window to roll over underneath it.
        LoginRateLimiter.AccountAttemptReservation stale = limiter.reserveAccountAttempt("owner@iwrite.local");

        clock.advance(Duration.ofSeconds(61)); // the window expires

        // A new request reserves against the freshly-reset window.
        limiter.reserveAccountAttempt("owner@iwrite.local"); // 1/1 in the new window

        stale.refund(); // must be a no-op: it belongs to the old, since-renewed window

        // The new window's unit is still spent — the stale refund never touched it.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("owner@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void configuracaoInvalidaFalhaNaConstrucao() {
        assertThatThrownBy(() -> new LoginRateLimiter(0, 1, 10, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(1, 0, 10, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(1, 1, 0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(1, 1, 10, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(1, 1, 10, Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginsValidosRepetidosNaoBloqueiamAConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        // A real owner logging in successfully, several times: each reservation is refunded right
        // after the (simulated) successful authenticate(), so it never accumulates against the
        // account's budget no matter how many sequential logins happen.
        for (int i = 0; i < 10; i++) {
            attemptAndSucceed(limiter, "1.2.3.4", "owner@iwrite.local");
        }
    }

    @Test
    void contaJaEsgotadaERecusadaAntesDeAutenticar() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local");

        // A distributed attack that already spent the account's budget through other origins must
        // be refused before bcrypt runs again for this one — authenticate() is never reached.
        assertThatThrownBy(() -> limiter.reserveAccountAttempt("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
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
