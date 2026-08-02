package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.context.AuthenticatedCurrentUserProvider;
import com.iwrite.user.context.DevelopmentCurrentUserProvider;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of {@code session -> principal -> membership -> tenant -> authorised query}.
 *
 * <p>Deliberately does not extend {@code PostgresIntegrationTest}: that base pins
 * {@code iwrite.current-user.development.enabled=true} through {@code @DynamicPropertySource},
 * which has the highest precedence and cannot be overridden from a subclass. This suite has to run
 * with the development identity off, which is the only configuration where the authenticated
 * provider exists at all.
 *
 * <p>Filters are real throughout — no {@code addFilters = false} — so every request goes through
 * the session, CSRF and authorization the browser would meet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticatedTenantResolutionIntegrationTest {

    private static final String PASSWORD = "senha-de-demonstracao-A1";

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // The point of this suite: identity comes from the session, never from a configured id.
        registry.add("iwrite.current-user.development.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @Autowired
    private BookRepository bookRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private String emailA;
    private UUID userAId;
    private UUID tenantAId;

    private String emailB;
    private UUID tenantBId;

    @BeforeEach
    void createTwoIndependentAuthors() {
        emailA = "autor-a-" + UUID.randomUUID() + "@iwrite.local";
        tenantAId = createTenant("Espaço do Autor A");
        userAId = createUser(emailA, "Autor A");
        addMembership(userAId, tenantAId);

        emailB = "autor-b-" + UUID.randomUUID() + "@iwrite.local";
        tenantBId = createTenant("Espaço do Autor B");
        addMembership(createUser(emailB, "Autor B"), tenantBId);
    }

    // 1, 16 — the context is resolved from the session, and the development identity is not even
    // registered, so there is no fixed-id fallback that could answer instead.
    @Test
    void singleMembershipResolvesTheTenantAndTheDevelopmentIdentityIsNotRegistered() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(DevelopmentCurrentUserProvider.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(AuthenticatedCurrentUserProvider.class)).isNotEmpty();

        MockHttpSession session = login(emailA);
        UUID bookId = createBook(session, "Manuscrito do Autor A");

        // The tenant was never sent; the server assigned the one behind the membership.
        assertThat(bookRepository.findById(bookId).orElseThrow().getTenant().getId()).isEqualTo(tenantAId);
    }

    // 2, 3 — each session lists only its own tenant.
    @Test
    void eachAuthorListsOnlyBooksOfItsOwnTenant() throws Exception {
        MockHttpSession sessionA = login(emailA);
        UUID bookA = createBook(sessionA, "Manuscrito do Autor A");

        MockHttpSession sessionB = login(emailB);
        UUID bookB = createBook(sessionB, "Manuscrito do Autor B");

        mockMvc.perform(get("/api/books").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookA)).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookB)).isEmpty());

        mockMvc.perform(get("/api/books").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookB)).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookA)).isEmpty());
    }

    // 4, 5 — a real book of another tenant and an id that never existed are answered the same way,
    // so the response cannot be used to test whether a resource exists.
    @Test
    void foreignBookAndUnknownIdAreIndistinguishable() throws Exception {
        MockHttpSession sessionB = login(emailB);
        UUID foreignBookId = createBook(sessionB, "Manuscrito do Autor B");

        MockHttpSession sessionA = login(emailA);
        UUID neverExisted = UUID.randomUUID();

        String foreign = bodyOf(mockMvc.perform(get("/api/books/" + foreignBookId).session(sessionA))
                .andExpect(status().isNotFound()).andReturn());
        String unknown = bodyOf(mockMvc.perform(get("/api/books/" + neverExisted).session(sessionA))
                .andExpect(status().isNotFound()).andReturn());

        // The two answers differ only where each echoes back the id the caller itself supplied.
        assertThat(comparableError(foreign, foreignBookId)).isEqualTo(comparableError(unknown, neverExisted));
        assertThat(foreign).doesNotContain(tenantBId.toString(), emailB, "Autor B");
    }

    // 6 — tenantId in the creation body.
    @Test
    void tenantIdInTheRequestBodyCannotChangeTheEffectiveTenant() throws Exception {
        MockHttpSession sessionA = login(emailA);

        MvcResult created = mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Tentativa com tenant alheio",
                                "tenantId", tenantBId))))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(tenantOfCreatedBook(created)).isEqualTo(tenantAId);
    }

    // 7 — tenantId as a query parameter.
    @Test
    void tenantIdInAQueryParameterCannotChangeTheEffectiveTenant() throws Exception {
        MockHttpSession sessionA = login(emailA);

        MvcResult created = mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .param("tenantId", tenantBId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(titleJson("Tentativa por query param")))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(tenantOfCreatedBook(created)).isEqualTo(tenantAId);

        // Listing is filtered by the resolved tenant too, not by anything the caller asked for.
        mockMvc.perform(get("/api/books").session(sessionA).param("tenantId", tenantBId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Tentativa por query param')]").exists());
    }

    // 8 — tenantId in a custom header.
    @Test
    void tenantIdInACustomHeaderCannotChangeTheEffectiveTenant() throws Exception {
        MockHttpSession sessionA = login(emailA);

        MvcResult created = mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .header("X-Tenant-Id", tenantBId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(titleJson("Tentativa por cabeçalho")))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(tenantOfCreatedBook(created)).isEqualTo(tenantAId);
    }

    // 9 — userId and role in the request cannot replace the authenticated identity.
    @Test
    void userIdAndRoleInTheRequestCannotReplaceTheAuthenticatedIdentity() throws Exception {
        UUID foreignUserId = membershipRepository.findByTenant_IdAndUser_Id(
                tenantBId, ownerOfTenant(tenantBId)).orElseThrow().getUser().getId();

        MockHttpSession sessionA = login(emailA);

        MvcResult created = mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .header("X-User-Id", foreignUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Tentativa de trocar de autor",
                                "userId", foreignUserId,
                                "role", "OWNER"))))
                .andExpect(status().isCreated())
                .andReturn();

        var book = bookRepository.findById(bookIdOf(created)).orElseThrow();
        assertThat(book.getOwner().getId()).isEqualTo(userAId);
        assertThat(book.getTenant().getId()).isEqualTo(tenantAId);
    }

    // 10, 11 — zero and several memberships never reach a resolved tenant, and say so with the same
    // message as a wrong password.
    @Test
    void zeroAndMultipleMembershipsFailSafelyAtLogin() throws Exception {
        String orphanEmail = "sem-workspace-" + UUID.randomUUID() + "@iwrite.local";
        createUser(orphanEmail, "Sem Workspace");

        String ambiguousEmail = "dois-workspaces-" + UUID.randomUUID() + "@iwrite.local";
        UUID ambiguousUserId = createUser(ambiguousEmail, "Dois Workspaces");
        addMembership(ambiguousUserId, createTenant("Workspace 1"));
        addMembership(ambiguousUserId, createTenant("Workspace 2"));

        String orphan = bodyOf(attemptLogin(orphanEmail, PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn());
        String ambiguous = bodyOf(attemptLogin(ambiguousEmail, PASSWORD)
                .andExpect(status().isUnauthorized()).andReturn());
        String wrongPassword = bodyOf(attemptLogin(emailA, "senha-errada")
                .andExpect(status().isUnauthorized()).andReturn());

        assertThat(messagesOf(orphan)).isEqualTo(messagesOf(wrongPassword));
        assertThat(messagesOf(ambiguous)).isEqualTo(messagesOf(wrongPassword));
        // Neither "you have no workspace" nor "you have several" may be inferable.
        assertThat(orphan).doesNotContain("membership", "workspace", "Workspace 1");
    }

    // 12, 13 — the session captured a tenant at login, but it is re-read on every request.
    //
    // Revocation is applied to a second member of tenant A rather than to the author: `books.owner`
    // is a foreign key into `tenant_memberships`, so the membership of someone who owns a
    // manuscript cannot be deleted at all. This is also the sharper case — tenant A's data still
    // exists and the revoked member must simply stop reaching it.
    @Test
    void revokedMembershipStopsBothTheSessionAndTenantScopedData() throws Exception {
        MockHttpSession author = login(emailA);
        createBook(author, "Manuscrito que permanece no tenant A");

        String revokedEmail = "revogado-" + UUID.randomUUID() + "@iwrite.local";
        UUID revokedUserId = createUser(revokedEmail, "Membro Revogado");
        addMembership(revokedUserId, tenantAId);

        MockHttpSession revoked = login(revokedEmail);
        mockMvc.perform(get("/api/auth/me").session(revoked)).andExpect(status().isOk());
        mockMvc.perform(get("/api/books").session(revoked)).andExpect(status().isOk());

        membershipRepository.deleteAll(membershipRepository.findByUser_Id(revokedUserId));
        entityManager.flush();

        mockMvc.perform(get("/api/auth/me").session(revoked))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value(AuthMessages.SESSION_EXPIRED));

        String blocked = bodyOf(mockMvc.perform(get("/api/books").session(revoked))
                .andExpect(status().isUnauthorized()).andReturn());
        assertThat(blocked).doesNotContain("Manuscrito que permanece no tenant A", tenantAId.toString());

        // The author's own session is untouched: revocation is per membership, not per tenant.
        mockMvc.perform(get("/api/books").session(author)).andExpect(status().isOk());
    }

    // 14 — after logout the same session resolves nothing at all.
    @Test
    void logoutStopsCurrentUserAndTenantResolution() throws Exception {
        MockHttpSession sessionA = login(emailA);
        createBook(sessionA, "Manuscrito do Autor A");

        mockMvc.perform(withCsrf(post("/api/auth/logout")).session(sessionA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(sessionA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/books").session(sessionA)).andExpect(status().isUnauthorized());
    }

    // 15 — no session at all is refused before any tenant-scoped query runs.
    @Test
    void absentSessionIsRejectedWithoutTouchingData() throws Exception {
        MockHttpSession sessionA = login(emailA);
        UUID bookId = createBook(sessionA, "Manuscrito do Autor A");

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messages[0]").value(AuthMessages.SESSION_EXPIRED));

        String direct = bodyOf(mockMvc.perform(get("/api/books/" + bookId))
                .andExpect(status().isUnauthorized()).andReturn());
        assertThat(direct).doesNotContain("Manuscrito do Autor A");
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = attemptLogin(email, PASSWORD).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return mockMvc.perform(withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    /** Browser side of the double-submit contract: same value as cookie and as header. */
    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private UUID createBook(MockHttpSession session, String title) throws Exception {
        return bookIdOf(mockMvc.perform(withCsrf(post("/api/books")).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(titleJson(title)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String titleJson(String title) throws Exception {
        return objectMapper.writeValueAsString(Map.of("title", title));
    }

    private UUID bookIdOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID tenantOfCreatedBook(MvcResult result) throws Exception {
        return bookRepository.findById(bookIdOf(result)).orElseThrow().getTenant().getId();
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private String messagesOf(String body) throws Exception {
        return objectMapper.readTree(body).get("messages").toString();
    }

    /** Drops the per-response timestamp and the id the caller supplied, leaving what it can learn. */
    private String comparableError(String body, UUID echoedId) throws Exception {
        ObjectNode error = (ObjectNode) objectMapper.readTree(body);
        error.remove("timestamp");
        return error.toString().replace(echoedId.toString(), "ID");
    }

    private UUID ownerOfTenant(UUID tenantId) {
        return entityManager
                .createQuery("select m.user.id from TenantMembership m where m.tenant.id = :tenantId", UUID.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }

    private UUID createTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("America/Sao_Paulo");
        return tenantRepository.save(tenant).getId();
    }

    private UUID createUser(String email, String displayName) {
        User user = new User();
        user.setDisplayName(displayName);
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
