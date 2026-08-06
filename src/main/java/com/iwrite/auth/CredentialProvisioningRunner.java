package com.iwrite.auth;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Provisions the login credential for one already-existing user, on demand.
 *
 * <p>Serves two situations with the same mechanism: unlocking the documented local development
 * workflow (the fixed legacy user created by V20 has no credential after V30 started requiring
 * one) and rolling an existing pre-V30 installation forward, where no account can complete
 * {@code /api/auth/login} yet. Point it at any email that already has a {@code users} row and it
 * produces exactly the one credential asked for — it never creates a user, and there is no HTTP
 * endpoint that does this, only a boot-time flag.
 *
 * <p>Off by default. A single boot with the flag and both values set is the whole procedure; the
 * three variables should be removed from the environment right after so the password does not
 * linger in the process configuration.
 */
@Component
@ConditionalOnProperty(prefix = "iwrite.auth.credential-provisioning", name = "enabled", havingValue = "true")
public class CredentialProvisioningRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialProvisioningRunner.class);

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public CredentialProvisioningRunner(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            @Value("${iwrite.auth.credential-provisioning.email:}") String email,
            @Value("${iwrite.auth.credential-provisioning.password:}") String password
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        requireBothValuesSet(email, password);
        // Normalized the same way every other lookup/write path is (EmailNormalizer, shared with
        // /api/auth/register and /api/auth/login): a configured value that differs only in case or
        // padding from the stored, already-normalized row must still resolve to it (#149 review).
        String normalizedEmail = EmailNormalizer.normalize(email);
        requireAsciiEmail(normalizedEmail);
        requireBcryptSafePassword(password);

        User user = userRepository.findByEmail(normalizedEmail).orElseThrow(() -> new IllegalStateException(
                "Credential provisioning is enabled but no user matches the configured email. "
                        + "Verify IWRITE_CREDENTIAL_PROVISIONING_EMAIL points to an existing user; "
                        + "this runner never creates one."));

        if (credentialRepository.existsById(user.getId())) {
            log.info("Credential provisioning: a credential already exists for the configured user, leaving it untouched");
            return;
        }

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(password));
        credentialRepository.save(credential);

        log.info("Credential provisioning: credential created for the configured user. "
                + "Remove IWRITE_CREDENTIAL_PROVISIONING_ENABLED, _EMAIL and _PASSWORD now.");
    }

    static void requireBothValuesSet(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Credential provisioning is enabled but IWRITE_CREDENTIAL_PROVISIONING_EMAIL and "
                            + "IWRITE_CREDENTIAL_PROVISIONING_PASSWORD are not both set. Provide both, or set "
                            + "IWRITE_CREDENTIAL_PROVISIONING_ENABLED=false.");
        }
    }

    /** ASCII-only policy (#149 review, see EmailNormalizer's class doc): this runner does a
     *  lookup by the normalized value exactly like login and registration do, so it needs the same
     *  guard against the Java/PostgreSQL lowercase divergence a non-ASCII value could hit. The
     *  message never echoes the configured value, matching {@link #requireBothValuesSet}. */
    static void requireAsciiEmail(String normalizedEmail) {
        if (!EmailNormalizer.isAscii(normalizedEmail)) {
            throw new IllegalStateException(
                    "Credential provisioning is enabled but IWRITE_CREDENTIAL_PROVISIONING_EMAIL is not an "
                            + "ASCII email address. This slice only supports ASCII account emails; configure a "
                            + "valid one and retry. This runner never creates a user.");
        }
    }

    /** bcrypt (via {@link PasswordEncoder}) silently ignores any UTF-8 byte past the 72nd — the same
     *  reasoning as {@link PasswordPolicy#MAX_UTF8_BYTES}, applied here because this is another path
     *  that calls {@code passwordEncoder.encode} directly (#149 review). Checked before that call,
     *  and the message never echoes the configured value. */
    static void requireBcryptSafePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_UTF8_BYTES) {
            throw new IllegalStateException(
                    "Credential provisioning is enabled but IWRITE_CREDENTIAL_PROVISIONING_PASSWORD exceeds "
                            + PasswordPolicy.MAX_UTF8_BYTES + " UTF-8 bytes, bcrypt's effective input limit. "
                            + "Configure a shorter password and retry. This runner never echoes the configured value.");
        }
    }
}
