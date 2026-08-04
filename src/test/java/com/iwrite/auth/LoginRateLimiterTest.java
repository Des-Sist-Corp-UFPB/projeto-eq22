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

    /** One full failed login, in the order AuthController drives the limiter for a real request. */
    private static void attemptAndFail(LoginRateLimiter limiter, String origin, String email) {
        limiter.checkOrigin(origin);
        limiter.checkAccountBudget(email);
        limiter.recordFailedAttempt(email);
    }

    @Test
    void permiteAteOLimitePorContaEDepoisRejeita() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        assertThatCode(() -> attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local")).doesNotThrowAnyException();
        assertThatCode(() -> attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local")).doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void distribuirTentativasEntreOrigensNaoEscapaOLimitePorConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 2, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.1.1.1", "victim@iwrite.local");
        attemptAndFail(limiter, "2.2.2.2", "victim@iwrite.local");

        // A third origin, same targeted account: the account budget is what is spent, not the origin's.
        limiter.checkOrigin("3.3.3.3");
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
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

        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void janelaExpiradaLiberaTentativasParaAMesmaConta() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
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
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // A second, distinct account fills the account dimension to its max-tracked-keys capacity.
        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.checkAccountBudget("second@iwrite.local")).doesNotThrowAnyException();

        // The map is now full of two live windows. Querying the victim again must still be a 429,
        // from its own preserved window — never a freshly created, empty one that would readmit it.
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void inundarComContasDescartaveisNaoLibertaAJanelaDaContaVitima() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 1, 2, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        attemptAndFail(limiter, origin, "victim@iwrite.local");
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // A distributed attack floods the account dimension with many disposable emails, well past
        // capacity, trying to force an eviction of the victim's window to make room for itself.
        for (int i = 0; i < 50; i++) {
            limiter.checkOrigin(origin);
            try {
                limiter.checkAccountBudget("disposable-" + i + "@iwrite.local");
            } catch (LoginRateLimitExceededException expected) {
                // Fail-closed once capacity is spent — exactly the protection under test.
            }
        }

        // The victim's window survived every one of those admission attempts.
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

    @Test
    void chaveNovaComTodasAsEntradasAtivasRecebe429ESemCrescerAlemDoLimite() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 100, 2, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.checkAccountBudget("a@iwrite.local")).doesNotThrowAnyException();
        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.checkAccountBudget("b@iwrite.local")).doesNotThrowAnyException();

        // The map is at max-tracked-keys capacity (2), both entries active: a third, unseen key
        // fails closed rather than growing the map or evicting either existing entry.
        assertThatThrownBy(() -> limiter.checkAccountBudget("c@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        // Neither existing entry was evicted to make room for the attempt above.
        assertThatCode(() -> limiter.checkAccountBudget("a@iwrite.local")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAccountBudget("b@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void chaveNovaEhAdmitidaAposUmaEntradaExpirar() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 100, 1, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        limiter.checkOrigin(origin);
        assertThatCode(() -> limiter.checkAccountBudget("a@iwrite.local")).doesNotThrowAnyException();

        // At capacity (max-tracked-keys=1): a second, distinct account is refused while "a"'s window
        // is still active.
        assertThatThrownBy(() -> limiter.checkAccountBudget("b@iwrite.local"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(61)); // "a"'s window expires; it is never revisited.

        // Admitting "b" reclaims the now-expired slot instead of refusing.
        assertThatCode(() -> limiter.checkAccountBudget("b@iwrite.local")).doesNotThrowAnyException();
    }

    @Test
    void chaveExistenteAbaixoDoLimiteContinuaUtilizavelComOMapaCheio() {
        LoginRateLimiter limiter = new LoginRateLimiter(1000, 3, 1, Duration.ofMinutes(1), clock);
        String origin = "1.2.3.4";

        // One failure recorded, budget is 3: still has room, and the map (max-tracked-keys=1) is
        // already completely full with only this account tracked.
        attemptAndFail(limiter, origin, "a@iwrite.local");
        assertThatCode(() -> limiter.checkAccountBudget("a@iwrite.local")).doesNotThrowAnyException();

        // Re-checking and re-spending the same, already-tracked key is never treated as a "new key"
        // admission — its own count keeps accumulating rather than being reset or evicted.
        limiter.checkOrigin(origin);
        limiter.recordFailedAttempt("a@iwrite.local"); // 2/3
        assertThatCode(() -> limiter.checkAccountBudget("a@iwrite.local")).doesNotThrowAnyException();
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
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
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
                        limiter.checkAccountBudget("attacker-" + index + "@iwrite.local");
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
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
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

        // A real owner logging in successfully, several times: checkAccountBudget alone (never
        // followed by recordFailedAttempt) must never spend the account's budget.
        for (int i = 0; i < 10; i++) {
            limiter.checkOrigin("1.2.3.4");
            assertThatCode(() -> limiter.checkAccountBudget("owner@iwrite.local")).doesNotThrowAnyException();
        }
    }

    @Test
    void contaJaEsgotadaERecusadaAntesDeAutenticar() {
        LoginRateLimiter limiter = new LoginRateLimiter(100, 1, 10_000, Duration.ofMinutes(1), clock);

        attemptAndFail(limiter, "1.2.3.4", "victim@iwrite.local");

        // A distributed attack that already spent the account's budget through other origins must
        // be refused before bcrypt runs again for this one — recordFailedAttempt is never reached.
        assertThatThrownBy(() -> limiter.checkAccountBudget("victim@iwrite.local"))
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
