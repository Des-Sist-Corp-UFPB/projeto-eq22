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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the transactional boundary added to {@link AuthController#register} for issue #143: a
 * failure that happens only after {@link RegistrationService} has already written its five rows —
 * a failed re-authentication, or a failed session establishment — must leave none of them behind,
 * and must never leave a usable session. {@link FaultInjectionConfig} wraps the real
 * {@link AuthenticationManager} and {@link SessionAuthenticationStrategy} beans with a toggle so
 * these failures happen deterministically against a real Spring context, real HTTP and a real
 * database, rather than only being asserted with mocks (see {@code RegistrationServiceTest} for
 * the unit-level coverage, and {@code RegistrationIntegrationTest} for the concurrent-duplicate
 * case, which this transactional change does not alter).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationAtomicityIntegrationTest.FaultInjectionConfig.class)
class RegistrationAtomicityIntegrationTest {

    private static final String VALID_PASSWORD = "senha-valida-1";
    private static final AtomicBoolean FAIL_AUTHENTICATION = new AtomicBoolean(false);
    private static final AtomicBoolean FAIL_SESSION_STRATEGY = new AtomicBoolean(false);

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
        FAIL_AUTHENTICATION.set(false);
        FAIL_SESSION_STRATEGY.set(false);
    }

    @Test
    void falhaNaAutenticacaoAposAsEscritasNaoDeixaNenhumaLinha() throws Exception {
        String email = uniqueEmail();
        FAIL_AUTHENTICATION.set(true);

        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email))))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void falhaNaEstrategiaDeSessaoNaoDeixaNenhumaLinhaNemSessaoAutenticada() throws Exception {
        String email = uniqueEmail();
        FAIL_SESSION_STRATEGY.set(true);

        MvcResult result = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(userRepository.findByEmail(email)).isEmpty();
        // No session survived: the fault fires before establishSession ever gets far enough to
        // create one.
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void sucessoContinuaPersistindoTudoEDevolvendoUmaSessaoRestauravel() throws Exception {
        String email = uniqueEmail();

        MvcResult result = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(email))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(userRepository.findByEmail(email)).isPresent();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    private Map<String, Object> registerBody(String email) {
        return Map.of(
                "displayName", "Alguém",
                "email", email,
                "password", VALID_PASSWORD,
                "passwordConfirmation", VALID_PASSWORD,
                "primaryPersona", "WRITER",
                "timeZone", "America/Sao_Paulo"
        );
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private String uniqueEmail() {
        return "atomic-" + UUID.randomUUID() + "@iwrite.local";
    }

    @TestConfiguration
    static class FaultInjectionConfig {

        /** Builds its own real {@link ProviderManager} rather than wrapping the app's own
         *  {@code AuthenticationManager} bean, so marking this one {@code @Primary} cannot create a
         *  self-referencing circular bean definition. */
        @Bean
        @Primary
        AuthenticationManager faultInjectingAuthenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
            provider.setUserDetailsService(userDetailsService);
            provider.setPasswordEncoder(passwordEncoder);
            provider.setHideUserNotFoundExceptions(true);
            AuthenticationManager delegate = new ProviderManager(provider);
            return authentication -> {
                if (FAIL_AUTHENTICATION.get()) {
                    throw new BadCredentialsException("Injected failure for atomicity test");
                }
                return delegate.authenticate(authentication);
            };
        }

        @Bean
        @Primary
        SessionAuthenticationStrategy faultInjectingSessionAuthenticationStrategy() {
            SessionAuthenticationStrategy delegate = new ChangeSessionIdAuthenticationStrategy();
            return (authentication, request, response) -> {
                if (FAIL_SESSION_STRATEGY.get()) {
                    throw new SessionAuthenticationException("Injected failure for atomicity test");
                }
                delegate.onAuthentication(authentication, request, response);
            };
        }
    }
}
