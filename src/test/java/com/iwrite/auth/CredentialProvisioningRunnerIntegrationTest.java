package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link CredentialProvisioningRunner} directly against the real schema, the same way
 * {@code DemoScenarioIntegrationTest} drives the demo seeder — this is what an operator's boot
 * with the flag set actually does, end to end through a real login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CredentialProvisioningRunnerIntegrationTest {

    // Deterministic legacy user inserted by V20; the one every pre-V30 installation already has.
    private static final String LEGACY_EMAIL = "carlos.legacy@iwrite.local";
    private static final String PASSWORD = "senha-provisionada-1";

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void isNotRegisteredWhenTheFlagIsAbsent() {
        assertThat(applicationContext.getBeanNamesForType(CredentialProvisioningRunner.class)).isEmpty();
    }

    @Test
    void provisionsTheLegacyUserAndItCanLogIn() throws Exception {
        runner(LEGACY_EMAIL, PASSWORD).run(null);

        login(LEGACY_EMAIL, PASSWORD);
    }

    @Test
    void runningTwiceDoesNotOverwriteTheExistingCredential() throws Exception {
        runner(LEGACY_EMAIL, PASSWORD).run(null);
        UUID userId = userRepository.findByEmail(LEGACY_EMAIL).orElseThrow().getId();
        String firstHash = credentialRepository.findById(userId).orElseThrow().getPasswordHash();

        // A second boot with a different configured password must still leave the first one intact.
        runner(LEGACY_EMAIL, "outra-senha-2").run(null);

        UserCredential unchanged = credentialRepository.findById(userId).orElseThrow();
        assertThat(unchanged.getPasswordHash()).isEqualTo(firstHash);
        login(LEGACY_EMAIL, PASSWORD);
    }

    @Test
    void failsClearlyWhenNoUserMatchesTheConfiguredEmail() {
        assertThatThrownBy(() -> runner("no-such-user@iwrite.local", PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no user matches");
    }

    // Codex P2 (round 6, #149): V32/V33 normalize every stored row (trim + lowercase), but a
    // configured value that still carries the legacy spelling must resolve to that same row —
    // exactly the upgrade scenario the finding names (IWRITE_CREDENTIAL_PROVISIONING_EMAIL set to
    // "Owner@Example.com" against a row already stored as "owner@example.com").

    @Test
    void provisionsUsingMixedCaseConfiguredEmail() throws Exception {
        runner("Carlos.Legacy@IWrite.local", PASSWORD).run(null);

        login(LEGACY_EMAIL, PASSWORD);
    }

    @Test
    void provisionsUsingConfiguredEmailPaddedWithSpacesTabCrAndLf() throws Exception {
        runner(" \t" + LEGACY_EMAIL + "\r\n", PASSWORD).run(null);

        login(LEGACY_EMAIL, PASSWORD);
    }

    // ASCII-only policy (#149 review, see EmailNormalizer): this runner does a real lookup, so a
    // misconfigured non-ASCII value must fail loudly at boot, before ever reaching the repository —
    // not resolve to the wrong row or silently no-op.
    @Test
    void failsClearlyWhenTheConfiguredEmailIsNotAsciiWithoutEchoingIt() {
        String nonAscii = "usuária@iwrite.local";
        assertThatThrownBy(() -> runner(nonAscii, PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_EMAIL")
                .hasMessageNotContaining(nonAscii);
    }

    // Codex P2 (round 6, #149): bcrypt silently ignores UTF-8 bytes past the 72nd; this runner calls
    // passwordEncoder.encode directly, so it needs the same guard RegistrationService now has.
    @Test
    void failsClearlyWhenTheConfiguredPasswordExceedsTheBcryptByteLimitWithoutEchoingIt() {
        String tooLong = "a1" + "b".repeat(71); // 73 UTF-8 bytes
        assertThatThrownBy(() -> runner(LEGACY_EMAIL, tooLong).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_CREDENTIAL_PROVISIONING_PASSWORD")
                .hasMessageNotContaining(tooLong);
    }

    private CredentialProvisioningRunner runner(String email, String password) {
        return new CredentialProvisioningRunner(userRepository, credentialRepository, passwordEncoder, email, password);
    }

    private void login(String email, String password) throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk());
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
