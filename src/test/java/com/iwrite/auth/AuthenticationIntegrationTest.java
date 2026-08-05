package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.repository.UserCredentialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real Spring Security filter chain: no disabled filters, real session, real CSRF.
 * The CSRF token is fetched from {@code /api/auth/csrf} and echoed back exactly as a browser
 * would, so the double-submit contract itself is under test rather than simulated.
 */
@AutoConfigureMockMvc
class AuthenticationIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "senha-de-demonstracao-A1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private String email;
    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void createAuthorWithSingleMembership() {
        email = "autor-" + UUID.randomUUID() + "@iwrite.local";
        tenantId = createTenant("Espaço do Autor A").getId();
        userId = createUser(email, "Autor A", PASSWORD);
        addMembership(userId, tenantId);
    }

    @Test
    void validCredentialsCreateSessionThatSurvivesLaterRequests() throws Exception {
        MvcResult login = login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Autor A"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.activeWorkspace.name").value("Espaço do Autor A"))
                .andExpect(jsonPath("$.activeWorkspace.role").value("OWNER"))
                // The browser is never told which user or tenant it is; it cannot echo one back.
                .andExpect(jsonPath("$.user.id").doesNotExist())
                .andExpect(jsonPath("$.activeWorkspace.id").doesNotExist())
                .andReturn();

        MockHttpSession session = sessionOf(login);
        assertThat(session).isNotNull();

        // Restoration: a brand new request carrying only the session cookie is enough.
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.activeWorkspace.name").value("Espaço do Autor A"));
    }

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() throws Exception {
        String unknownEmail = messagesOf(login("nao-existe@iwrite.local", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn());

        String wrongPassword = messagesOf(login(email, "senha-errada")
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(unknownEmail).contains(AuthMessages.INVALID_CREDENTIALS);
        // Nothing about the account, the lookup or the failure cause may surface.
        assertThat(unknownEmail).doesNotContain(email, "User", "password", "membership");
    }

    @Test
    void missingAndAmbiguousMembershipFailWithTheSameMessageAsWrongCredentials() throws Exception {
        String noMembershipEmail = "sem-workspace@iwrite.local";
        addCredentialedUser(noMembershipEmail);

        String ambiguousEmail = "dois-workspaces@iwrite.local";
        UUID ambiguousUserId = addCredentialedUser(ambiguousEmail);
        addMembership(ambiguousUserId, createTenant("Workspace 1").getId());
        addMembership(ambiguousUserId, createTenant("Workspace 2").getId());

        String noMembership = messagesOf(login(noMembershipEmail, PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn());
        String ambiguous = messagesOf(login(ambiguousEmail, PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn());
        String wrongPassword = messagesOf(login(email, "senha-errada")
                .andExpect(status().isUnauthorized()).andReturn());

        assertThat(noMembership).isEqualTo(wrongPassword);
        assertThat(ambiguous).isEqualTo(wrongPassword);
    }

    @Test
    void loginIsRejectedWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(email, PASSWORD)))
                .andExpect(status().isForbidden());

        // And the rejection really was CSRF, not credentials: the same body succeeds with a token.
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void csrfTokenIsAvailableWithoutASession() throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");

        assertThat(token).isNotNull();
        assertThat(token.getValue()).isNotBlank();
        // The SPA has to read this one to echo it back, so it is intentionally not HttpOnly.
        assertThat(token.isHttpOnly()).isFalse();
    }

    @Test
    void protectedApisRequireASessionAndPingStaysPublic() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value(AuthMessages.SESSION_EXPIRED));

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/ping")).andExpect(status().isOk());
    }

    /**
     * The MCP transport is exempted from authentication only while the MCP server is enabled.
     * This suite runs with the default (disabled), so the exemption must not apply — otherwise the
     * carve-out would be an unconditional hole in every deployment.
     */
    @Test
    void mcpTransportIsNotExemptedWhileTheMcpServerIsDisabled() throws Exception {
        // Authentication still applies to the SSE channel.
        mockMvc.perform(get("/sse")).andExpect(status().isUnauthorized());
        // And CSRF still applies to the message channel: the CsrfFilter runs ahead of
        // authentication, so an un-tokened POST is refused at 403 rather than reaching a handler.
        mockMvc.perform(post("/mcp/message")).andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = sessionOf(login(email, PASSWORD).andExpect(status().isOk()).andReturn());

        mockMvc.perform(withCsrf(post("/api/auth/logout")).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/books").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedMembershipImmediatelyStopsAuthorisingAnOpenSession() throws Exception {
        MockHttpSession session = sessionOf(login(email, PASSWORD).andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        membershipRepository.deleteAll(membershipRepository.findByUser_Id(userId));
        entityManager.flush();

        // /api/auth/me must re-read the membership rather than replay what the session captured.
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value(AuthMessages.SESSION_EXPIRED));
    }

    @Test
    void passwordIsStoredOnlyAsAnAdaptiveHash() {
        String storedHash = credentialRepository.findById(userId).orElseThrow().getPasswordHash();

        assertThat(storedHash).isNotEqualTo(PASSWORD);
        assertThat(storedHash).doesNotContain(PASSWORD);
        assertThat(storedHash).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(PASSWORD, storedHash)).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions login(String loginEmail, String password)
            throws Exception {
        return mockMvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(loginEmail, password)));
    }

    /** Performs the browser side of the double-submit contract: cookie in, same value as header. */
    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private String credentialsJson(String loginEmail, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", loginEmail, "password", password));
    }

    private String messagesOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("messages").toString();
    }

    private MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Tenant createTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("America/Sao_Paulo");
        return tenantRepository.save(tenant);
    }

    private UUID addCredentialedUser(String userEmail) {
        return createUser(userEmail, userEmail, PASSWORD);
    }

    private UUID createUser(String userEmail, String displayName, String rawPassword) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(userEmail);
        user.setTimeZoneId("America/Sao_Paulo");
        entityManager.persist(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(rawPassword));
        entityManager.persist(credential);
        entityManager.flush();
        return user.getId();
    }

    private void addMembership(UUID memberId, UUID memberTenantId) {
        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, memberTenantId));
        membership.setUser(entityManager.getReference(User.class, memberId));
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
    }
}
