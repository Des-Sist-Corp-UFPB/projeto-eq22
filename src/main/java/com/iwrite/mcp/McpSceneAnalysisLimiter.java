package com.iwrite.mcp;

import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Proteção simples contra abuso e custo do {@code analisar_cena}: no máximo uma
 * análise concorrente por identidade (tenant + usuário) e uma janela fixa de
 * tentativas configurável. Tentativas com falha também contam — o objetivo é
 * limitar chamadas ao provedor, não sucessos.
 */
// ponytail: estado em memória por instância; store compartilhado se houver múltiplas réplicas.
@Service
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpSceneAnalysisLimiter {

    private final CurrentUserProvider currentUserProvider;
    private final int maxPerWindow;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, IdentityState> states = new ConcurrentHashMap<>();

    @Autowired
    public McpSceneAnalysisLimiter(
            CurrentUserProvider currentUserProvider,
            @Value("${iwrite.mcp.scene-analysis.max-per-window:3}") int maxPerWindow,
            @Value("${iwrite.mcp.scene-analysis.window:1m}") Duration window
    ) {
        this(currentUserProvider, maxPerWindow, window, Clock.systemUTC());
    }

    McpSceneAnalysisLimiter(CurrentUserProvider currentUserProvider, int maxPerWindow, Duration window, Clock clock) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException(
                    "iwrite.mcp.scene-analysis.max-per-window deve ser no mínimo 1.");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "iwrite.mcp.scene-analysis.window deve ser uma duração positiva (ex.: 1m).");
        }
        this.currentUserProvider = currentUserProvider;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.clock = clock;
    }

    public <T> T withLimit(Supplier<T> operation) {
        String key = currentUserProvider.tenantId() + ":" + currentUserProvider.userId();
        IdentityState state = states.computeIfAbsent(key, ignored -> new IdentityState());
        synchronized (state) {
            if (state.inFlight) {
                throw new McpToolException(
                        McpToolException.CATEGORY_RATE_LIMITED,
                        "Já existe uma análise em andamento para este usuário. Aguarde a conclusão."
                );
            }
            long now = clock.millis();
            if (now - state.windowStartMillis >= window.toMillis()) {
                state.windowStartMillis = now;
                state.count = 0;
            }
            if (state.count >= maxPerWindow) {
                throw new McpToolException(
                        McpToolException.CATEGORY_RATE_LIMITED,
                        "Limite de análises atingido para este usuário. Tente novamente em instantes."
                );
            }
            state.count++;
            state.inFlight = true;
        }
        try {
            return operation.get();
        } finally {
            synchronized (state) {
                state.inFlight = false;
            }
        }
    }

    private static final class IdentityState {
        private boolean inFlight;
        private long windowStartMillis;
        private int count;
    }
}
