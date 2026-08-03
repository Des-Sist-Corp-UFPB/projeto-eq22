package com.iwrite.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
    void limiteDeChavesRastreadasEhRespeitadoAoInvesDeCrescerSemLimite() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 100, 2, Duration.ofMinutes(1), clock);

        assertThatCode(() -> {
            for (int i = 0; i < 500; i++) {
                attemptAndFail(limiter, "origin-" + i, "user-" + i + "@iwrite.local");
            }
        }).doesNotThrowAnyException();
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
