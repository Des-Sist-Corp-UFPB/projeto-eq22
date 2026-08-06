package com.iwrite.auth;

import java.util.Optional;

/**
 * The one place a password is turned into the UTF-8 bytes bcrypt actually hashes or matches
 * against — shared by registration ({@link PasswordPolicy}), credential provisioning
 * ({@link CredentialProvisioningRunner}), demo seeding ({@link com.iwrite.demo.DemoDataSeeder}) and
 * login ({@link AuthController#login}), so all four agree on what counts as a safe bcrypt input
 * (#149 review).
 *
 * <p>Delegates the actual well-formedness check to {@link WellFormedUtf8} (#149 review, round 8),
 * which has no opinion on bcrypt — this class adds only the 72-byte effective input limit bcrypt
 * itself imposes on top of that generic check.
 */
public final class BcryptInputPolicy {

    private BcryptInputPolicy() {
    }

    /** Empty if {@code password} contains an unpaired surrogate — or otherwise cannot be encoded
     *  strictly as UTF-8 — never a lossy substitution. A valid surrogate pair is unaffected. */
    public static Optional<byte[]> encode(String password) {
        return WellFormedUtf8.encodeStrict(password);
    }

    /** True only if {@code password} encodes strictly to at most {@code maxBytes} UTF-8 bytes —
     *  false for malformed input (see {@link #encode(String)}) or anything past the limit. */
    public static boolean isValid(String password, int maxBytes) {
        return encode(password).map(bytes -> bytes.length <= maxBytes).orElse(false);
    }
}
