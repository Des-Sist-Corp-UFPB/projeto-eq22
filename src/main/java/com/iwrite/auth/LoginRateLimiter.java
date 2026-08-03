package com.iwrite.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, in-memory login throttling on two independent dimensions — the calling origin and the
 * targeted account — so a distributed attempt against one account and a concentrated attempt from
 * one source are both caught, and a busy shared origin (an office NAT, a proxy) cannot exhaust the
 * budget of every account behind it. Every call counts, success or failure: the goal is to bound
 * attempts, not just failures, the same policy {@link com.iwrite.mcp.McpSceneAnalysisLimiter}
 * already uses for scene analysis.
 *
 * <p>Refusing never says which dimension tripped, and {@link AuthMessages#TOO_MANY_LOGIN_ATTEMPTS}
 * is as silent about account existence as a wrong password. It is a fixed window, not a lockout: it
 * always reopens on its own, so nobody can hold someone else's account shut by supplying its email
 * in failed attempts for longer than one window — the account dimension slows the account down, it
 * never locks it, and stopping the attempts is enough to let the real owner back in immediately at
 * the next window.
 *
 * <p>The origin is {@code request.getRemoteAddr()}, never a client-supplied header:
 * {@code X-Forwarded-For} is trivially spoofed by whoever sends the request, so trusting it here
 * would let an attacker pick a fresh "origin" on every attempt and defeat the whole dimension.
 * Behind a real reverse proxy, resolve the true peer with Spring's own
 * {@code server.forward-headers-strategy=framework} (from a trusted hop) — this class only ever
 * reads the value the servlet container already resolved.
 */
// ponytail: per-instance in-memory state, bounded by max-tracked-keys with fixed-window eviction.
// A multi-instance deployment needs a shared store (e.g. Redis) or each instance only enforces its
// own independent window, which lowers the effective limit protection by the instance count.
@Component
public class LoginRateLimiter {

    private final int maxAttemptsPerOrigin;
    private final int maxAttemptsPerAccount;
    private final int maxTrackedKeysPerDimension;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> byOrigin = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> byAccount = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(
            @Value("${iwrite.auth.login-rate-limit.max-attempts-per-origin:20}") int maxAttemptsPerOrigin,
            @Value("${iwrite.auth.login-rate-limit.max-attempts-per-account:8}") int maxAttemptsPerAccount,
            @Value("${iwrite.auth.login-rate-limit.max-tracked-keys:10000}") int maxTrackedKeysPerDimension,
            @Value("${iwrite.auth.login-rate-limit.window:1m}") Duration window
    ) {
        this(maxAttemptsPerOrigin, maxAttemptsPerAccount, maxTrackedKeysPerDimension, window, Clock.systemUTC());
    }

    LoginRateLimiter(
            int maxAttemptsPerOrigin,
            int maxAttemptsPerAccount,
            int maxTrackedKeysPerDimension,
            Duration window,
            Clock clock
    ) {
        if (maxAttemptsPerOrigin < 1 || maxAttemptsPerAccount < 1) {
            throw new IllegalArgumentException(
                    "iwrite.auth.login-rate-limit.max-attempts-per-origin/account devem ser no mínimo 1.");
        }
        if (maxTrackedKeysPerDimension < 1) {
            throw new IllegalArgumentException(
                    "iwrite.auth.login-rate-limit.max-tracked-keys deve ser no mínimo 1.");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "iwrite.auth.login-rate-limit.window deve ser uma duração positiva (ex.: 1m).");
        }
        this.maxAttemptsPerOrigin = maxAttemptsPerOrigin;
        this.maxAttemptsPerAccount = maxAttemptsPerAccount;
        this.maxTrackedKeysPerDimension = maxTrackedKeysPerDimension;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Records one login attempt and throws once either dimension's budget for its current window
     * is spent. {@code email} may be {@code null} (a malformed request still consumes the origin
     * budget); a blank one is normalized away rather than tracked as a shared "" account key.
     */
    public void checkAllowed(String origin, String email) {
        if (!tryIncrement(byOrigin, normalizedOrigin(origin), maxAttemptsPerOrigin)) {
            throw new LoginRateLimitExceededException();
        }
        String account = normalizedAccount(email);
        if (account != null && !tryIncrement(byAccount, account, maxAttemptsPerAccount)) {
            throw new LoginRateLimitExceededException();
        }
    }

    private boolean tryIncrement(ConcurrentHashMap<String, Window> states, String key, int maxAttempts) {
        long now = clock.millis();
        prune(states, now);
        Window state = states.computeIfAbsent(key, ignored -> new Window());
        synchronized (state) {
            if (now - state.startMillis >= window.toMillis()) {
                state.startMillis = now;
                state.count = 0;
            }
            if (state.count >= maxAttempts) {
                return false;
            }
            state.count++;
            return true;
        }
    }

    /** Bounds memory: expired windows are dropped first; if still full, the single oldest yields. */
    private void prune(ConcurrentHashMap<String, Window> states, long now) {
        if (states.size() < maxTrackedKeysPerDimension) {
            return;
        }
        states.entrySet().removeIf(entry -> now - entry.getValue().startMillis >= window.toMillis());
        if (states.size() >= maxTrackedKeysPerDimension) {
            states.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().startMillis))
                    .ifPresent(oldest -> states.remove(oldest.getKey()));
        }
    }

    private static String normalizedOrigin(String origin) {
        return origin == null || origin.isBlank() ? "unknown" : origin;
    }

    private static String normalizedAccount(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Window {
        private long startMillis;
        private int count;
    }
}
