package com.iwrite.auth;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The one place a password is turned into the UTF-8 bytes bcrypt actually hashes or matches
 * against — shared by registration ({@link PasswordPolicy}), credential provisioning
 * ({@link CredentialProvisioningRunner}) and login ({@link AuthController#login}), so all three
 * agree on what counts as a safe bcrypt input (#149 review).
 *
 * <p>{@code String.getBytes(StandardCharsets.UTF_8)} is not safe for this: it silently substitutes
 * every unpaired surrogate (half of a broken UTF-16 pair — never producible by typing, but
 * reachable via a crafted request body) with the same replacement byte sequence, so two different
 * malformed passwords can encode to identical bytes and therefore hash identically, or one can
 * authenticate in place of the other. {@link #encode(String)} encodes strictly instead, reporting
 * malformed input rather than substituting it, so a caller can reject it outright.
 */
public final class BcryptInputPolicy {

    private BcryptInputPolicy() {
    }

    /** Empty if {@code password} contains an unpaired surrogate — or otherwise cannot be encoded
     *  strictly as UTF-8 — never a lossy substitution. A valid surrogate pair is unaffected. */
    public static Optional<byte[]> encode(String password) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(password));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Optional.of(bytes);
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    /** True only if {@code password} encodes strictly to at most {@code maxBytes} UTF-8 bytes —
     *  false for malformed input (see {@link #encode(String)}) or anything past the limit. */
    public static boolean isValid(String password, int maxBytes) {
        return encode(password).map(bytes -> bytes.length <= maxBytes).orElse(false);
    }
}
