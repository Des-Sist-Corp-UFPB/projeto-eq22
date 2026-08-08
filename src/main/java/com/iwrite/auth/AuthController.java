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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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

    /**
     * One mutex per pre-existing session (#149 review, round 8; widened round 9). {@link
     * #establishSession} holds it for its *entire* critical section — the initial ownership marker,
     * {@code sessionAuthenticationStrategy.onAuthentication}, the {@link SecurityContext} creation and
     * {@code saveContext}, and the final ownership reassertion — as one atomic unit, and {@link
     * #discardPartialSession}'s ownership read together with its {@code invalidate()} runs under the
     * same lock. Round 8 released the lock between those steps, guarding only the token writes
     * themselves; that let a login pause after marking its token but before {@code onAuthentication},
     * a concurrent registration finish establishing its own session and later roll back, and the
     * rollback's {@code invalidate()} destroy the session the login was still in the middle of
     * completing. Making the whole operation one critical section per lock removes that window: two
     * flows sharing a session are fully serialized through {@link #establishSession}/{@link
     * #discardPartialSession}, never interleaved partway through either.
     *
     * <p>Deliberately not held across the database round trip in {@link #currentSession} — that runs
     * after {@link #establishSession} has already released the lock, once the session is fully
     * established: only the establishment (and its rollback) need cross-flow atomicity, and holding a
     * session's lock across a database call would needlessly block unrelated requests.
     *
     * <p>The lock is the {@link HttpSession} object itself (see {@link #sessionLock}) — not its id, and
     * not a session-keyed registry that would need its own eviction. A servlet container hands back the
     * exact same {@code HttpSession} instance to every request presenting the same session, and mutates
     * that instance's id in place on rotation rather than replacing it (confirmed against both real
     * Tomcat sessions and {@code MockHttpSession}, which the concurrent tests below run against) — so
     * two concurrent requests that share a session keep sharing the same monitor even across a
     * rotation triggered by either one of them, with no registry to leak or evict. {@code synchronized}
     * is reentrant per thread, so a container operation that itself locks on this same session object
     * (e.g. a real session-id change) cannot deadlock against the lock this thread already holds.
     */
    static Object sessionLock(HttpSession preexistingSession) {
        return preexistingSession != null ? preexistingSession : new Object();
    }

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
            // Checked before authenticate() ever runs, so a malformed-surrogate or oversized
            // password never reaches PasswordEncoder.matches: String.getBytes(UTF_8) would silently
            // substitute an unpaired surrogate instead of rejecting it, letting a password crafted
            // with one unpaired surrogate authenticate in place of a stored password that only
            // shares its first 72 bytes with a different one (#149 review). The reservation above
            // stays spent — this is a real failed attempt, not refunded like a successful login.
            if (!BcryptInputPolicy.isValid(request.password(), PasswordPolicy.MAX_UTF8_BYTES)) {
                throw new BadCredentialsException("Malformed or oversized password");
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
        // Captured before establishSession can mutate anything, exactly like register() below —
        // whichever session this login started on, its ownership token is only ever touched under
        // the matching sessionLock (#149 review, round 8).
        HttpSession sessionBeforeLogin = httpRequest.getSession(false);
        Object lock = sessionLock(sessionBeforeLogin);
        AuthenticatedUserResponse response = establishSession(authentication, ownershipToken, httpRequest, httpResponse, lock);
        // Nothing downstream of a successful login ever needs to tell this session apart from
        // another flow's again; leaving the marker behind would only be dead state (#149 review).
        releaseSessionOwnerIfStillOwned(httpRequest, ownershipToken, lock);
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
     * must log out first. That check reads only {@link SecurityContextHolder}, this thread's own copy
     * of this one request's authentication — it cannot see a concurrent login finishing on a *shared*
     * session after this check ran but before database work and re-authentication below complete
     * (#149 review, fresh finding). {@link #establishRegistrationSession} is the authoritative guard
     * against that race: it rereads the session's actually-persisted {@link SecurityContext} once
     * more, inside the same lock as the mutation that follows, immediately before taking the session
     * over.
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
     * token minted for this one attempt, written by {@link #establishSessionStateLocked} before and
     * after the mutation, so whichever flow's establishment ran last is whichever token is still on
     * the session when this one's rollback callback runs. Registration's own establishment never
     * writes that token at all if {@link #establishRegistrationSession}'s recheck finds the session
     * already authenticated — so a login that won that race is never at risk of the token comparison
     * misfiring in registration's favor.
     *
     * <p>That comparison and {@code invalidate()} form one critical section (#149 review, round 8),
     * and so does the entirety of {@link #establishRegistrationSession} — its own revalidation and the
     * mutation that follows, together (round 9 widened {@link #establishSession}'s critical section
     * the same way; round 10 split the shared mutation out into {@link #establishSessionStateLocked}
     * so registration's wider section could wrap a revalidation around it too). Every touch of the
     * ownership token or the session it mutates, here, in {@link #login}, and inside {@link
     * #discardPartialSession}/{@link #releaseSessionOwnerIfStillOwned}, runs under the same {@link
     * #sessionLock}. A concurrent login can therefore never write its own token, run {@code
     * onAuthentication}, or save its {@link SecurityContext} partway through this method's own
     * revalidation, establishment or rollback decision — all three are fully serialized against it.
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
        // Same mutex login() locks on for this exact pre-existing session: every read or write of the
        // ownership token for this session, on either flow, goes through it (#149 review, round 8).
        Object lock = sessionLock(sessionBeforeRegistration);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    // No later step of this registration ever needs the marker again; leaving it
                    // behind would only be dead session state (#149 review).
                    releaseSessionOwnerIfStillOwned(httpRequest, registrationToken, lock);
                } else {
                    discardPartialSession(httpRequest, preexistingSessionId, registrationToken, lock);
                }
            }
        });

        registrationService.register(request);

        String email = EmailNormalizer.normalize(request.email());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));

        return establishRegistrationSession(authentication, registrationToken, httpRequest, httpResponse, lock);
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
     *
     * <p>The token read and {@code invalidate()} below run inside one {@code synchronized} block on
     * {@code lock} (#149 review, round 8) — the same object {@code sessionLock} returns for this
     * pre-existing session, and the same object {@link #establishSession} (its entire critical
     * section, round 9) and {@link #releaseSessionOwnerIfStillOwned} synchronize on for their own
     * touches of the session. That is what makes the read and the invalidation decision atomic with
     * respect to a concurrent flow's own establishment: it can only happen strictly before this block
     * starts or strictly after it ends, never in the middle of it.
     */
    private void discardPartialSession(HttpServletRequest httpRequest, String preexistingSessionId, UUID registrationToken, Object lock) {
        synchronized (lock) {
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
        }
        SecurityContextHolder.clearContext();
    }

    /** Removes {@link #SESSION_OWNER_TOKEN_ATTRIBUTE} only if it still holds exactly this token —
     *  a compare-and-clear, safe regardless of what has run concurrently since: if some other flow
     *  has since taken ownership, this correctly leaves that marker alone (#149 review). Synchronizes
     *  on {@code lock}, the same object every other touch of this session's token uses (#149 review,
     *  round 8). */
    private void releaseSessionOwnerIfStillOwned(HttpServletRequest httpRequest, UUID ownershipToken, Object lock) {
        synchronized (lock) {
            HttpSession session = httpRequest.getSession(false);
            if (session != null && ownershipToken.equals(session.getAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE))) {
                session.removeAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE);
            }
        }
    }

    /**
     * Rotates the session id, persists the {@link SecurityContext} (Spring Security 6 no longer
     * does this implicitly — {@code requireExplicitSave} — so skipping it would leave the very next
     * request anonymous), and returns the session payload. Used by {@link #login} directly, and by
     * {@link #establishRegistrationSession} as the last step of its own, wider critical section.
     *
     * <p>{@code lock} must be the object {@code sessionLock} returns for the session that existed
     * before this call — the same one {@link #discardPartialSession} and {@link
     * #releaseSessionOwnerIfStillOwned} synchronize on for this same session.
     */
    private AuthenticatedUserResponse establishSession(
            Authentication authentication,
            UUID ownershipToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            Object lock
    ) {
        IWriteUserDetails principal;
        synchronized (lock) {
            principal = establishSessionStateLocked(authentication, ownershipToken, httpRequest, httpResponse);
        }
        return currentSession(principal);
    }

    /**
     * Registration's own counterpart to {@link #establishSession}: revalidates, under the same lock,
     * that no concurrent flow has authenticated this shared session since {@link #register} did its
     * one-time, thread-local {@code isAuthenticatedPrincipal()} check at the top of the request — and
     * only then runs the exact same mutation {@link #establishSessionStateLocked} performs for login,
     * inside that same critical section (#149 review, fresh finding).
     *
     * <p>That thread-local check cannot by itself catch a login racing on the same anonymous
     * {@code HttpSession}: two tabs sharing one session can both pass it before either has written
     * anything, because {@link SecurityContextHolder} only ever reflects *this* request's own thread,
     * never what a concurrent request on another thread has since saved onto the *shared* session. By
     * the time registration reaches this method, database work and a full {@code bcrypt} hash/verify
     * cycle have run — plenty of time for a concurrent login on the same session to finish first and
     * leave a real, authenticated {@link SecurityContext} sitting in the session registration is about
     * to overwrite.
     *
     * <p>{@link #sessionAlreadyAuthenticated} reads that {@link SecurityContext} straight off the
     * {@link HttpSession} Spring Security itself persisted it to — not {@code SecurityContextHolder}
     * and not a {@code DeferredSecurityContext} captured at the start of the request, both of which
     * are this thread's own copy and cannot see a concurrent thread's write to the shared session. The
     * read and the mutation that follows run inside one {@code synchronized(lock)} block, with no
     * release in between: nothing can authenticate this session between the recheck and registration
     * taking it over. Found already authenticated, this throws {@link ConflictException} with {@link
     * RegistrationMessages#ALREADY_AUTHENTICATED} — before {@link #establishSessionStateLocked} ever
     * runs, so registration's own token is never written to the session. That is exactly what keeps
     * {@link #discardPartialSession}'s rollback safe: its ownership comparison only ever invalidates a
     * session still marked with *this* registration's token, and a token that was never written can
     * never match.
     */
    private AuthenticatedUserResponse establishRegistrationSession(
            Authentication authentication,
            UUID ownershipToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            Object lock
    ) {
        IWriteUserDetails principal;
        synchronized (lock) {
            if (sessionAlreadyAuthenticated(httpRequest.getSession(false))) {
                throw new ConflictException(RegistrationMessages.ALREADY_AUTHENTICATED);
            }
            principal = establishSessionStateLocked(authentication, ownershipToken, httpRequest, httpResponse);
        }
        return currentSession(principal);
    }

    /**
     * True only if {@code session} carries a {@link SecurityContext}, itself carrying an {@link
     * Authentication} that is non-null, {@link Authentication#isAuthenticated()} and whose principal
     * is IWrite's own {@link IWriteUserDetails} — the same three conditions {@link
     * #isAuthenticatedPrincipal()} checks against the thread-local context, applied here to the
     * session's actually-persisted one (#149 review). {@link
     * HttpSessionSecurityContextRepository#SPRING_SECURITY_CONTEXT_KEY} is the exact attribute {@link
     * #securityContextRepository} (an {@link HttpSessionSecurityContextRepository} in production)
     * writes under in {@link #establishSessionStateLocked}, so a concurrent flow's completed
     * establishment is visible here the moment its own critical section ends. Must only ever be called
     * from inside the same {@code synchronized(lock)} section as the mutation it gates.
     */
    private boolean sessionAlreadyAuthenticated(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof SecurityContext securityContext)) {
            return false;
        }
        Authentication authentication = securityContext.getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof IWriteUserDetails;
    }

    /**
     * The actual session mutation shared by {@link #login} (via {@link #establishSession}) and {@link
     * #register} (via {@link #establishRegistrationSession}): writes {@code ownershipToken} to
     * whatever session already exists (a no-op if none does yet — this method, not its caller, is
     * what may create one, and a fault injected inside {@code sessionAuthenticationStrategy}'s call
     * below must still leave no session behind when none existed before), rotates the session id,
     * persists the {@link SecurityContext}, and reasserts the token on whatever session the request
     * is actually left with afterward. Two token writes, not one, because the mutation in between can
     * itself create or rotate the session the first write landed on.
     *
     * <p><b>Assumes the caller already holds {@code lock}</b> — this method never synchronizes on it
     * itself (#149 review, round 10 refactor of round 9's widened critical section): {@link
     * #establishSession} acquires it around this call alone, while {@link
     * #establishRegistrationSession} acquires it once around both its own revalidation and this call,
     * so the two never observe or mutate the session partway through either flow.
     */
    private IWriteUserDetails establishSessionStateLocked(
            Authentication authentication,
            UUID ownershipToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        HttpSession preexisting = httpRequest.getSession(false);
        if (preexisting != null) {
            preexisting.setAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE, ownershipToken);
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // Reasserted, not just written once above: saveContext (or the strategy call before it) may
        // have created a brand-new session, or rotated the id of the one the first write landed on —
        // either way, the marker must end up on whichever session this request is actually left with.
        httpRequest.getSession(true).setAttribute(SESSION_OWNER_TOKEN_ATTRIBUTE, ownershipToken);
        return (IWriteUserDetails) authentication.getPrincipal();
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
