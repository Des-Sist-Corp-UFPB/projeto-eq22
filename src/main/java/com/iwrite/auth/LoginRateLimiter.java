package com.iwrite.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Bounded, in-memory login throttling on two independent dimensions — the calling origin and the
 * targeted account — so a distributed attempt against one account and a concentrated attempt from
 * one source are both caught, and a busy shared origin (an office NAT, a proxy) cannot exhaust the
 * budget of every account behind it. Each dimension is its own {@link FixedWindowRateLimiter}
 * instance, never sharing state with the other.
 *
 * <p>The two dimensions count differently, on purpose. The origin budget ({@link #checkOrigin})
 * counts every call, success or failure, and is spent before any password is checked: its job is
 * to bound how much bcrypt one source can trigger, including from an attacker who never sends a
 * real account. The account dimension reserves a unit ({@link #reserveAccountAttempt}) *before*
 * bcrypt runs, and a successful login refunds that same unit right away
 * ({@link AccountAttemptReservation#refund()}): sequential valid logins from several tabs or
 * devices never accumulate against the account's own budget. A burst of concurrently valid logins
 * larger than the per-account limit can still see some of its members get a temporary 429 while
 * their reservations are in flight — that is the point, it is what keeps concurrent bcrypt calls
 * for one account bounded, and the window reopens on its own moments later.
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
@Component
public class LoginRateLimiter {

    private final FixedWindowRateLimiter byOrigin;
    private final FixedWindowRateLimiter byAccount;

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
        this.byOrigin = new FixedWindowRateLimiter(maxAttemptsPerOrigin, maxTrackedKeysPerDimension, window, clock);
        this.byAccount = new FixedWindowRateLimiter(maxAttemptsPerAccount, maxTrackedKeysPerDimension, window, clock);
    }

    /** Spends one unit of the calling origin's budget for this window, success or failure alike. */
    public void checkOrigin(String origin) {
        if (byOrigin.reserve(normalizedOrigin(origin)) == null) {
            throw new LoginRateLimitExceededException();
        }
    }

    /**
     * Reserves one unit of the targeted account's budget before any password is checked, so a
     * concurrent burst against one account cannot all pass a read-then-later-increment race and
     * reach bcrypt together. Throws {@link LoginRateLimitExceededException} instead of returning
     * when the account has no room left — the caller must never reach
     * {@code AuthenticationManager.authenticate} in that case.
     *
     * <p>A null or blank email — already rejected by request validation before authentication would
     * even run — gets a no-op reservation: the origin dimension already bounds that traffic.
     */
    public AccountAttemptReservation reserveAccountAttempt(String email) {
        String account = normalizedAccount(email);
        if (account == null) {
            return AccountAttemptReservation.noop();
        }
        FixedWindowRateLimiter.Reservation reservation = byAccount.reserve(account);
        if (reservation == null) {
            throw new LoginRateLimitExceededException();
        }
        return new AccountAttemptReservation(reservation);
    }

    private static String normalizedOrigin(String origin) {
        return origin == null || origin.isBlank() ? "unknown" : origin;
    }

    private static String normalizedAccount(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Thin wrapper over {@link FixedWindowRateLimiter.Reservation} that keeps the public API and
     *  the {@code refund()} contract exactly as callers (e.g. {@link AuthController}) already use it. */
    public static final class AccountAttemptReservation {

        private static final AccountAttemptReservation NOOP = new AccountAttemptReservation(null);

        private final FixedWindowRateLimiter.Reservation reservation;

        private AccountAttemptReservation(FixedWindowRateLimiter.Reservation reservation) {
            this.reservation = reservation;
        }

        private static AccountAttemptReservation noop() {
            return NOOP;
        }

        /** Gives the reserved unit back. Idempotent, and a no-op for a no-op reservation. */
        public void refund() {
            if (reservation != null) {
                reservation.refund();
            }
        }
    }
}
