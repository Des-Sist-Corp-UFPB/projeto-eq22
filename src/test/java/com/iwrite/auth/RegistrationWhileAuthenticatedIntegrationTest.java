package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.TestDatabaseInitializer;
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
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the P2 Codex raised against {@link AuthController#register}: a caller who already holds
 * an authenticated session must be refused before registration touches anything, and a rollback
 * must never tear down a session it did not itself create or rotate. Not {@code @Transactional} —
 * same reasoning as {@link RegistrationIntegrationTest} and {@link RegistrationAtomicityIntegrationTest}:
 * these tests need the register call's own commit-or-rollback to be real, not folded into one
 * shared test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationWhileAuthenticatedIntegrationTest.MutateThenFailConfig.class)
class RegistrationWhileAuthenticatedIntegrationTest {

    private static final String VALID_PASSWORD = "senha-valida-1";
    private static final AtomicBoolean FAIL_AFTER_SESSION_MUTATION = new AtomicBoolean(false);

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

    @AfterEach
    void resetFaultInjection() {
        FAIL_AFTER_SESSION_MUTATION.set(false);
    }

    @Test
    void cadastroComEmailDuplicadoNaMesmaSessaoAutenticadaERecusadoEPreservaASessao() throws Exception {
        String existingEmail = uniqueEmail();
        MockHttpSession session = loggedInSession(existingEmail);

        String responseBody = attemptRegister(session, registerBody(existingEmail, "Segunda Conta", "WRITER", "America/Sao_Paulo"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.ALREADY_AUTHENTICATED);
        assertThat(userRepository.findAll().stream().filter(u -> existingEmail.equals(u.getEmail())).count()).isEqualTo(1);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    @Test
    void cadastroComSenhaInvalidaNaMesmaSessaoAutenticadaERecusadoEPreservaASessao() throws Exception {
        String existingEmail = uniqueEmail();
        MockHttpSession session = loggedInSession(existingEmail);
        String newEmail = uniqueEmail();

        String responseBody = attemptRegister(session, registerBody(newEmail, "Outra Pessoa", "curta1", "curta1", "WRITER", "America/Sao_Paulo"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.ALREADY_AUTHENTICATED);
        assertThat(userRepository.findByEmail(newEmail)).isEmpty();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    @Test
    void cadastroComPersonaInvalidaNaMesmaSessaoAutenticadaERecusadoEPreservaASessao() throws Exception {
        String existingEmail = uniqueEmail();
        MockHttpSession session = loggedInSession(existingEmail);
        String newEmail = uniqueEmail();

        String responseBody = attemptRegister(session, registerBody(newEmail, "Outra Pessoa", "PROTAGONISTA", "America/Sao_Paulo"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.ALREADY_AUTHENTICATED);
        assertThat(userRepository.findByEmail(newEmail)).isEmpty();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    @Test
    void cadastroBemFormadoNaMesmaSessaoAutenticadaERecusadoEPreservaASessao() throws Exception {
        String existingEmail = uniqueEmail();
        MockHttpSession session = loggedInSession(existingEmail);
        String newEmail = uniqueEmail();

        String responseBody = attemptRegister(session, registerBody(newEmail, "Conta Nova", "WRITER", "America/Sao_Paulo"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.ALREADY_AUTHENTICATED);
        assertThat(userRepository.findByEmail(newEmail)).isEmpty();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(existingEmail));
    }

    @Test
    void falhaAposMutacaoRealDaSessaoContinuaInvalidandoASessaoParcial() throws Exception {
        String email = uniqueEmail();
        FAIL_AFTER_SESSION_MUTATION.set(true);
        // An anonymous session has to exist beforehand: ChangeSessionIdAuthenticationStrategy is a
        // no-op when there is none (session creation would only happen later, in saveContext), so
        // without this the fault would fire before anything real ever mutated — not the case this
        // test means to cover.
        MockHttpSession sessionBeforeRegistration = new MockHttpSession();
        String originalSessionId = sessionBeforeRegistration.getId();

        mockMvc.perform(withCsrf(post("/api/auth/register")).session(sessionBeforeRegistration)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email, "Alguém", "WRITER", "America/Sao_Paulo"))))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findByEmail(email)).isEmpty();
        // The strategy really rotated the session id before the injected failure fired.
        assertThat(sessionBeforeRegistration.getId()).isNotEqualTo(originalSessionId);
        // ...and the rotated session was invalidated, not left pointing at a reverted registration.
        mockMvc.perform(get("/api/auth/me").session(sessionBeforeRegistration))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession loggedInSession(String email) throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email, "Titular", "WRITER", "America/Sao_Paulo"))))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", VALID_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private ResultActions attemptRegister(MockHttpSession session, Map<String, Object> body) throws Exception {
        return mockMvc.perform(withCsrf(post("/api/auth/register")).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> registerBody(String email, String displayName, String persona, String timeZone) {
        return registerBody(email, displayName, VALID_PASSWORD, VALID_PASSWORD, persona, timeZone);
    }

    private Map<String, Object> registerBody(
            String email, String displayName, String password, String passwordConfirmation, String persona, String timeZone
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("email", email);
        body.put("password", password);
        body.put("passwordConfirmation", passwordConfirmation);
        body.put("primaryPersona", persona);
        body.put("timeZone", timeZone);
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
        return "ja-autenticado-" + UUID.randomUUID() + "@iwrite.local";
    }

    @TestConfiguration
    static class MutateThenFailConfig {

        /** Runs the real strategy first — a real session id rotation/creation — then optionally
         *  throws, so the failure happens strictly after a real mutation instead of in place of one. */
        @Bean
        @Primary
        SessionAuthenticationStrategy mutateThenFailSessionAuthenticationStrategy() {
            SessionAuthenticationStrategy delegate = new ChangeSessionIdAuthenticationStrategy();
            return (authentication, request, response) -> {
                delegate.onAuthentication(authentication, request, response);
                if (FAIL_AFTER_SESSION_MUTATION.get()) {
                    throw new SessionAuthenticationException("Injected failure after real session mutation");
                }
            };
        }
    }
}
