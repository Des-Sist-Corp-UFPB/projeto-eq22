package com.iwrite.demo;

import com.iwrite.book.repository.BookRepository;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The three locks that keep known accounts out of places they must never appear. Pure, so it needs
 * no context: the point is the decision, not the wiring.
 */
class DemoDataSeederTest {

    private static final String PASSWORD = "uma-senha-de-teste";

    @Test
    void refusesToRunWhenAProductionProfileIsActive() {
        for (String profile : new String[] { "prod", "production", "PROD", "Production" }) {
            assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(
                    new String[] { "demo", profile }, PASSWORD, PASSWORD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refuses to run")
                    .hasMessageContaining(profile);
        }
    }

    @Test
    void refusesToRunWithoutBothPasswords() {
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, "", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, "   "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saysWhichVariablesToSetWithoutEchoingAnyValue() {
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, ""))
                .hasMessageContaining("IWRITE_DEMO_AUTOR_A_PASSWORD")
                .hasMessageContaining("IWRITE_DEMO_AUTOR_B_PASSWORD")
                .hasMessageContaining("IWRITE_DEMO_SEED_ENABLED=false")
                // The one password that was supplied must not travel in the failure of the other.
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void acceptsAConfiguredDemoEnvironment() {
        assertThatCode(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, PASSWORD, PASSWORD))
                .doesNotThrowAnyException();
    }

    // Codex P2 (round 8, #149): seedAuthor below calls passwordEncoder.encode directly on either
    // configured password, the same shape of call CredentialProvisioningRunner and PasswordPolicy
    // already guard with BcryptInputPolicy — this seeder had none until this round.

    @Test
    void requireSafeConfigurationAceitaSenhaComExatamente72Bytes() {
        String password = "a1" + "b".repeat(70);
        assertThatCode(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, password, PASSWORD))
                .doesNotThrowAnyException();
    }

    @Test
    void requireSafeConfigurationRecusaSenhaCom73BytesSemEcoarOValor() {
        String password = "a1" + "b".repeat(71);
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, password, PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IWRITE_DEMO_AUTOR_A_PASSWORD")
                .hasMessageContaining("IWRITE_DEMO_AUTOR_B_PASSWORD")
                .hasMessageNotContaining(password);
    }

    @Test
    void requireSafeConfigurationRecusaHighSurrogateIsoladoSemEcoarOValor() {
        String password = "senha-com-surrogate-" + '\uD800';
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, password, PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(password);
    }

    @Test
    void requireSafeConfigurationRecusaLowSurrogateIsoladoSemEcoarOValor() {
        String password = "senha-com-surrogate-" + '\uDC01';
        assertThatThrownBy(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, password, PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(password);
    }

    @Test
    void requireSafeConfigurationAceitaParSurrogateValido() {
        // U+1F600 ("😀") is a valid high+low surrogate pair — must not be mistaken for two lone
        // surrogates, and its 4 UTF-8 bytes still fit comfortably under the 72-byte limit.
        String password = "senha-com-emoji-😀-valida";
        assertThatCode(() -> DemoDataSeeder.requireSafeConfiguration(new String[] { "demo" }, password, PASSWORD))
                .doesNotThrowAnyException();
    }

    // run() must fail on requireSafeConfiguration before ever reaching passwordEncoder.encode or any
    // repository — mirrors CredentialProvisioningRunnerTest's equivalent test for the same finding
    // shape. Proves both "not called" and "no partial entity": nothing can be half-written by a
    // collaborator that was never invoked at all.
    @Test
    void runComSenhaConfiguradaInvalidaNaoChamaEncodeNemRepositorios() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserCredentialRepository credentialRepository = mock(UserCredentialRepository.class);
        TenantMembershipRepository membershipRepository = mock(TenantMembershipRepository.class);
        BookRepository bookRepository = mock(BookRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        String invalidPassword = "senha-com-surrogate-" + '\uD800';
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("demo");

        DemoDataSeeder seeder = new DemoDataSeeder(
                environment,
                tenantRepository,
                userRepository,
                credentialRepository,
                membershipRepository,
                bookRepository,
                passwordEncoder,
                invalidPassword,
                PASSWORD
        );

        assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(tenantRepository, userRepository, credentialRepository, membershipRepository, bookRepository, passwordEncoder);
    }
}
