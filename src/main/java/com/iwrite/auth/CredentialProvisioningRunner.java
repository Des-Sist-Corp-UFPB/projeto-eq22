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

import java.util.Optional;

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
 * variables should be removed from the environment right after so the password does not linger in
 * the process configuration.
 *
 * <p><b>{@code replace-existing} (#149 review, fresh finding)</b>: before this slice's login started
 * enforcing {@link BcryptInputPolicy} (72-byte limit, well-formed UTF-16), this runner hashed
 * whatever password it was given, so an installation can already hold a credential for a password
 * longer than that limit — one the new login contract now refuses outright, with no way back in
 * since this runner was, until now, always idempotent. {@code replace-existing=false} (default)
 * keeps that idempotency: an existing credential is left untouched, and the configured password is
 * never validated or hashed, since it will never be used. {@code replace-existing=true} is the
 * explicit escape hatch: it requires a well-formed, <code>&le;72</code>-byte password (same
 * {@link BcryptInputPolicy} bcrypt itself needs) and replaces the stored hash in place, in this same
 * transaction — never a new row, never touching {@code User}/{@code Tenant}/{@code
 * TenantMembership}/{@code UserPersona} or any book. The password and the resulting hash are never
 * logged either way; only that a rotation happened.
 *
 * <p>Upgrade procedure for an account stuck behind an oversized legacy password: (1) set the
 * account's email as {@code IWRITE_CREDENTIAL_PROVISIONING_EMAIL}; (2) choose a new, secure password
 * of at most 72 UTF-8 bytes; (3) enable provisioning with {@code replace-existing=true} for one boot;
 * (4) confirm the new password logs in; (5) remove every {@code IWRITE_CREDENTIAL_PROVISIONING_*}
 * variable, including {@code _REPLACE_EXISTING}.
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
    private final boolean replaceExisting;

    public CredentialProvisioningRunner(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            @Value("${iwrite.auth.credential-provisioning.email:}") String email,
            @Value("${iwrite.auth.credential-provisioning.password:}") String password,
            @Value("${iwrite.auth.credential-provisioning.replace-existing:false}") boolean replaceExisting
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.replaceExisting = replaceExisting;
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

        User user = userRepository.findByEmail(normalizedEmail).orElseThrow(() -> new IllegalStateException(
                "Credential provisioning is enabled but no user matches the configured email. "
                        + "Verify IWRITE_CREDENTIAL_PROVISIONING_EMAIL points to an existing user; "
                        + "this runner never creates one."));

        Optional<UserCredential> existing = credentialRepository.findById(user.getId());

        if (existing.isEmpty()) {
            // Only path that ever creates a row: the configured password must be a safe bcrypt
            // input, checked before it is ever hashed or written.
            requireBcryptSafePassword(password);
            UserCredential credential = new UserCredential();
            credential.setUserId(user.getId());
            credential.setPasswordHash(passwordEncoder.encode(password));
            credentialRepository.save(credential);
            log.info("Credential provisioning: credential created for the configured user. "
                    + "Remove IWRITE_CREDENTIAL_PROVISIONING_ENABLED, _EMAIL and _PASSWORD now.");
            return;
        }

        if (!replaceExisting) {
            // Preserves the pre-existing idempotent contract: the configured password is never
            // validated or hashed here, since a bcrypt-unsafe legacy value that will never be
            // written must not block startup (#149 review).
            log.info("Credential provisioning: a credential already exists for the configured user, leaving it untouched");
            return;
        }

        // Explicit rotation, requested via replace-existing=true: same bcrypt-safety bar as a brand
        // new credential, checked before encode/save so an invalid replacement password never
        // reaches either.
        requireBcryptSafePassword(password);
        UserCredential credential = existing.get();
        credential.setPasswordHash(passwordEncoder.encode(password));
        credentialRepository.save(credential);
        log.info("Credential provisioning: existing credential rotated for the configured user. "
                + "Remove IWRITE_CREDENTIAL_PROVISIONING_ENABLED, _EMAIL, _PASSWORD and _REPLACE_EXISTING now.");
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

    /** bcrypt (via {@link PasswordEncoder}) silently ignores any UTF-8 byte past the 72nd, and
     *  silently substitutes rather than rejects a malformed surrogate — the same reasoning as
     *  {@link BcryptInputPolicy}, applied here because this is another path that calls
     *  {@code passwordEncoder.encode} directly (#149 review). Checked before that call, and the
     *  message never echoes the configured value. */
    static void requireBcryptSafePassword(String password) {
        if (!BcryptInputPolicy.isValid(password, PasswordPolicy.MAX_UTF8_BYTES)) {
            throw new IllegalStateException(
                    "Credential provisioning is enabled but IWRITE_CREDENTIAL_PROVISIONING_PASSWORD is not a "
                            + "valid bcrypt input: it either exceeds " + PasswordPolicy.MAX_UTF8_BYTES
                            + " UTF-8 bytes (bcrypt's effective input limit) or contains malformed UTF-16. "
                            + "Configure a valid, shorter password and retry. This runner never echoes the "
                            + "configured value.");
        }
    }
}
