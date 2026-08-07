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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that {@link RegistrationRateLimiter} is wired into {@code POST /api/auth/register}
 * and answers with its own, registration-specific 429 contract — never the login one. Capacity and
 * concurrency for the underlying engine are covered deterministically at the unit level in
 * {@link RegistrationRateLimiterTest}, with an injectable clock and no sleep.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationRateLimitingIntegrationTest {

    private static final int MAX_ATTEMPTS_PER_ORIGIN = 3;

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        registry.add("iwrite.auth.registration-rate-limit.max-attempts-per-origin", () -> MAX_ATTEMPTS_PER_ORIGIN);
        registry.add("iwrite.auth.registration-rate-limit.window", () -> "1m");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void excederOOrcamentoDeOrigemResponde429ComMensagemDeCadastro() throws Exception {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ORIGIN; attempt++) {
            // Weak password on purpose: a 400 still spends the origin budget (checked before any
            // expensive work), and this loop only needs to spend the budget, not succeed.
            attemptRegister("fraca").andExpect(status().isBadRequest());
        }

        String body = attemptRegister("outra-fraca")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(RegistrationMessages.TOO_MANY_REGISTRATION_ATTEMPTS);
        // Never the login message, and never a hint of which budget or account tripped.
        assertThat(body).doesNotContain(AuthMessages.TOO_MANY_LOGIN_ATTEMPTS, "origin", "origem");
    }

    private ResultActions attemptRegister(String weakPassword) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", "Alguém");
        body.put("email", "rate-limit-" + UUID.randomUUID() + "@iwrite.local");
        body.put("password", weakPassword);
        body.put("passwordConfirmation", weakPassword);
        body.put("primaryPersona", "WRITER");
        body.put("timeZone", "America/Sao_Paulo");

        return mockMvc.perform(withCsrf(post("/api/auth/register"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
