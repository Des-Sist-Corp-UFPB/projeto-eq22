package com.iwrite.auth;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Strict UTF-8 encoding of a Java string: rejects malformed UTF-16 (an unpaired surrogate) instead
 * of silently substituting it the way {@code String.getBytes(UTF_8)} does. Generic — has no opinion
 * on bcrypt, passwords, display names, or any other caller-specific semantics. {@link
 * BcryptInputPolicy} delegates here and adds its own 72-byte bcrypt limit on top; {@link
 * RegistrationService} uses {@link #isWellFormed(String)} directly for {@code displayName}, which has
 * nothing to do with bcrypt (#149 review, round 8).
 *
 * <p>An unpaired surrogate is not {@link Character#isISOControl(int)} and {@code
 * String#codePointCount} counts it as one ordinary character, so neither a control-character check
 * nor a length check ever catches it. PostgreSQL cannot represent a surrogate code point at all; the
 * JDBC UTF-8 conversion can otherwise substitute it silently instead of failing loudly.
 */
public final class WellFormedUtf8 {

    private WellFormedUtf8() {
    }

    /** Empty if {@code value} contains an unpaired surrogate — or otherwise cannot be encoded
     *  strictly as UTF-8 — never a lossy substitution. A valid surrogate pair is unaffected. */
    public static Optional<byte[]> encodeStrict(String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Optional.of(bytes);
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    /** True if {@code value} is well-formed UTF-16 (no unpaired surrogate), regardless of length. */
    public static boolean isWellFormed(String value) {
        return encodeStrict(value).isPresent();
    }
}
