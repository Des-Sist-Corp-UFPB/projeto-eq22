package com.iwrite.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * The demonstration of #137, run as a test: the seeded pair of authors, then every step of the
 * five-minute script against the real filter chain — real session, real CSRF, no disabled filters.
 *
 * <p>Drives {@link DemoDataSeeder} directly instead of activating the {@code demo} profile. The
 * seeder is what the demo environment runs, and building it here lets the same test prove that
 * running it twice changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DemoScenarioIntegrationTest {

    private static final String PASSWORD_A = "senha-de-demonstracao-A1";
    private static final String PASSWORD_B = "senha-de-demonstracao-B2";
    private static final String EMAIL_A = "autor-a@iwrite.local";
    private static final String EMAIL_B = "autor-b@iwrite.local";
    private static final String BOOK_A = "A Cidade de Vidro";
    private static final String BOOK_B = "O Jardim Submerso";

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // The demonstration profile: identity comes from the session, never from a configured id.
        registry.add("iwrite.current-user.development.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private DemoDataSeeder seeder;

    @BeforeEach
    void seedTheDemonstration() {
        seeder = new DemoDataSeeder(
                new MockEnvironment(),
                tenantRepository,
                userRepository,
                credentialRepository,
                membershipRepository,
                bookRepository,
                passwordEncoder,
                PASSWORD_A,
                PASSWORD_B
        );
        seeder.run(null);
    }

    @Test
    void runningTheSeedAgainChangesNothing() throws Exception {
        UUID userA = userRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        long tenants = tenantRepository.count();
        long books = bookRepository.count();

        seeder.run(null);
        seeder.run(null);

        assertThat(userRepository.findByEmail(EMAIL_A).orElseThrow().getId()).isEqualTo(userA);
        assertThat(tenantRepository.count()).isEqualTo(tenants);
        assertThat(bookRepository.count()).isEqualTo(books);
        assertThat(membershipRepository.findByUser_Id(userA)).hasSize(1);

        // And the credential still authenticates: a repeat run must not have re-hashed anything.
        login(EMAIL_A, PASSWORD_A);
    }

    @Test
    void theSeedIsNotRegisteredWhenTheDemoProfileAndFlagAreAbsent() {
        assertThat(applicationContext.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
    }

    // Script steps 1-3: each author logs in and sees their own name and workspace.
    @Test
    void eachAuthorLogsInAndSeesTheirOwnWorkspace() throws Exception {
        MockHttpSession sessionA = login(EMAIL_A, PASSWORD_A);
        mockMvc.perform(get("/api/auth/me").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Autor A"))
                .andExpect(jsonPath("$.activeWorkspace.name").value("Espaço do Autor A"));

        MockHttpSession sessionB = login(EMAIL_B, PASSWORD_B);
        mockMvc.perform(get("/api/auth/me").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Autor B"))
                .andExpect(jsonPath("$.activeWorkspace.name").value("Espaço do Autor B"));
    }

    // Script step 4: each library holds only its own tenant's book.
    @Test
    void eachLibraryListsOnlyItsOwnTenant() throws Exception {
        mockMvc.perform(get("/api/books").session(login(EMAIL_A, PASSWORD_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == '%s')]".formatted(BOOK_A)).exists())
                .andExpect(jsonPath("$[?(@.title == '%s')]".formatted(BOOK_B)).isEmpty());

        mockMvc.perform(get("/api/books").session(login(EMAIL_B, PASSWORD_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == '%s')]".formatted(BOOK_B)).exists())
                .andExpect(jsonPath("$[?(@.title == '%s')]".formatted(BOOK_A)).isEmpty());
    }

    // Script steps 5-6: the id of B's book, used from A's session, is a 404 indistinguishable from
    // an id that never existed.
    @Test
    void authorAGetsTheSameNotFoundForAuthorsBookAndForNothingAtAll() throws Exception {
        UUID foreignBookId = bookIdByTitle(BOOK_B);
        UUID neverExisted = UUID.randomUUID();
        MockHttpSession sessionA = login(EMAIL_A, PASSWORD_A);

        String foreign = bodyOf(mockMvc.perform(get("/api/books/" + foreignBookId).session(sessionA))
                .andExpect(status().isNotFound()).andReturn());
        String unknown = bodyOf(mockMvc.perform(get("/api/books/" + neverExisted).session(sessionA))
                .andExpect(status().isNotFound()).andReturn());

        assertThat(comparableError(foreign, foreignBookId)).isEqualTo(comparableError(unknown, neverExisted));
        assertThat(foreign).doesNotContain(BOOK_B, "Autor B", "Espaço do Autor B");
    }

    // Script step 7: tenant B's id offered in the body, the query string and a header, all ignored.
    @Test
    void tenantIdFromTheClientNeverMovesTheEffectiveTenant() throws Exception {
        UUID tenantA = tenantIdByName("Espaço do Autor A");
        UUID tenantB = tenantIdByName("Espaço do Autor B");
        MockHttpSession sessionA = login(EMAIL_A, PASSWORD_A);

        MvcResult created = mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .param("tenantId", tenantB.toString())
                        .header("X-Tenant-Id", tenantB.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Livro criado na demonstração",
                                "tenantId", tenantB))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID createdId = UUID.fromString(objectMapper.readTree(bodyOf(created)).get("id").asText());
        assertThat(bookRepository.findById(createdId).orElseThrow().getTenant().getId()).isEqualTo(tenantA);

        // And it did not appear in author B's library.
        mockMvc.perform(get("/api/books").session(login(EMAIL_B, PASSWORD_B)))
                .andExpect(jsonPath("$[?(@.title == 'Livro criado na demonstração')]").isEmpty());
    }

    // Script step 8: reload restores the session; logout ends it and everything after is a 401.
    @Test
    void theSessionSurvivesAReloadAndIsGoneAfterLogout() throws Exception {
        MockHttpSession sessionA = login(EMAIL_A, PASSWORD_A);

        // A reload is exactly this: a fresh request carrying only the session cookie.
        mockMvc.perform(get("/api/auth/me").session(sessionA)).andExpect(status().isOk());
        mockMvc.perform(get("/api/books").session(sessionA)).andExpect(status().isOk());

        mockMvc.perform(withCsrf(post("/api/auth/logout")).session(sessionA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(sessionA)).andExpect(status().isUnauthorized());
        String blocked = bodyOf(mockMvc.perform(get("/api/books").session(sessionA))
                .andExpect(status().isUnauthorized()).andReturn());
        assertThat(blocked).doesNotContain(BOOK_A);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
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

    private UUID bookIdByTitle(String title) {
        return bookRepository.findAll().stream()
                .filter(book -> title.equals(book.getTitle()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private UUID tenantIdByName(String name) {
        return tenantRepository.findAll().stream()
                .filter(tenant -> name.equals(tenant.getName()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private String comparableError(String body, UUID echoedId) throws Exception {
        ObjectNode error = (ObjectNode) objectMapper.readTree(body);
        error.remove("timestamp");
        return error.toString().replace(echoedId.toString(), "ID");
    }
}
