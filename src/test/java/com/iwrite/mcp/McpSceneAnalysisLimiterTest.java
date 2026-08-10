package com.iwrite.mcp;

import com.iwrite.user.context.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class McpSceneAnalysisLimiterTest {

    private final MutableProvider provider = new MutableProvider();
    private final MutableClock clock = new MutableClock();
    private final McpSceneAnalysisLimiter limiter =
            new McpSceneAnalysisLimiter(provider, 2, Duration.ofMinutes(1), clock);

    @Test
    void permiteAteOLimiteDaJanelaEDepoisRejeitaComCategoriaRateLimited() {
        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");
        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");

        McpToolException limited = catchThrowableOfType(
                McpToolException.class,
                () -> limiter.withLimit(() -> "excedente")
        );
        assertThat(limited.category()).isEqualTo(McpToolException.CATEGORY_RATE_LIMITED);
        assertThat(limited.getMessage()).startsWith("{\"error\":{\"category\":\"rate_limited\"");
    }

    @Test
    void janelaExpiradaLiberaNovasAnalises() {
        limiter.withLimit(() -> "ok");
        limiter.withLimit(() -> "ok");
        assertThatThrownBy(() -> limiter.withLimit(() -> "excedente")).isInstanceOf(McpToolException.class);

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void falhaDaOperacaoLiberaOBloqueioEContaNaJanela() {
        assertThatThrownBy(() -> limiter.withLimit(() -> {
            throw new IllegalStateException("falha do provedor");
        })).isInstanceOf(IllegalStateException.class);

        // O bloqueio de concorrência foi liberado; a tentativa com falha contou na janela.
        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");
        McpToolException limited = catchThrowableOfType(
                McpToolException.class,
                () -> limiter.withLimit(() -> "excedente")
        );
        assertThat(limited.category()).isEqualTo(McpToolException.CATEGORY_RATE_LIMITED);
    }

    @Test
    void segundaAnaliseConcorrenteDaMesmaIdentidadeERejeitada() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Object> firstResult = new AtomicReference<>();

        Thread first = new Thread(() -> firstResult.set(limiter.withLimit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "primeira";
        })));
        first.start();
        entered.await();

        McpToolException concurrent = catchThrowableOfType(
                McpToolException.class,
                () -> limiter.withLimit(() -> "concorrente")
        );
        assertThat(concurrent.category()).isEqualTo(McpToolException.CATEGORY_RATE_LIMITED);

        release.countDown();
        first.join();
        assertThat(firstResult.get()).isEqualTo("primeira");

        // Concluída a primeira, ainda há orçamento na janela (a concorrente rejeitada não contou).
        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void configuracaoInvalidaFalhaNaConstrucaoComMensagemSegura() {
        assertThatThrownBy(() -> new McpSceneAnalysisLimiter(provider, 0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-per-window");
        assertThatThrownBy(() -> new McpSceneAnalysisLimiter(provider, -3, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-per-window");
        assertThatThrownBy(() -> new McpSceneAnalysisLimiter(provider, 1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new McpSceneAnalysisLimiter(provider, 1, Duration.ofSeconds(-5), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void configuracaoValidaConstroiEFunciona() {
        McpSceneAnalysisLimiter valid = new McpSceneAnalysisLimiter(provider, 1, Duration.ofSeconds(30), clock);
        assertThat(valid.withLimit(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void identidadesDistintasTemOrcamentosIndependentes() {
        limiter.withLimit(() -> "ok");
        limiter.withLimit(() -> "ok");
        assertThatThrownBy(() -> limiter.withLimit(() -> "excedente")).isInstanceOf(McpToolException.class);

        provider.switchIdentity();

        assertThat(limiter.withLimit(() -> "ok")).isEqualTo("ok");
    }

    private static final class MutableProvider implements CurrentUserProvider {

        private UUID userId = UUID.randomUUID();
        private UUID tenantId = UUID.randomUUID();

        void switchIdentity() {
            userId = UUID.randomUUID();
            tenantId = UUID.randomUUID();
        }

        @Override
        public UUID userId() {
            return userId;
        }

        @Override
        public UUID tenantId() {
            return tenantId;
        }

        @Override
        public ZoneId effectiveZoneId() {
            return ZoneOffset.UTC;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-07-31T12:00:00Z");

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
