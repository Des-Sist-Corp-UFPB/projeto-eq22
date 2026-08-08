package com.iwrite.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Bounded, in-memory throttling for {@code POST /api/auth/register}, on its own budget — never
 * borrowed from {@link LoginRateLimiter}. Origin only: a registration always targets a brand-new
 * email, so an account dimension would only ever protect an account from itself. What actually
 * needs bounding is distributed abuse from one source trying many different emails, which the
 * origin dimension already catches regardless of which email each attempt used.
 *
 * <p>Checked before {@link RegistrationService#register} runs, so a spent budget is refused before
 * the password hash and the four-table transaction it guards ever happen.
 */
@Component
public class RegistrationRateLimiter {

    private final FixedWindowRateLimiter byOrigin;

    @Autowired
    public RegistrationRateLimiter(
            @Value("${iwrite.auth.registration-rate-limit.max-attempts-per-origin:10}") int maxAttemptsPerOrigin,
            @Value("${iwrite.auth.registration-rate-limit.max-tracked-keys:10000}") int maxTrackedKeys,
            @Value("${iwrite.auth.registration-rate-limit.window:1m}") Duration window
    ) {
        this(maxAttemptsPerOrigin, maxTrackedKeys, window, Clock.systemUTC());
    }

    RegistrationRateLimiter(int maxAttemptsPerOrigin, int maxTrackedKeys, Duration window, Clock clock) {
        this.byOrigin = new FixedWindowRateLimiter(maxAttemptsPerOrigin, maxTrackedKeys, window, clock);
    }

    /** Spends one unit of the calling origin's budget for this window, success or failure alike. */
    public void checkOrigin(String origin) {
        if (byOrigin.reserve(normalizedOrigin(origin)) == null) {
            throw new RegistrationRateLimitExceededException();
        }
    }

    private static String normalizedOrigin(String origin) {
        return origin == null || origin.isBlank() ? "unknown" : origin;
    }
}
