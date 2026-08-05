package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that {@link LoginRateLimiter} is actually wired into {@code POST /api/auth/login}
 * — window-expiry behaviour is already covered deterministically at the unit level in
 * {@link LoginRateLimiterTest}, with an injectable clock and no sleep.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginRateLimitingIntegrationTest {

    private static final String PASSWORD = "senha-correta-1";
    private static final int MAX_ATTEMPTS_PER_ACCOUNT = 3;

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        // Small and deterministic: enough attempts to prove the account dimension trips without a
        // slow test. The origin limit is raised out of the way — the origin is shared by every
        // MockMvc request in this class (and the limiter is a singleton across test methods in a
        // cached context), and this suite is only exercising the account dimension.
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-account", () -> MAX_ATTEMPTS_PER_ACCOUNT);
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.window", () -> "1m");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private String email;

    @BeforeEach
    void createOneAccount() {
        email = "alvo-" + UUID.randomUUID() + "@iwrite.local";
        UUID tenantId = createTenant("Espaço do Alvo");
        UUID userId = createUser(email);
        addMembership(userId, tenantId);
    }

    @Test
    void excederOLimitePorContaResponde429ComMensagemGenerica() throws Exception {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ACCOUNT; attempt++) {
            attemptLogin(email, "senha-errada").andExpect(status().isUnauthorized());
        }

        String body = attemptLogin(email, "senha-errada")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(AuthMessages.TOO_MANY_LOGIN_ATTEMPTS);
        // Never reveals which dimension tripped or that the account exists.
        assertThat(body).doesNotContain("account", "origin", "conta", "origem", email);
    }

    @Test
    void contaAindaDesconhecidaTambemEBloqueadaAposOMesmoNumeroDeTentativas() throws Exception {
        String unknownEmail = "desconhecido-" + UUID.randomUUID() + "@iwrite.local";

        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ACCOUNT; attempt++) {
            attemptLogin(unknownEmail, "senha-qualquer").andExpect(status().isUnauthorized());
        }

        // Identical outward behaviour to a real account under the same load: existence is not leaked.
        attemptLogin(unknownEmail, "senha-qualquer").andExpect(status().isTooManyRequests());
    }

    @Test
    void loginValidoAindaFuncionaAntesDoLimiteSerAtingido() throws Exception {
        attemptLogin(email, "senha-errada").andExpect(status().isUnauthorized());

        attemptLogin(email, PASSWORD).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions attemptLogin(String email, String password) throws Exception {
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

    private UUID createTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("America/Sao_Paulo");
        entityManager.persist(tenant);
        return tenant.getId();
    }

    private UUID createUser(String email) {
        User user = new User();
        user.setDisplayName("Alvo");
        user.setEmail(email);
        user.setTimeZoneId("America/Sao_Paulo");
        entityManager.persist(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entityManager.persist(credential);
        entityManager.flush();
        return user.getId();
    }

    private void addMembership(UUID userId, UUID tenantId) {
        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, tenantId));
        membership.setUser(entityManager.getReference(User.class, userId));
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
    }
}
