package com.iwrite.demo;

import com.iwrite.auth.BcryptInputPolicy;
import com.iwrite.auth.PasswordPolicy;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Creates the two independent authors the multi-tenant demonstration needs (#137).
 *
 * <p>Gated twice over: the {@code demo} profile has to be active <em>and</em>
 * {@code iwrite.demo.seed.enabled} has to be true. With either missing the bean does not exist, so
 * a normal boot is untouched — the seed can only appear when someone asked for it in two places.
 *
 * <p>Passwords come from the environment and have no defaults. A default password in a repository
 * is a published password.
 */
@Component
@Profile("demo")
@ConditionalOnProperty(prefix = "iwrite.demo.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final List<String> REFUSED_PROFILES = List.of("prod", "production");

    private final Environment environment;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final TenantMembershipRepository membershipRepository;
    private final BookRepository bookRepository;
    private final PasswordEncoder passwordEncoder;
    private final String autorAPassword;
    private final String autorBPassword;

    public DemoDataSeeder(
            Environment environment,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            TenantMembershipRepository membershipRepository,
            BookRepository bookRepository,
            PasswordEncoder passwordEncoder,
            @Value("${iwrite.demo.seed.autor-a-password:}") String autorAPassword,
            @Value("${iwrite.demo.seed.autor-b-password:}") String autorBPassword
    ) {
        this.environment = environment;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.membershipRepository = membershipRepository;
        this.bookRepository = bookRepository;
        this.passwordEncoder = passwordEncoder;
        this.autorAPassword = autorAPassword;
        this.autorBPassword = autorBPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        requireSafeConfiguration(environment.getActiveProfiles(), autorAPassword, autorBPassword);

        seedAuthor("autor-a@iwrite.local", "Autor A", "Espaço do Autor A", "A Cidade de Vidro", autorAPassword);
        seedAuthor("autor-b@iwrite.local", "Autor B", "Espaço do Autor B", "O Jardim Submerso", autorBPassword);
    }

    /**
     * Refuses to run at all in production, and refuses to run half-configured.
     *
     * <p>The {@code demo} profile already keeps this bean out of a production context; this is the
     * second lock, for the case where someone activates {@code demo} alongside it. Neither message
     * contains a password — they name the variables to set, never their values.
     *
     * <p>Every password is also checked against {@link BcryptInputPolicy} (#149 review, round 8),
     * the same bcrypt-input guard {@code /api/auth/register}, {@code /api/auth/login} and {@link
     * com.iwrite.auth.CredentialProvisioningRunner} already enforce: {@code seedAuthor} below calls
     * {@code passwordEncoder.encode} directly, and without this check a configured password over
     * bcrypt's 72-byte effective limit, or one containing an unpaired surrogate, would still create
     * the user, workspace and credential — just with a hash that silently ignores the bytes past 72,
     * or one where {@code String.getBytes(UTF_8)} substitutes the malformed surrogate instead of
     * rejecting it. Checked before either {@code seedAuthor} call, so before any repository access,
     * hash, or write.
     */
    static void requireSafeConfiguration(String[] activeProfiles, String... passwords) {
        for (String profile : activeProfiles) {
            if (REFUSED_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "Demo seed refuses to run with the '" + profile + "' profile active. "
                                + "It creates known accounts and belongs only to a demonstration environment.");
            }
        }

        for (String password : passwords) {
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "Demo seed is enabled but the demo passwords are not set. "
                                + "Provide IWRITE_DEMO_AUTOR_A_PASSWORD and IWRITE_DEMO_AUTOR_B_PASSWORD, "
                                + "or set IWRITE_DEMO_SEED_ENABLED=false.");
            }
        }

        for (String password : passwords) {
            if (!BcryptInputPolicy.isValid(password, PasswordPolicy.MAX_UTF8_BYTES)) {
                throw new IllegalStateException(
                        "Demo seed is enabled but a configured demo password is not a valid bcrypt input: "
                                + "it either exceeds " + PasswordPolicy.MAX_UTF8_BYTES + " UTF-8 bytes "
                                + "(bcrypt's effective input limit) or contains malformed UTF-16. Configure valid "
                                + "IWRITE_DEMO_AUTOR_A_PASSWORD / IWRITE_DEMO_AUTOR_B_PASSWORD values and retry. "
                                + "This message never echoes the configured value.");
            }
        }
    }

    /**
     * Idempotent by the one thing that is unique per author: the email. Finding the user means the
     * whole set - tenant, credential, membership, book - was created by an earlier run, so a repeat
     * run adds nothing and changes nothing.
     */
    private void seedAuthor(String email, String displayName, String tenantName, String bookTitle, String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            log.info("Demo seed: {} already exists, leaving it untouched", displayName);
            return;
        }

        Tenant tenant = new Tenant();
        tenant.setName(tenantName);
        tenant.setDefaultTimeZoneId("America/Sao_Paulo");
        tenantRepository.save(tenant);

        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId("America/Sao_Paulo");
        userRepository.save(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(password));
        credentialRepository.save(credential);

        // Exactly one membership per demo user: the academic slice resolves the tenant only when
        // there is no ambiguity to resolve.
        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        membershipRepository.save(membership);

        Book book = new Book();
        book.setTenant(tenant);
        book.setOwner(user);
        book.setTitle(bookTitle);
        book.setStatus(BookStatus.WRITING);
        bookRepository.save(book);

        log.info("Demo seed: created {} with workspace '{}' and one book", displayName, tenantName);
    }
}
