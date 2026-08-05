package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.TestDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the fail-closed capacity policy through the real filter chain — window-expiry
 * and per-dimension throttling are already covered deterministically at the unit level in
 * {@link LoginRateLimiterTest}; {@link LoginRateLimitingIntegrationTest} covers ordinary account
 * exhaustion through {@code POST /api/auth/login}. This suite is the one place the account
 * dimension's {@code max-tracked-keys} is deliberately tiny, so it can observe what a genuinely
 * saturated map does to a brand-new login — a separate Spring context from the other two, since the
 * property differs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginRateLimiterCapacityIntegrationTest {

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-account", () -> "100");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-origin", () -> "1000");
        // The account dimension can hold exactly one tracked key. Every MockMvc request in this
        // class shares the same origin, so the origin dimension only ever tracks that one key too —
        // this ceiling is only ever exercised on purpose, by the account dimension below.
        registry.add("iwrite.auth.login-rate-limit.max-tracked-keys", () -> "1");
        registry.add("iwrite.auth.login-rate-limit.window", () -> "1m");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contaNovaERecusadaPorCapacidadeAntesDeAutenticarSemRevelarOMotivo() throws Exception {
        // Fills the account dimension's single tracked-key slot. Neither account below was ever
        // created, so if authentication actually ran for either, DaoAuthenticationProvider's
        // hidden-user-not-found dummy hash would answer 401, not 429 — the outcome asserted next is
        // only possible if capacity refused the request before that ever happened.
        attemptLogin("primeira-" + UUID.randomUUID() + "@iwrite.local", "qualquer")
                .andExpect(status().isUnauthorized());

        String body = attemptLogin("segunda-" + UUID.randomUUID() + "@iwrite.local", "qualquer")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(AuthMessages.TOO_MANY_LOGIN_ATTEMPTS);
        // The same generic outward message as an exhausted account or a busy origin — a capacity
        // refusal is not distinguishable from either dimension's own throttling.
        assertThat(body).doesNotContain("account", "origin", "capacity", "conta", "origem", "capacidade");
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return mockMvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
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
}
