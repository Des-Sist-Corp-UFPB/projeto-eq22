package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserPersonaRepository;
import com.iwrite.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of {@code POST /api/auth/register} through the real Spring Security filter
 * chain (real session, real CSRF), against a real PostgreSQL database. Deliberately not
 * {@code @Transactional}: several tests here need to observe a truly committed (or truly rolled
 * back) state from a second, independent read — wrapping the whole test method in one shared
 * transaction (as {@code PostgresIntegrationTest}/{@code AuthenticationIntegrationTest} do) would
 * make every write visible to later reads in the same test regardless of whether the register
 * transaction itself actually committed, which is exactly what the rollback tests need to tell apart.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationIntegrationTest {

    private static final String VALID_PASSWORD = "senha-valida-1";

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        // Generous on purpose: this suite exercises the registration flow itself, not its rate
        // limiter (see RegistrationRateLimitingIntegrationTest for that, with its own tiny context).
        registry.add("iwrite.auth.registration-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-account", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
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

    @Test
    void cadastroCompletoCriaTodasAsEntidadesEUmaSessaoValida() throws Exception {
        String email = uniqueEmail();

        MvcResult result = register(email, "Nova Autora", "WRITER", "America/Sao_Paulo")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Nova Autora"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.activeWorkspace.role").value("OWNER"))
                // The browser is never told an id, exactly like /api/auth/login.
                .andExpect(jsonPath("$.user.id").doesNotExist())
                .andExpect(jsonPath("$.activeWorkspace.id").doesNotExist())
                .andReturn();

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(credentialRepository.findById(user.getId())).isPresent();
        List<TenantMembership> memberships = membershipRepository.findByUser_Id(user.getId());
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).getRole().name()).isEqualTo("OWNER");
        UUID tenantId = memberships.get(0).getTenant().getId();
        assertThat(tenantRepository.findById(tenantId)).hasValueSatisfying(tenant -> assertThat(tenant.getName()).isNotBlank());

        String storedHash = credentialRepository.findById(user.getId()).orElseThrow().getPasswordHash();
        assertThat(storedHash).isNotEqualTo(VALID_PASSWORD).doesNotContain(VALID_PASSWORD);
        assertThat(storedHash).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches(VALID_PASSWORD, storedHash)).isTrue();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void registraAPersonaPrincipalUmaUnicaVez() throws Exception {
        String email = uniqueEmail();
        register(email, "Nova Editora", "EDITOR", "America/Sao_Paulo").andExpect(status().isOk());

        User user = userRepository.findByEmail(email).orElseThrow();
        List<UserPersona> personas = personaRepository.findAll().stream()
                .filter(p -> p.getUserId().equals(user.getId()))
                .toList();

        assertThat(personas).hasSize(1);
        assertThat(personas.get(0).getPersona()).isEqualTo(UserPersonaType.EDITOR);
        assertThat(personas.get(0).isPrimary()).isTrue();
    }

    @Test
    void confirmacaoDeSenhaDivergenteRetorna400() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, "outra-senha-1", "WRITER", "America/Sao_Paulo");

        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void senhaForaDaPoliticaRetorna400SemCriarNada() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = registerBody(email, "Alguém", "curta1", "curta1", "WRITER", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.WEAK_PASSWORD);
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void personaInvalidaRetorna400SemCriarNada() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "PROTAGONISTA", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.INVALID_PERSONA);
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void timezoneInvalidoRetorna400SemCriarNada() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "nao-e-um-fuso");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.INVALID_TIME_ZONE);
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void cadastroExigeCsrfValido() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByEmail(email)).isEmpty();

        // And the rejection really was CSRF, not the payload: the same body succeeds with a token.
        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void emailDuplicadoSequencialRetorna409SemQuebrarOPrimeiroCadastro() throws Exception {
        String email = uniqueEmail();
        register(email, "Primeira", "WRITER", "America/Sao_Paulo").andExpect(status().isOk());

        // Same address, different case and padding: the duplicate check must see through that too.
        String bodyResponse = register(" " + email.toUpperCase() + " ", "Segunda", "EDITOR", "America/Sao_Paulo")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(bodyResponse).contains(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        assertThat(userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).count()).isEqualTo(1);
    }

    @Test
    void duasRequisicoesConcorrentesComOMesmoEmailProduzemUmaUnicaContaSemLinhaParcial() throws Exception {
        String email = uniqueEmail();
        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger otherCount = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < racers; i++) {
                String displayName = "Corredora " + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        int status = register(email, displayName, "WRITER", "America/Sao_Paulo")
                                .andReturn().getResponse().getStatus();
                        if (status == 200) {
                            okCount.incrementAndGet();
                        } else if (status == 409) {
                            conflictCount.incrementAndGet();
                        } else {
                            otherCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        otherCount.incrementAndGet();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(otherCount.get()).as("no 500s or unexpected statuses").isZero();
        assertThat(okCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        List<User> matches = userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).toList();
        assertThat(matches).hasSize(1);
        User survivor = matches.get(0);
        assertThat(credentialRepository.findById(survivor.getId())).isPresent();
        assertThat(membershipRepository.findByUser_Id(survivor.getId())).hasSize(1);
        long personaRows = personaRepository.findAll().stream().filter(p -> p.getUserId().equals(survivor.getId())).count();
        assertThat(personaRows).isEqualTo(1);
    }

    @Test
    void normalizacaoDeEmailECompartilhadaEntreCadastroELogin() throws Exception {
        String email = uniqueEmail();
        register(" " + email.toUpperCase() + " ", "Alguém", "WRITER", "America/Sao_Paulo")
                .andExpect(status().isOk());

        Map<String, Object> loginBody = Map.of("email", email.toUpperCase(), "password", VALID_PASSWORD);
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void tenantIdUserIdERoleEnviadosPeloClienteSaoIgnorados() throws Exception {
        String email = uniqueEmail();
        Map<String, Object> body = new LinkedHashMap<>(registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo"));
        UUID foreignTenantId = UUID.randomUUID();
        UUID foreignUserId = UUID.randomUUID();
        body.put("tenantId", foreignTenantId.toString());
        body.put("userId", foreignUserId.toString());
        body.put("role", "ADMIN");

        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeWorkspace.role").value("OWNER"));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getId()).isNotEqualTo(foreignUserId);
        TenantMembership membership = membershipRepository.findByUser_Id(user.getId()).get(0);
        assertThat(membership.getTenant().getId()).isNotEqualTo(foreignTenantId);
        assertThat(tenantRepository.findById(foreignTenantId)).isEmpty();
    }

    @Test
    void novoTenantEIsoladoDeOutrosUsuarios() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        MvcResult resultA = register(emailA, "Autora A", "WRITER", "America/Sao_Paulo").andExpect(status().isOk()).andReturn();
        MvcResult resultB = register(emailB, "Autora B", "WRITER", "America/Sao_Paulo").andExpect(status().isOk()).andReturn();

        MockHttpSession sessionA = (MockHttpSession) resultA.getRequest().getSession(false);
        MockHttpSession sessionB = (MockHttpSession) resultB.getRequest().getSession(false);

        String createBook = objectMapper.writeValueAsString(Map.of("title", "Livro exclusivo de A " + UUID.randomUUID()));
        mockMvc.perform(withCsrf(post("/api/books")).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBook))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/books").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // B's own, brand-new tenant has none of A's books.
        mockMvc.perform(get("/api/books").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void personaNaoAlteraAcessoAOsPropriosLivros() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email, "Leitora Beta", "BETA_READER", "America/Sao_Paulo")
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

        String createBook = objectMapper.writeValueAsString(Map.of("title", "Livro da Beta Reader " + UUID.randomUUID()));
        mockMvc.perform(withCsrf(post("/api/books")).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBook))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/books").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void displayNameCom255CaracteresEAceitoETenantNomeadoDerivadoRespeitaOLimite() throws Exception {
        String email = uniqueEmail();
        String displayName = "A".repeat(255);
        Map<String, Object> body = registerBody(email, displayName, VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value(displayName));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getDisplayName()).hasSize(255);
        UUID tenantId = membershipRepository.findByUser_Id(user.getId()).get(0).getTenant().getId();
        String tenantName = tenantRepository.findById(tenantId).orElseThrow().getName();
        assertThat(tenantName.codePointCount(0, tenantName.length())).isLessThanOrEqualTo(255);
    }

    @Test
    void displayNameCom256CaracteresRetorna400SemCriarNada() throws Exception {
        String email = uniqueEmail();
        String displayName = "A".repeat(256);
        Map<String, Object> body = registerBody(email, displayName, VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.DISPLAY_NAME_TOO_LONG);
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void email255CaracteresEAceito() throws Exception {
        String email = emailOfExactLength(255);
        assertThat(email).hasSize(255);
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail(email)).isPresent();
    }

    @Test
    void email256CaracteresRetorna400NuncaConflitoSemCriarNada() throws Exception {
        String email = emailOfExactLength(256);
        assertThat(email).hasSize(256);
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.EMAIL_TOO_LONG);
        assertThat(userRepository.findByEmail(EmailNormalizer.normalize(email))).isEmpty();
    }

    // Codex P3 (round 5): an embedded NUL is neither \s nor stripped by trim(), so without a
    // control-character check it reaches the users insert, which Postgres rejects as an invalid
    // varchar value — the finding was that this surfaced as a generic 500, not the 400 a bad
    // request should produce. Built via (char) rather than a source-level escape.

    @Test
    void emailComNulEmbutidoRetorna400NuncaConflitoOuErroInternoSemCriarNada() throws Exception {
        String email = "a" + (char) 0x0000 + uniqueEmail();
        long usersBefore = userRepository.count();
        Map<String, Object> body = registerBody(email, "Alguém", VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.INVALID_EMAIL);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void displayNameComNulEmbutidoRetorna400NuncaErroInternoSemCriarNada() throws Exception {
        String email = uniqueEmail();
        String displayName = "An" + (char) 0x0000 + "a";
        Map<String, Object> body = registerBody(email, displayName, VALID_PASSWORD, VALID_PASSWORD, "WRITER", "America/Sao_Paulo");

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).contains(RegistrationMessages.INVALID_DISPLAY_NAME);
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void usuarioLegadoEPersonaDeBackfillPermanecemIntactos() {
        Optional<User> legacyUser = userRepository.findByEmail("carlos.legacy@iwrite.local");
        assertThat(legacyUser).isPresent();

        List<UserPersona> personas = personaRepository.findAll().stream()
                .filter(p -> p.getUserId().equals(legacyUser.get().getId()))
                .toList();
        assertThat(personas).hasSize(1);
        assertThat(personas.get(0).getPersona()).isEqualTo(UserPersonaType.WRITER);
        assertThat(personas.get(0).isPrimary()).isTrue();
    }

    private ResultActions register(String email, String displayName, String persona, String timeZone) throws Exception {
        Map<String, Object> body = registerBody(email, displayName, VALID_PASSWORD, VALID_PASSWORD, persona, timeZone);
        return mockMvc.perform(withCsrf(post("/api/auth/register"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
        return "registro-" + UUID.randomUUID() + "@iwrite.local";
    }

    /** A unique, syntactically valid email of exactly {@code totalLength} characters. */
    private String emailOfExactLength(int totalLength) {
        String domain = "@iwrite.local";
        int localLength = totalLength - domain.length();
        String unique = UUID.randomUUID().toString().replace("-", "");
        String local = unique + "a".repeat(localLength - unique.length());
        return local + domain;
    }
}
