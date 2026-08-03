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
 * budget of every account behind it.
 *
 * <p>The two dimensions count differently, on purpose. The origin budget ({@link #checkOrigin})
 * counts every call, success or failure, and is spent before any password is checked: its job is
 * to bound how much bcrypt one source can trigger, including from an attacker who never sends a
 * real account. The account budget only counts failures ({@link #recordFailedAttempt}, called
 * only from a catch block): a real owner logging in from several tabs or devices must never be
 * throttled out of their own account by their own successful logins. What the account dimension
 * still does before authenticating ({@link #checkAccountBudget}) is refuse an already-exhausted
 * account outright, so a distributed attack that already tripped the account limit through other
 * origins cannot keep spending bcrypt on this one either.
 *
 * <p>Refusing never says which dimension tripped, and {@link AuthMessages#TOO_MANY_LOGIN_ATTEMPTS}
 * is as silent about account existence as a wrong password. It is a fixed window, not a lockout: it
 * always reopens on its own, so nobody can hold someone else's account shut by supplying its email
 * in failed attempts for longer than one window — the account dimension slows the account down, it
 * never locks it, and stopping the attempts is enough to let the real owner back in immediately at
 * the next window.
 *
 * <p>The origin is whatever {@link ClientAddressResolver} resolves — the real per-browser address
 * behind the frontend's proxy when it is a configured trusted peer, {@code remoteAddr} otherwise —
 * never a client-supplied header taken on faith.
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

    /** Spends one unit of the calling origin's budget for this window, success or failure alike. */
    public void checkOrigin(String origin) {
        if (!tryIncrement(byOrigin, normalizedOrigin(origin), maxAttemptsPerOrigin)) {
            throw new LoginRateLimitExceededException();
        }
    }

    /**
     * Refuses an account that already spent its failure budget, without spending anything itself —
     * a login that turns out to be valid must never count against the account it just authenticated.
     */
    public void checkAccountBudget(String email) {
        String account = normalizedAccount(email);
        if (account != null && !hasRemainingBudget(byAccount, account, maxAttemptsPerAccount)) {
            throw new LoginRateLimitExceededException();
        }
    }

    /** Spends one unit of the targeted account's budget. Call only after authentication failed. */
    public void recordFailedAttempt(String email) {
        String account = normalizedAccount(email);
        if (account != null) {
            increment(byAccount, account);
        }
    }

    private boolean tryIncrement(ConcurrentHashMap<String, Window> states, String key, int maxAttempts) {
        Window state = statesGet(states, key);
        synchronized (state) {
            resetIfExpired(state);
            if (state.count >= maxAttempts) {
                return false;
            }
            state.count++;
            return true;
        }
    }

    private boolean hasRemainingBudget(ConcurrentHashMap<String, Window> states, String key, int maxAttempts) {
        Window state = statesGet(states, key);
        synchronized (state) {
            resetIfExpired(state);
            return state.count < maxAttempts;
        }
    }

    private void increment(ConcurrentHashMap<String, Window> states, String key) {
        Window state = statesGet(states, key);
        synchronized (state) {
            resetIfExpired(state);
            state.count++;
        }
    }

    private Window statesGet(ConcurrentHashMap<String, Window> states, String key) {
        prune(states, clock.millis());
        return states.computeIfAbsent(key, ignored -> new Window());
    }

    private void resetIfExpired(Window state) {
        long now = clock.millis();
        if (now - state.startMillis >= window.toMillis()) {
            state.startMillis = now;
            state.count = 0;
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
