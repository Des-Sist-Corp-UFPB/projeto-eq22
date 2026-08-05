package com.iwrite.auth;

import com.iwrite.auth.dto.AuthenticatedUserResponse;
import com.iwrite.auth.dto.LoginRequest;
import com.iwrite.auth.dto.RegisterRequest;
import com.iwrite.common.exception.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Session endpoints. Logout is not declared here: it is served by Spring Security's
 * {@code LogoutFilter} (configured in {@link SecurityConfig}), which already invalidates the
 * session, clears the security context and expires the cookie.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthSessionService authSessionService;
    private final RegistrationService registrationService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final LoginRateLimiter loginRateLimiter;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final ClientAddressResolver clientAddressResolver;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthSessionService authSessionService,
            RegistrationService registrationService,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            LoginRateLimiter loginRateLimiter,
            RegistrationRateLimiter registrationRateLimiter,
            ClientAddressResolver clientAddressResolver
    ) {
        this.authenticationManager = authenticationManager;
        this.authSessionService = authSessionService;
        this.registrationService = registrationService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.loginRateLimiter = loginRateLimiter;
        this.registrationRateLimiter = registrationRateLimiter;
        this.clientAddressResolver = clientAddressResolver;
    }

    /** Issues the {@code XSRF-TOKEN} cookie the SPA echoes back as {@code X-XSRF-TOKEN}. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public AuthenticatedUserResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        loginRateLimiter.checkOrigin(clientAddressResolver.resolve(httpRequest));
        // Reserved before bcrypt runs, not just checked: a concurrent burst against one account
        // must not all pass a read-then-later-increment race and reach authenticate() together.
        LoginRateLimiter.AccountAttemptReservation reservation =
                loginRateLimiter.reserveAccountAttempt(request.email());

        Authentication authentication;
        try {
            if (request.email() == null || request.password() == null) {
                throw new BadCredentialsException("Missing credentials");
            }
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            EmailNormalizer.normalize(request.email()), request.password()));
        } catch (AuthenticationException e) {
            // The reservation stays spent: a real failure must keep counting against the account's
            // budget, and refunding here would let the same unit be spent twice concurrently.
            throw e;
        }

        // Refunded immediately, before the session is created: a real owner logging in from
        // several tabs or devices must never be throttled out of their own account by their own
        // successful logins.
        reservation.refund();

        return establishSession(authentication, httpRequest, httpResponse);
    }

    /**
     * Creates the personal workspace ({@link RegistrationService}), re-authenticates through the
     * exact same {@link AuthenticationManager} path {@link #login} uses against the credential just
     * persisted, and establishes the session — all inside one {@code REQUIRED} transaction (issue
     * #143), so a failure anywhere in that sequence leaves none of the five registration entities
     * behind. Re-authenticating rather than hand-building the response is also what guarantees the
     * session has the identical contract as one produced by {@code /api/auth/login}.
     *
     * <p>Refused up front, before the synchronization is even registered, for a caller who already
     * holds an authenticated session: public registration only ever starts a brand-new identity, and
     * letting it run over one that already exists would rotate the caller's own session id and, on
     * any failure, risk tearing that pre-existing session down for a mistake ({@code discardPartialSession}
     * below is only ever safe to invoke against a session this method itself touched). The caller
     * must log out first.
     *
     * <p>A transaction rollback only undoes the database side; it does not by itself undo a session
     * id already rotated by {@link #establishSession} or a {@link SecurityContext} already installed
     * on this thread. The {@link TransactionSynchronization} below is what erases both whenever the
     * transaction does not end up committed — including the case where {@code authenticate} or
     * {@code establishSession} throws, and the narrower case where everything up to here succeeded
     * but the commit itself still fails, which must never leave a live session pointing at a user
     * that was never actually persisted.
     */
    @PostMapping("/register")
    @Transactional
    public AuthenticatedUserResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (isAuthenticatedPrincipal()) {
            throw new ConflictException(RegistrationMessages.ALREADY_AUTHENTICATED);
        }

        registrationRateLimiter.checkOrigin(clientAddressResolver.resolve(httpRequest));

        // Captured before anything below can create or rotate a session, so the rollback callback
        // can tell "this session already existed" from "this session is what registration made" —
        // the only thing that tells the two apart, since neither the request nor the transaction
        // status says which session, if any, belongs to this attempt.
        HttpSession sessionBeforeRegistration = httpRequest.getSession(false);
        String preexistingSessionId = sessionBeforeRegistration != null ? sessionBeforeRegistration.getId() : null;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    discardPartialSession(httpRequest, preexistingSessionId);
                }
            }
        });

        registrationService.register(request);

        String email = EmailNormalizer.normalize(request.email());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));

        return establishSession(authentication, httpRequest, httpResponse);
    }

    /** A caller counts as already authenticated only if the security context carries IWrite's own
     *  principal — the same test {@link com.iwrite.user.context.AuthenticatedCurrentUserProvider}
     *  uses — so the default anonymous authentication Spring Security installs for every unauthenticated
     *  request never trips this check. */
    private boolean isAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof IWriteUserDetails;
    }

    /**
     * Invalidates the HTTP session only if it is not the one that already existed when {@link
     * #register} started — i.e. only a session this registration attempt itself created or rotated
     * (via {@link #establishSession}) — and always clears the security context this thread just
     * installed. A no-op on the session side when nothing got that far yet (e.g. {@code authenticate}
     * failed first) or when the caller's own pre-existing session was never touched; comparing ids
     * rather than a flag set before {@link #establishSession} runs is what still catches the
     * in-between case where session id rotation happened but the subsequent context save did not.
     * {@code invalidate} can race {@link GlobalExceptionHandler#handleInvalidSession}, which also
     * invalidates the same session for a {@code SessionAuthenticationException} thrown here — the
     * second caller finds it already gone, which is fine, not a state to fail on.
     */
    private void discardPartialSession(HttpServletRequest httpRequest, String preexistingSessionId) {
        HttpSession session = httpRequest.getSession(false);
        boolean sessionCreatedOrRotatedByRegistration =
                session != null && !session.getId().equals(preexistingSessionId);
        if (sessionCreatedOrRotatedByRegistration) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // Already invalidated by the other cleanup path; nothing left to do.
            }
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * Rotates the session id, persists the {@link SecurityContext} (Spring Security 6 no longer
     * does this implicitly — {@code requireExplicitSave} — so skipping it would leave the very next
     * request anonymous), and returns the session payload. Shared by {@link #login} and
     * {@link #register}: both end the same way, with a freshly authenticated principal that needs
     * a real, persisted session built for it.
     */
    private AuthenticatedUserResponse establishSession(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return currentSession((IWriteUserDetails) authentication.getPrincipal());
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse me(@AuthenticationPrincipal IWriteUserDetails principal) {
        return currentSession(principal);
    }

    /**
     * Resolves the session payload straight from the database. The session is never allowed to
     * answer from its own frozen copy: if the membership disappeared, {@link GlobalExceptionHandler}
     * destroys the session when it catches the {@link SessionAuthenticationException} thrown here,
     * so the caller has to authenticate again.
     */
    private AuthenticatedUserResponse currentSession(IWriteUserDetails principal) {
        Optional<AuthenticatedUserResponse> session = authSessionService.revalidate(principal);
        if (session.isEmpty()) {
            throw new SessionAuthenticationException("Membership backing this session is no longer valid");
        }
        return session.get();
    }
}
