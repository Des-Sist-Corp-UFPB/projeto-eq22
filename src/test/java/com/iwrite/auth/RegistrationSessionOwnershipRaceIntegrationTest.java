package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.auth.dto.AuthenticatedUserResponse;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserPersonaRepository;
import com.iwrite.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic, latch-driven coverage of the session-ownership token (#149 round-6 review, finding
 * 5): a session id changing cannot by itself tell "registration mutated this session" from "a
 * concurrent login sharing the same session mutated it" — both rotate the id the same way. These
 * force the exact interleaving a real race could produce, against a real Spring context, real HTTP
 * and a real database, rather than only asserting the mechanism at the unit level.
 *
 * <p>Two independent injection points, each keyed by the racing registration's own email so the
 * concurrent login's own calls to the same beans pass straight through undisturbed:
 * <ul>
 *   <li>{@link RaceConfig#racyAuthenticationManager} pauses (and can fail) registration's own
 *       re-authentication call, before {@link AuthController#establishSession} ever runs — scenario A.</li>
 *   <li>{@link RaceConfig#racyAuthSessionService} pauses (and can fail) inside {@code revalidate},
 *       strictly after {@link AuthController#establishSession} has fully mutated the session and
 *       released its lock (round 9, #149 review) — after the mutation, before the transaction
 *       commits — scenario C. Round 8 injected this fault mid-{@code establishSession} instead (right
 *       after the real session id rotation); round 9 widened {@code establishSession}'s own lock to
 *       cover its entire body, which would make that injection point deadlock against a concurrent
 *       login sharing the same lock, so the fault moved to the one place guaranteed to run after the
 *       lock is released.</li>
 * </ul>
 *
 * <p>Scenario B (registration mutates and is the sole, later cause of an invalidated session) is
 * already covered, unchanged, by {@code RegistrationWhileAuthenticatedIntegrationTest}
 * #falhaAposMutacaoRealDaSessaoContinuaInvalidandoASessaoParcial. Scenario D (no race — existing
 * atomicity/cleanup tests stay green) is the rest of the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationSessionOwnershipRaceIntegrationTest.RaceConfig.class)
class RegistrationSessionOwnershipRaceIntegrationTest {

    private static final String VALID_PASSWORD = "senha-valida-1";

    private static volatile String emailToPauseInAuthenticate;
    private static volatile boolean failAfterAuthenticatePause;
    private static volatile CountDownLatch registrationReachedAuthenticate;
    private static volatile CountDownLatch loginCompletedForAuthenticate;

    private static volatile String emailToPauseAfterSessionMutation;
    private static volatile boolean failAfterSessionMutationPause;
    private static volatile CountDownLatch registrationReachedSessionMutation;
    private static volatile CountDownLatch loginCompletedForSessionMutation;

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        registry.add("iwrite.auth.registration-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-account", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserCredentialRepository credentialRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private TenantMembershipRepository membershipRepository;
    @Autowired
    private UserPersonaRepository personaRepository;

    @AfterEach
    void resetRaceState() {
        emailToPauseInAuthenticate = null;
        failAfterAuthenticatePause = false;
        registrationReachedAuthenticate = null;
        loginCompletedForAuthenticate = null;
        emailToPauseAfterSessionMutation = null;
        failAfterSessionMutationPause = false;
        registrationReachedSessionMutation = null;
        loginCompletedForSessionMutation = null;
    }

    /**
     * Scenario A: same anonymous session. Registration captures its pre-existing session state,
     * then blocks inside its own re-authentication call (before {@code establishSession} ever runs
     * for it); a concurrent login on that same session authenticates and rotates it first; only then
     * does registration's own re-authentication fail — its rollback must not invalidate the login.
     */
    @Test
    void loginConcorrenteAssumeASessaoAnonimaECadastroQueFalhaAntesDoProprioEstablishSessionNaoOInvalida() throws Exception {
        String existingEmail = registerNewAccount();
        String racingEmail = uniqueEmail();
        MockHttpSession sharedSession = new MockHttpSession();

        emailToPauseInAuthenticate = racingEmail;
        failAfterAuthenticatePause = true;
        registrationReachedAuthenticate = new CountDownLatch(1);
        loginCompletedForAuthenticate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> registrationFuture = pool.submit(() ->
                    mockMvc.perform(withCsrf(post("/api/auth/register")).session(sharedSession)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(registerBody(racingEmail))))
                            .andReturn());

            assertThat(registrationReachedAuthenticate.await(10, TimeUnit.SECONDS))
                    .as("registration reached its own re-authentication call")
                    .isTrue();

            // The concurrent login: same session, a different, already-existing account.
            mockMvc.perform(withCsrf(post("/api/auth/login")).session(sharedSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("email", existingEmail, "password", VALID_PASSWORD))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value(existingEmail));

            loginCompletedForAuthenticate.countDown();

            MvcResult registrationResult = registrationFuture.get(10, TimeUnit.SECONDS);
            assertThat(registrationResult.getResponse().getStatus()).isEqualTo(401);
        } finally {
            pool.shutdown();
        }

        assertThat(userRepository.findByEmail(racingEmail)).isEmpty();
        // The login's session survives registration's rollback intact.
        mockMvc.perform(get("/api/auth/me").session(sharedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    /**
     * Scenario C: registration's own {@code establishSession} fully mutates the session (token
     * written, id really rotated) and releases its lock; only then, inside {@code revalidate} — after
     * the lock is gone (round 9, #149 review) — does a concurrent login on that same session get the
     * chance to complete and take ownership, before registration's own transaction still fails
     * (simulating a late failure that is not itself a session/authentication problem — the same shape
     * the class Javadoc calls out: "the commit itself still fails"). The rollback must preserve the
     * login, not the registration's own now-superseded mutation.
     */
    @Test
    void loginQueConcluiDepoisDeCadastroMutarASessaoAssumeAPropriedadeERollbackDoCadastroPreservaOLogin() throws Exception {
        String existingEmail = registerNewAccount();
        String racingEmail = uniqueEmail();
        // An anonymous session has to exist beforehand: the real session-authentication strategy is a
        // no-op when there is none, so without this the fault would fire before anything real ever
        // mutated (same reasoning as RegistrationWhileAuthenticatedIntegrationTest's own fault-after-
        // mutation test).
        MockHttpSession sharedSession = new MockHttpSession();

        emailToPauseAfterSessionMutation = racingEmail;
        failAfterSessionMutationPause = true;
        registrationReachedSessionMutation = new CountDownLatch(1);
        loginCompletedForSessionMutation = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> registrationFuture = pool.submit(() ->
                    mockMvc.perform(withCsrf(post("/api/auth/register")).session(sharedSession)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(registerBody(racingEmail))))
                            .andReturn());

            assertThat(registrationReachedSessionMutation.await(10, TimeUnit.SECONDS))
                    .as("registration reached revalidate, strictly after establishSession released its lock")
                    .isTrue();

            mockMvc.perform(withCsrf(post("/api/auth/login")).session(sharedSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("email", existingEmail, "password", VALID_PASSWORD))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value(existingEmail));

            loginCompletedForSessionMutation.countDown();

            MvcResult registrationResult = registrationFuture.get(10, TimeUnit.SECONDS);
            // Whatever internal fault type surfaces this, the exact status is incidental here — what
            // matters is that registration's own transaction did not commit.
            assertThat(registrationResult.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        } finally {
            pool.shutdown();
        }

        assertThat(userRepository.findByEmail(racingEmail)).isEmpty();
        mockMvc.perform(get("/api/auth/me").session(sharedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    /**
     * Scenario A (#149 review, round 10, fresh finding): same shared, initially anonymous session as
     * the scenario above, but this time registration's own re-authentication is allowed to succeed
     * after the pause — reaching, before this round, the point where it would have unconditionally
     * overwritten the session a concurrent login just finished establishing. {@code
     * establishRegistrationSession}'s revalidation (under the same lock as its own mutation) must
     * catch that a real {@link SecurityContext} is already on the shared session, refuse with 409, and
     * roll back — leaving the login's session untouched and none of registration's five entities
     * behind (scenario B folded into this same test: {@code User}, {@code UserCredential}, {@code
     * Tenant}, {@code TenantMembership} and {@code UserPersona} counts are unchanged).
     */
    @Test
    void loginConcorrenteEstabelecidoAntesDaFaseFinalDoCadastroFazEsteDetectarSessaoAutenticadaERecusar() throws Exception {
        String existingEmail = registerNewAccount();
        String racingEmail = uniqueEmail();
        MockHttpSession sharedSession = new MockHttpSession();

        long usersBefore = userRepository.count();
        long credentialsBefore = credentialRepository.count();
        long tenantsBefore = tenantRepository.count();
        long membershipsBefore = membershipRepository.count();
        long personasBefore = personaRepository.count();

        emailToPauseInAuthenticate = racingEmail;
        failAfterAuthenticatePause = false;
        registrationReachedAuthenticate = new CountDownLatch(1);
        loginCompletedForAuthenticate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> registrationFuture = pool.submit(() ->
                    mockMvc.perform(withCsrf(post("/api/auth/register")).session(sharedSession)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(registerBody(racingEmail))))
                            .andReturn());

            assertThat(registrationReachedAuthenticate.await(10, TimeUnit.SECONDS))
                    .as("registration passed its initial thread-local check and reached its own re-authentication call")
                    .isTrue();

            // The concurrent login, on the same session, finishes completely before registration
            // resumes: its SecurityContext is fully persisted on the shared session by the time this
            // andExpect returns.
            mockMvc.perform(withCsrf(post("/api/auth/login")).session(sharedSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("email", existingEmail, "password", VALID_PASSWORD))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value(existingEmail));

            loginCompletedForAuthenticate.countDown();

            MvcResult registrationResult = registrationFuture.get(10, TimeUnit.SECONDS);
            assertThat(registrationResult.getResponse().getStatus()).isEqualTo(409);
        } finally {
            pool.shutdown();
        }

        assertThat(userRepository.findByEmail(racingEmail)).isEmpty();
        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(credentialRepository.count()).isEqualTo(credentialsBefore);
        assertThat(tenantRepository.count()).isEqualTo(tenantsBefore);
        assertThat(membershipRepository.count()).isEqualTo(membershipsBefore);
        assertThat(personaRepository.count()).isEqualTo(personasBefore);

        // The login's own session survives untouched: still the login's identity, not rolled back or
        // rotated by registration's refusal.
        mockMvc.perform(get("/api/auth/me").session(sharedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    /** Scenario D (#149 review, round 10): two unrelated sessions racing registration/login at the
     *  HTTP level must never block each other — the per-session lock only ever excludes flows sharing
     *  the exact same session (already proven at the lock-mechanism level by {@code
     *  AuthControllerSessionOwnershipConcurrencyTest#sessoesDistintasEstabelecemConcorrentementeSemSeBloquear};
     *  this exercises the same guarantee through the real HTTP endpoints). */
    @Test
    void sessoesDistintasRegistramSeConcorrentementeSemSeBloquear() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        MockHttpSession sessionA = new MockHttpSession();
        MockHttpSession sessionB = new MockHttpSession();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> futureA = pool.submit(() ->
                    mockMvc.perform(withCsrf(post("/api/auth/register")).session(sessionA)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(registerBody(emailA))))
                            .andReturn());
            Future<MvcResult> futureB = pool.submit(() ->
                    mockMvc.perform(withCsrf(post("/api/auth/register")).session(sessionB)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(registerBody(emailB))))
                            .andReturn());

            assertThat(futureA.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(futureB.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }

        assertThat(userRepository.findByEmail(emailA)).isPresent();
        assertThat(userRepository.findByEmail(emailB)).isPresent();
    }

    /** Scenario E (#149 review, round 10): the existing session-swap contract is unaffected by this
     *  round's change — once registration has fully established its own session (no concurrency here,
     *  strictly sequential), a later {@code /api/auth/login} for a *different* account on that same
     *  session must still be free to swap it, exactly as it could before this round. */
    @Test
    void loginAposCadastroConcluidoAindaTrocaASessaoConformeOContratoDeSessionSwap() throws Exception {
        String registeredEmail = uniqueEmail();
        String otherEmail = registerNewAccount();
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(withCsrf(post("/api/auth/register")).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(registeredEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(registeredEmail));

        mockMvc.perform(withCsrf(post("/api/auth/login")).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", otherEmail, "password", VALID_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(otherEmail));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(otherEmail));
    }

    /** Registers a brand-new account (its own unique email) and returns that email, for use as the
     *  "already exists, can log in concurrently" side of each race. */
    private String registerNewAccount() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email))))
                .andExpect(status().isOk());
        return email;
    }

    private Map<String, Object> registerBody(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", "Alguém");
        body.put("email", email);
        body.put("password", VALID_PASSWORD);
        body.put("passwordConfirmation", VALID_PASSWORD);
        body.put("primaryPersona", "WRITER");
        body.put("timeZone", "America/Sao_Paulo");
        return body;
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private String uniqueEmail() {
        return "race-" + UUID.randomUUID() + "@iwrite.local";
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the concurrent login in the ownership race test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class RaceConfig {

        /** Pauses (and can fail) only the re-authentication call for the one email under test,
         *  right where {@link AuthController#register} calls it — before its own {@code
         *  establishSession} ever runs. A concurrent login's own call, for a different email, passes
         *  straight through to the real delegate untouched. */
        @Bean
        @Primary
        AuthenticationManager racyAuthenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
            provider.setUserDetailsService(userDetailsService);
            provider.setPasswordEncoder(passwordEncoder);
            provider.setHideUserNotFoundExceptions(true);
            AuthenticationManager delegate = new ProviderManager(provider);
            return authentication -> {
                String principalEmail = String.valueOf(authentication.getPrincipal());
                if (principalEmail.equals(emailToPauseInAuthenticate)) {
                    registrationReachedAuthenticate.countDown();
                    awaitQuietly(loginCompletedForAuthenticate);
                    if (failAfterAuthenticatePause) {
                        throw new BadCredentialsException("Injected failure for session-ownership race test (scenario A)");
                    }
                }
                return delegate.authenticate(authentication);
            };
        }

        /** Delegates every read to the real {@link AuthSessionService}, then pauses (and can fail)
         *  only for the one principal under test, right inside {@code revalidate} — the one call
         *  {@link AuthController#currentSession} makes strictly after {@code establishSession} has
         *  released its lock (round 9, #149 review), so the pause can never block a concurrent flow
         *  sharing that same lock. A concurrent login's own call, for a different principal, passes
         *  straight through to the real delegate untouched, both before and after. */
        @Bean
        @Primary
        AuthSessionService racyAuthSessionService(
                UserRepository userRepository,
                UserCredentialRepository credentialRepository,
                TenantMembershipRepository membershipRepository
        ) {
            return new AuthSessionService(userRepository, credentialRepository, membershipRepository) {
                @Override
                public Optional<AuthenticatedUserResponse> revalidate(IWriteUserDetails principal) {
                    if (principal.getUsername().equals(emailToPauseAfterSessionMutation)) {
                        registrationReachedSessionMutation.countDown();
                        awaitQuietly(loginCompletedForSessionMutation);
                        if (failAfterSessionMutationPause) {
                            // Deliberately not a SessionAuthenticationException: GlobalExceptionHandler's
                            // handleInvalidSession unconditionally invalidates whatever session currently
                            // exists on the request, which would defeat this exact scenario (the
                            // concurrent login's session must survive). A plain unchecked exception here
                            // stands in for the class Javadoc's "the commit itself still fails" case,
                            // which is not a session/authentication failure at all.
                            throw new IllegalStateException("Injected failure for session-ownership race test (scenario C)");
                        }
                    }
                    return super.revalidate(principal);
                }
            };
        }
    }
}
