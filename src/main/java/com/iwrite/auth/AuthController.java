package com.iwrite.auth;

import com.iwrite.auth.dto.AuthenticatedUserResponse;
import com.iwrite.auth.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientAddressResolver clientAddressResolver;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthSessionService authSessionService,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            LoginRateLimiter loginRateLimiter,
            ClientAddressResolver clientAddressResolver
    ) {
        this.authenticationManager = authenticationManager;
        this.authSessionService = authSessionService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.loginRateLimiter = loginRateLimiter;
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
        loginRateLimiter.checkAccountBudget(request.email());

        Authentication authentication;
        try {
            if (request.email() == null || request.password() == null) {
                throw new BadCredentialsException("Missing credentials");
            }
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (AuthenticationException e) {
            // Only a failure spends the account's budget: a real owner logging in from several
            // tabs or devices must never be throttled out of their own account by their own
            // successful logins.
            loginRateLimiter.recordFailedAttempt(request.email());
            throw e;
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        // Spring Security 6 no longer persists the context implicitly (requireExplicitSave), so
        // without this save the login would succeed and the very next request would be anonymous.
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
