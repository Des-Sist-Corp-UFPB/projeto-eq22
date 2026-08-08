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

import java.nio.charset.StandardCharsets;
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

    // Codex P2 (fresh finding, round 7, #149): String.getBytes(UTF_8) — used internally by bcrypt's
    // matches() — silently substitutes an unpaired surrogate with the same replacement byte instead
    // of rejecting it. BcryptInputPolicy, now checked in AuthController#login before
    // AuthenticationManager.authenticate ever runs, must reject this the same way a wrong password
    // is rejected: generic 401, reservation still spent, PasswordEncoder.matches never reached.

    @Test
    void loginComSenhaContendoSurrogateIsoladoEIndistinguivelDeSenhaErrada() throws Exception {
        String malformed = messagesOf(loginWithRawJsonEscapedPassword(email, PASSWORD.substring(0, 5) + "\\ud800")
                .andExpect(status().isUnauthorized())
                .andReturn());
        String wrongPassword = messagesOf(login(email, "senha-completamente-errada")
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(malformed).isEqualTo(wrongPassword);
        assertThat(malformed).contains(AuthMessages.INVALID_CREDENTIALS);
    }

    /**
     * The exact collision BcryptInputPolicy exists to close: {@code String.getBytes(UTF_8)}'s
     * default substitution replaces an unpaired surrogate with the same byte as a literal {@code
     * '?'}, so an account whose real password contains one could previously be authenticated by an
     * entirely different, malformed attempt that happened to encode to the same bytes.
     */
    @Test
    void senhaValidaComInterrogacaoNaoEAutenticadaPorTentativaComSurrogateIsolado() throws Exception {
        String realPasswordWithQuestionMark = "abcdefgh1?";

        String questionMarkEmail = "com-interrogacao-" + UUID.randomUUID() + "@iwrite.local";
        UUID questionMarkUserId = createUser(questionMarkEmail, "Com Interrogação", realPasswordWithQuestionMark);
        addMembership(questionMarkUserId, createTenant("Espaço Com Interrogação").getId());

        // "abcdefgh1" + a lone high surrogate: String.getBytes(UTF_8) would substitute the surrogate
        // with the same byte as a literal '?', so this must NOT authenticate against the real
        // "abcdefgh1?" password above.
        loginWithRawJsonEscapedPassword(questionMarkEmail, "abcdefgh1\\ud800").andExpect(status().isUnauthorized());
        login(questionMarkEmail, realPasswordWithQuestionMark).andExpect(status().isOk());
    }

    /**
     * Registration (round 6) already refuses to create an account whose real password exceeds 72
     * UTF-8 bytes, but nothing previously stopped a login attempt itself from submitting more than
     * 72 bytes that merely share their first 72 with a real, shorter-or-equal stored password —
     * bcrypt truncates internally and would match on that shared prefix regardless of what follows.
     * Login must recuse outright, past this limit, not silently accept the truncation.
     */
    @Test
    void loginComPrefixoValidoDe72BytesMaisSufixoERecusadoNaoAutenticado() throws Exception {
        String realPassword72Bytes = "a1" + "b".repeat(70);
        assertThat(realPassword72Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        String prefixEmail = "prefixo-72-" + UUID.randomUUID() + "@iwrite.local";
        UUID prefixUserId = createUser(prefixEmail, "Prefixo 72", realPassword72Bytes);
        addMembership(prefixUserId, createTenant("Espaço Prefixo 72").getId());

        String attemptWithSuffix = realPassword72Bytes + "X";
        login(prefixEmail, attemptWithSuffix).andExpect(status().isUnauthorized());
        login(prefixEmail, realPassword72Bytes).andExpect(status().isOk());
    }

    // #149 review, round 9: U+212A KELVIN SIGN lowercases to plain ASCII 'k' under Locale.ROOT.
    // EmailNormalizer.normalize used to lowercase before checking isAscii, so a login attempt using
    // this code point in place of an ordinary 'k' would resolve to the exact same lookup key as the
    // real, ASCII-only account — indistinguishable, at the database, from actually knowing that
    // account's real email. The fixed order must never authenticate it: same generic failure as any
    // other wrong email, and the real account itself must keep logging in normally afterward.
    @Test
    void loginComKelvinSignNoLugarDeKNuncaAutenticaAContaAsciiEquivalente() throws Exception {
        String kelvinTargetEmail = "kelvin-" + UUID.randomUUID() + "@k.example";
        UUID kelvinUserId = createUser(kelvinTargetEmail, "Alvo Kelvin", PASSWORD);
        addMembership(kelvinUserId, createTenant("Espaço Alvo Kelvin").getId());

        String withKelvinSign = kelvinTargetEmail.replaceFirst("@k\\.", "@K.");
        assertThat(withKelvinSign).isNotEqualTo(kelvinTargetEmail);

        String kelvinAttempt = messagesOf(login(withKelvinSign, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn());
        String wrongPassword = messagesOf(login(kelvinTargetEmail, "senha-completamente-errada")
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(kelvinAttempt).isEqualTo(wrongPassword);
        assertThat(kelvinAttempt).contains(AuthMessages.INVALID_CREDENTIALS);
        // The real account is unaffected and still logs in normally with its own, real address.
        login(kelvinTargetEmail, PASSWORD).andExpect(status().isOk());
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

    /**
     * Sends a password containing a lone (unpaired) surrogate as a raw {@code \\uD800}-style JSON
     * escape, not as an actual Java {@code char} embedded in the request body string. Embedding the
     * real character and going through {@code objectMapper.writeValueAsString} +
     * {@code MockHttpServletRequestBuilder.content(String)} silently collapses it: that path itself
     * encodes the body via {@code String.getBytes()}, which — exactly like the vulnerability this
     * suite tests — substitutes a lone surrogate with {@code '?'} before the request ever reaches
     * the server, turning a would-be malformed attempt into a real, matching password by accident.
     * A {@code \\uD800} escape inside otherwise-plain-ASCII JSON text has no such loss: every byte
     * of the request body is plain ASCII, and the server's own JSON parser is what turns the escape
     * into the lone surrogate code unit, so the malformed value actually reaches
     * {@code AuthController#login}.
     */
    private org.springframework.test.web.servlet.ResultActions loginWithRawJsonEscapedPassword(
            String loginEmail, String jsonEscapedPassword) throws Exception {
        String body = "{\"email\":\"" + loginEmail + "\",\"password\":\"" + jsonEscapedPassword + "\"}";
        return mockMvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
