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
import java.util.UUID;

/**
 * Session endpoints. Logout is not declared here: it is served by Spring Security's
 * {@code LogoutFilter} (configured in {@link SecurityConfig}), which already invalidates the
 * session, clears the security context and expires the cookie.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Session attribute holding a fresh {@link UUID} minted by whichever call to {@link
     * #establishSession} last touched the session — never serialized into any response (#149
     * review). Its sole purpose is telling apart "this session id changed because *this* flow
     * mutated it" from "this session id changed because a concurrent flow did": a session id alone
     * cannot distinguish the two, since either one rotates it the same way.
     */
    private static final String SESSION_OWNER_TOKEN_ATTRIBUTE = "iwrite.auth.session-owner-token";

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

        UUID ownershipToken = UUID.randomUUID();
        AuthenticatedUserResponse response = establishSession(authentication, ownershipToken, httpRequest, httpResponse);
        // Nothing downstream of a successful login ever needs to tell this session apart from
        // another flow's again; leaving the marker behind would only be dead state (#149 review).
        releaseSessionOwnerIfStillOwned(httpRequest, ownershipToken);
        return response;
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
     *
     * <p>Comparing session ids alone cannot tell "this session changed because registration mutated
     * it" from "this session changed because a concurrent login mutated it" — either rotates the id
     * the same way. {@link #SESSION_OWNER_TOKEN_ATTRIBUTE} (#149 review) is the tie-breaker: a fresh
     * token minted for this one attempt, written by {@link #establishSession} before and after the
     * mutation, so whichever flow's {@code establishSession} call ran last is whichever token is
     * still on the session when this one's rollback callback runs.
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
        // a first, coarse safety net kept alongside the ownership token below, since neither the
        // request nor the transaction status says which session, if any, belongs to this attempt.
        HttpSession sessionBeforeRegistration = httpRequest.getSession(false);
        String preexistingSessionId = sessionBeforeRegistration != null ? sessionBeforeRegistration.getId() : null;
        UUID registrationToken = UUID.randomUUID();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    // No later step of this registration ever needs the marker again; leaving it
                    // behind would only be dead session state (#149 review).
                    releaseSessionOwnerIfStillOwned(httpRequest, registrationToken);
                } else {
                    discardPartialSession(httpRequest, preexistingSessionId, registrationToken);
                }
            }
        });

        registrationService.register(request);

        String email = EmailNormalizer.normalize(request.email());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));

        return establishSession(authentication, registrationToken, httpRequest, httpResponse);
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
     * Invalidates the HTTP session only if both (a) it is not the one that already existed when
     * {@link #register} started, and (b) {@link #SESSION_OWNER_TOKEN_ATTRIBUTE} still holds the
     * token minted for this one registration attempt — never on session id alone (#149 review): a
     * concurrent login sharing the same session also rotates its id, and would otherwise look
     * indistinguishable from registration's own mutation. If a concurrent login (or another
     * registration) has since taken ownership, the token comparison fails and this is a no-op on the
     * session side, no matter what the id looks like. Always clears the security context this thread
     * just installed. A no-op on the session side when nothing got that far yet (e.g. {@code
     * authenticate} failed first, so {@link #establishSession} never ran) or when the caller's own
     * pre-existing session was never touched. {@code invalidate} can race {@link
     * GlobalExceptionHandler#handleInvalidSession}, which also invalidates the same session for a
     * {@code SessionAuthenticationException} thrown here — the second caller finds it already gone,
     * which is fine, not a state to fail on.
     */
    private void discardPartialSession(HttpServletRequest httpRequest, String preexistingSessionId, UUID registrationToken) {
        HttpSession session = httpRequest.getSession(false);
        boolean sessionCreatedOrRotatedByRegistration =
                session != null && !session.getId().equals(preexistingSessionId);
        boolean stillOwnedByThisRegistration =
                session != null && registrationToken.equals(session.getAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE));
        if (sessionCreatedOrRotatedByRegistration && stillOwnedByThisRegistration) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // Already invalidated by the other cleanup path; nothing left to do.
            }
        }
        SecurityContextHolder.clearContext();
    }

    /** Removes {@link #SESSION_OWNER_TOKEN_ATTRIBUTE} only if it still holds exactly this token —
     *  a compare-and-clear, safe regardless of what has run concurrently since: if some other flow
     *  has since taken ownership, this correctly leaves that marker alone (#149 review). */
    private void releaseSessionOwnerIfStillOwned(HttpServletRequest httpRequest, UUID ownershipToken) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null && ownershipToken.equals(session.getAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE))) {
            session.removeAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE);
        }
    }

    /**
     * Rotates the session id, persists the {@link SecurityContext} (Spring Security 6 no longer
     * does this implicitly — {@code requireExplicitSave} — so skipping it would leave the very next
     * request anonymous), and returns the session payload. Shared by {@link #login} and
     * {@link #register}: both end the same way, with a freshly authenticated principal that needs
     * a real, persisted session built for it.
     *
     * <p>{@code ownershipToken} is written to whatever session already exists before the mutation
     * (a no-op if none does yet — this method, not its caller, is what may create one, and a fault
     * injected inside {@code sessionAuthenticationStrategy.onAuthentication} below must still leave
     * no session behind when none existed before) and reasserted after {@code saveContext}, which is
     * guaranteed to have a session by then. Two writes, not one, because the mutation in between can
     * itself create or rotate the session the first write landed on.
     */
    private AuthenticatedUserResponse establishSession(
            Authentication authentication,
            UUID ownershipToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        markSessionOwnerIfSessionExists(httpRequest, ownershipToken);
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        // Reasserted, not just written once above: saveContext (or the strategy call before it) may
        // have created a brand-new session, or rotated the id of the one the first write landed on —
        // either way, the marker must end up on whichever session this request is actually left with.
        httpRequest.getSession(true).setAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE, ownershipToken);

        return currentSession((IWriteUserDetails) authentication.getPrincipal());
    }

    private void markSessionOwnerIfSessionExists(HttpServletRequest httpRequest, UUID ownershipToken) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE, ownershipToken);
        }
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
