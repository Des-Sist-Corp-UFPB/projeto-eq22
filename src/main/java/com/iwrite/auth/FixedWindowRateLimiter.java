package com.iwrite.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic bounded, in-memory fixed-window counter for exactly one rate-limiting dimension, keyed
 * by an arbitrary string. Extracted so {@link LoginRateLimiter} (two instances, one per dimension)
 * and {@link RegistrationRateLimiter} share the exact same fail-closed admission and
 * reservation/refund mechanics instead of each maintaining a divergent copy — see
 * {@link LoginRateLimiter}'s class Javadoc for the rationale a single dimension implements.
 *
 * <p>Refusal is reported as a {@code null} {@link Reservation} rather than by throwing: this class
 * has no opinion on what a caller's exhausted budget should mean to the outside world (a login
 * caller and a registration caller answer with different, endpoint-specific exceptions).
 */
// ponytail: per-instance in-memory state, bounded by maxTrackedKeys with fixed-window eviction. A
// multi-instance deployment needs a shared store (e.g. Redis) or each instance only enforces its
// own independent window, which lowers the effective limit protection by the instance count.
final class FixedWindowRateLimiter {

    private final int maxAttempts;
    private final int maxTrackedKeys;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> states = new ConcurrentHashMap<>();
    // Admits a brand-new key with a single atomic decision ("does this key already exist, and if
    // not, is there room after reclaiming only expired entries"), which a bare
    // ConcurrentHashMap.computeIfAbsent cannot express on its own. Ordinary get/increment/reset on
    // an *existing* key never takes this lock.
    private final Object admissionLock = new Object();

    FixedWindowRateLimiter(int maxAttempts, int maxTrackedKeys, Duration window, Clock clock) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts deve ser no mínimo 1.");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys deve ser no mínimo 1.");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window deve ser uma duração positiva (ex.: 1m).");
        }
        this.maxAttempts = maxAttempts;
        this.maxTrackedKeys = maxTrackedKeys;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Reserves one unit of {@code key}'s budget for this window, or returns {@code null} if the
     * budget (or the dimension's tracked-key capacity) is spent. The returned token is opaque and
     * carries no key: it pins the specific {@link Window} instance and the generation it was
     * reserved against, which is exactly what {@link Reservation#refund()} needs to give the unit
     * back safely later.
     */
    Reservation reserve(String key) {
        Window state = statesGet(key);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            resetIfExpired(state);
            if (state.count >= maxAttempts) {
                return null;
            }
            state.count++;
            return new Reservation(state, state.generation);
        }
    }

    /**
     * A key already being tracked is returned as-is, without ever consulting capacity — an existing
     * window is never a candidate for eviction just because the map happens to be full. Only a key
     * seen for the first time goes through admission, which is where capacity is enforced.
     */
    private Window statesGet(String key) {
        Window existing = states.get(key);
        if (existing != null) {
            return existing;
        }
        return admitNewKey(key);
    }

    /**
     * Fail-closed admission for a key not yet tracked. Reclaims only windows that have actually
     * expired; if the map is still at capacity afterwards, every remaining entry is a live window
     * that belongs to some other key, and none of them yields — the new key is refused (a null
     * result, the same refusal an exhausted key itself produces) instead, so a flood of
     * never-before-seen keys can never evict an active one to make room for itself.
     *
     * <p>The existence check, the prune, the capacity check and the insertion are one atomic
     * decision under {@link #admissionLock}, the only lock this class takes that spans more than a
     * single {@link Window}.
     */
    private Window admitNewKey(String key) {
        synchronized (admissionLock) {
            Window existing = states.get(key);
            if (existing != null) {
                return existing;
            }
            long now = clock.millis();
            if (states.size() >= maxTrackedKeys) {
                states.entrySet().removeIf(entry -> now - entry.getValue().startMillis >= window.toMillis());
            }
            if (states.size() >= maxTrackedKeys) {
                return null;
            }
            Window created = new Window();
            created.startMillis = now;
            states.put(key, created);
            return created;
        }
    }

    private void resetIfExpired(Window state) {
        long now = clock.millis();
        if (now - state.startMillis >= window.toMillis()) {
            state.startMillis = now;
            state.count = 0;
            state.generation++;
        }
    }

    private static final class Window {
        private long startMillis;
        private int count;
        // Bumped every time resetIfExpired renews this window. A reservation taken against a since-
        // renewed window must never refund into the new one — the generation it captured is the
        // only way to tell the two apart, since the Window instance itself is reused in place.
        private long generation;
    }

    /**
     * Opaque token for one reserved unit of a key's budget. Deliberately carries no key — only the
     * {@link Window} it was reserved against and the generation at that time.
     */
    static final class Reservation {

        private final Window window;
        private final long generation;
        private final AtomicBoolean refunded = new AtomicBoolean(false);

        private Reservation(Window window, long generation) {
            this.window = window;
            this.generation = generation;
        }

        /**
         * Gives the reserved unit back. Idempotent — calling it more than once never decrements
         * twice — and a no-op if the window was renewed (expired and reset) while this reservation
         * was outstanding, so a refund can never land in a window it was never actually spent from.
         */
        void refund() {
            if (!refunded.compareAndSet(false, true)) {
                return;
            }
            synchronized (window) {
                if (window.generation == generation && window.count > 0) {
                    window.count--;
                }
            }
        }
    }
}
