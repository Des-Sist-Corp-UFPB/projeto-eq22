package com.iwrite.auth;

import com.iwrite.auth.dto.AuthenticatedUserResponse;
import com.iwrite.auth.dto.LoginRequest;
import com.iwrite.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
     * Creates the personal workspace transactionally ({@link RegistrationService}), then
     * authenticates through the exact same {@link AuthenticationManager} path {@link #login} uses,
     * against the credential it just persisted — this is what guarantees the returned session has
     * the identical contract as one produced by {@code /api/auth/login}, rather than a hand-built
     * copy that could drift from it.
     */
    @PostMapping("/register")
    public AuthenticatedUserResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        registrationRateLimiter.checkOrigin(clientAddressResolver.resolve(httpRequest));
        registrationService.register(request);

        String email = EmailNormalizer.normalize(request.email());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));

        return establishSession(authentication, httpRequest, httpResponse);
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
